# templates/mw-service-template: scaffolding (scripts/new-service.sh) and container wiring.
# Runs against a copy of the real template in a fixture root; only the root-build check runs Gradle.

TEMPLATE_SRC="$REPO_DIR/templates/mw-service-template"

# copy_template ROOT: the real template (without build output) into a fixture root.
copy_template() {
  mkdir -p "$1/templates/mw-service-template"
  (cd "$TEMPLATE_SRC" && tar cf - --exclude ./build --exclude ./.gradle .) \
    | (cd "$1/templates/mw-service-template" && tar xf -)
}

# back_refs DIR: print text files under DIR that still name the template.
back_refs() {
  grep -rIlE 'service-template|service_template|service\.template|service/template|ServiceTemplate|SERVICE_TEMPLATE|templates/' "$1" || true
}

test_template_is_a_module_and_passes_isolation() {
  copy_template "$PWD"
  assert_ok mw_run "$PWD" check-isolation.sh mw-service-template
  assert_out_contains "1 module(s)"
}

test_new_service_scaffolds_a_clean_copy() {
  copy_template "$PWD"
  assert_ok mw_run "$PWD" new-service.sh mw-demo
  d=services/mw-demo
  [ -f $d/src/main/java/mfs/mw/demo/DemoApplication.java ] || fail "no DemoApplication: $(find $d/src -name '*.java' | head)"
  [ -f $d/src/main/java/mfs/mw/demo/util/MaskingPolicy.java ] || fail "layered packages not moved"
  [ -f $d/src/test/java/mfs/mw/demo/util/MaskingPolicyTest.java ] || fail "tests not moved"
  [ ! -d $d/src/main/java/mfs/mw/service ] || fail "old package dir left behind"
  grep -q '^package mfs.mw.demo;' $d/src/main/java/mfs/mw/demo/DemoApplication.java || fail "package not renamed"
  grep -q 'archiveFileName = "mw-demo.jar"' $d/build.gradle.kts || fail "boot jar name not renamed"
  grep -q 'COPY build/libs/mw-demo.jar app.jar' $d/Dockerfile || fail "Dockerfile jar name not renamed"
  grep -q '!build/libs/mw-demo.jar' $d/.dockerignore || fail ".dockerignore jar name not renamed"
  grep -q 'name: mw-demo' $d/src/main/resources/application.yml || fail "application name not renamed"
  [ ! -e $d/compose.yml ] || fail "the copy has its own compose.yml (ADR D16: one deploy/docker-compose.yml)"
  # instead it prints the block to paste into deploy/docker-compose.yml
  assert_out_contains "deploy/docker-compose.yml"
  assert_out_contains "  mw-demo:"
  assert_out_contains "image: mfs-mw/mw-demo:local"
  assert_out_contains "build: { context: ../services/mw-demo, args: *jvm-build-args }"
  assert_out_contains 'memory: "${APP_MEM_LIMIT:-416M}"'
  assert_out_contains "./gradlew :mw-demo:bootJar"
  assert_out_lacks "mw-demo-init"
  assert_out_contains "demo-approle:/run/mw/approle:ro"
  # and its provisioning: the provision volume and the services.sh entry
  assert_out_contains "- demo-approle:/out/demo"
  assert_out_contains "deploy/provision/services.sh (append mw-demo:demo to SERVICES"
  assert_out_contains "provision_mw_demo() {"
  assert_out_contains "db_role demo_rw demo"
  grep -q 'schemas: demo$' $d/src/main/resources/application.yml || fail "DB schema not renamed"
  grep -q 'COMMENT ON SCHEMA demo ' $d/src/main/resources/db/migration/V1__baseline.sql || fail "migration schema not renamed"
  # a plain subproject of the root build: no settings, wrapper or catalog of its own
  for f in settings.gradle.kts gradlew gradlew.bat gradle gradle.properties; do
    [ ! -e $d/$f ] || fail "the copy has its own $f"
  done
  [ ! -e $d/scaffold.sh ] || fail "scaffold.sh copied"
  grep -q '# mw-demo' $d/CLAUDE.md || fail "CLAUDE.md not generated for the service"
  refs="$(back_refs $d)"
  [ -z "$refs" ] || fail "references back to the template in: $refs"
  assert_ok mw_run "$PWD" check-isolation.sh mw-demo
}

test_new_service_maps_hyphens_to_package_dots() {
  copy_template "$PWD"
  assert_ok mw_run "$PWD" new-service.sh mw-adapter-telco
  d=services/mw-adapter-telco
  [ -f $d/src/main/java/mfs/mw/adapter/telco/AdapterTelcoApplication.java ] || fail "wrong package path"
  grep -q '^package mfs.mw.adapter.telco.filter;' $d/src/main/java/mfs/mw/adapter/telco/filter/CorrelationIdFilter.java || fail "sub-package not renamed"
  assert_out_contains "image: mfs-mw/mw-adapter-telco:local"
  grep -q 'schemas: adapter_telco$' $d/src/main/resources/application.yml || fail "DB schema not snake_case"
  [ -z "$(back_refs $d)" ] || fail "references back to the template"
}

test_new_service_rejects_names_that_are_not_java_packages() {
  copy_template "$PWD"
  assert_fails mw_run "$PWD" new-service.sh mw-new
  assert_out_contains "Java"
  assert_fails mw_run "$PWD" new-service.sh mw-adapter-2fa
  [ ! -e services/mw-new ] && [ ! -e services/mw-adapter-2fa ] || fail "partial module left behind"
}

test_container_runs_as_non_root_with_health_wired_to_compose() {
  local df="$TEMPLATE_SRC/Dockerfile" cf="$REPO_DIR/deploy/docker-compose.yml" app
  user="$(grep -E '^USER ' "$df" | tail -1 | awk '{print $2}')"
  [ -n "$user" ] || fail "Dockerfile has no USER"
  case "$user" in root|0|0:*) fail "Dockerfile runs as root: $user" ;; esac
  grep -q 'HEALTHCHECK' "$df" || fail "Dockerfile has no HEALTHCHECK"
  grep -q '/actuator/health/readiness' "$df" || fail "Dockerfile healthcheck not on readiness"
  # every service block a new module pastes merges x-app (deploy/docker-compose.yml)
  app="$(awk '/^x-app:/{f=1;next} f&&/^[a-z]/{exit} f' "$cf")"
  printf '%s' "$app" | grep -q 'healthcheck:' || fail "x-app has no healthcheck"
  printf '%s' "$app" | grep -q '/actuator/health/readiness' || fail "x-app healthcheck not on readiness"
  printf '%s' "$app" | grep -q 'read_only: true' || fail "x-app root FS not read-only"
  printf '%s' "$app" | grep -q 'restart: unless-stopped' || fail "x-app restart policy missing"
}

# A scaffolded copy is a subproject of the root build as soon as it exists (settings.gradle.kts
# discovers services/* with a build.gradle.kts): the real root settings, run by the real wrapper in a
# fixture root, list it. Configuration only (no compile, no tests); skipped when no JDK is around.
test_scaffolded_service_is_picked_up_by_the_root_build() {
  if ! command -v java >/dev/null 2>&1 && [ -z "${JAVA_HOME:-}" ]; then
    echo "    SKIP: no JDK" >&2; return 0
  fi
  copy_template "$PWD"
  cp -R "$REPO_DIR/gradlew" "$REPO_DIR/gradle" "$REPO_DIR/settings.gradle.kts" "$REPO_DIR/gradle.properties" .
  printf 'plugins { base }\n' >build.gradle.kts
  assert_ok mw_run "$PWD" new-service.sh mw-demo
  OUT="$(./gradlew -q --offline --no-daemon --no-configuration-cache projects 2>&1)" \
    || OUT="$(./gradlew -q --no-daemon --no-configuration-cache projects 2>&1)" \
    || fail "./gradlew projects failed in the fixture root"
  assert_out_contains ":mw-demo"
}

test_template_has_a_single_test_task_and_no_coverage_gate() {
  # One test task per module (unit, slice, contract, *IT, @Conformance in one JVM), no JaCoCo gate.
  local b="$TEMPLATE_SRC/build.gradle.kts"
  grep -qE '^[[:space:]]*jacoco$|jacocoTestCoverageVerification' "$b" && fail "JaCoCo still applied"
  grep -qE 'register<Test>|integrationTest|conformance"' "$b" && fail "extra test tasks still registered"
  grep -q 'tasks.test' "$b" || fail "no tasks.test configuration"
  grep -q 'useJUnitPlatform' "$b" || fail "JUnit Platform not used"
  grep -q 'libs\.' "$b" && fail "the template uses a version catalog (versions are inline)"
  return 0
}

test_no_static_analysis_tool_in_the_template_docs_or_scripts() {
  # M0 review (ticket 49): SpotBugs/FindSecBugs dropped; ./gradlew build runs no static analysis.
  [ -e "$TEMPLATE_SRC/config/spotbugs" ] && fail "config/spotbugs still present"
  local hits
  hits="$(grep -rliE 'spotbugs|findsecbugs' "$TEMPLATE_SRC/build.gradle.kts" \
    "$TEMPLATE_SRC/CLAUDE.md" "$TEMPLATE_SRC/scaffold.sh" "$REPO_DIR/CLAUDE.md" "$REPO_DIR/.github/pull_request_template.md" \
    "$REPO_DIR/build.gradle.kts" "$REPO_DIR/scripts"/*.sh 2>/dev/null)"
  [ -z "$hits" ] || fail "SpotBugs still mentioned in: $hits"
  return 0
}

test_scheduler_jobs_only_call_the_service_layer() {
  # Spec 4.4 (review of 48/49): scheduler -> service; SQL lives in service/ or repository/.
  local main="$TEMPLATE_SRC/src/main/java/mfs/mw/service/template" hits
  hits="$(grep -liE 'JdbcTemplate|delete from|insert into|select .* from' "$main"/scheduler/*.java)"
  [ -z "$hits" ] || fail "scheduler classes hold SQL or JdbcTemplate: $hits"
  grep -q 'RetentionService' "$main/scheduler/OutboxInboxPurgeJob.java" || fail "purge job does not call service/RetentionService"
  [ -f "$main/service/RetentionService.java" ] || fail "no service/RetentionService"
  # both batch subqueries in key order (inbox has no id column: its key is (consumer, event_id))
  grep -q 'order by id limit' "$main/service/RetentionService.java" || fail "outbox batch subquery not ordered by id"
  grep -q 'order by consumer, event_id limit' "$main/service/RetentionService.java" \
    || fail "inbox batch subquery not ordered by its key like the outbox one"
}

test_retention_settings_live_under_one_prefix() {
  local y="$TEMPLATE_SRC/src/main/resources/application.yml" block k
  block="$(awk '/^  retention:/{f=1;next} f&&/^  [a-z]/{f=0} f' "$y")"
  for k in 'outbox: 7d' 'inbox: 7d' 'batch-size:' 'cron:'; do
    printf '%s\n' "$block" | grep -q "^    $k" || fail "mw.retention lacks $k"
  done
  grep -qE '^  purge:|^    retention:' "$y" && fail "old mw.purge / mw.<table>.retention keys remain"
  grep -q 'mw.retention.outbox' "$TEMPLATE_SRC/CLAUDE.md" || fail "template CLAUDE.md does not name mw.retention.*"
  return 0
}
