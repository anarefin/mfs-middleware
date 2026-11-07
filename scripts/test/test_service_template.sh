# templates/mw-service-template: scaffolding (make new-service) and container wiring.
# Runs against a copy of the real template in a fixture root; no Gradle or Docker needed.

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
  assert_ok mw_make "$PWD" check-isolation M=mw-service-template
  assert_out_contains "1 module(s)"
}

test_new_service_scaffolds_a_clean_copy() {
  copy_template "$PWD"
  assert_ok mw_make "$PWD" new-service NAME=mw-demo
  d=services/mw-demo
  [ -f $d/src/main/java/mfs/mw/demo/DemoApplication.java ] || fail "no DemoApplication: $(find $d/src -name '*.java' | head)"
  [ -f $d/src/main/java/mfs/mw/demo/util/MaskingPolicy.java ] || fail "layered packages not moved"
  [ -f $d/src/test/java/mfs/mw/demo/util/MaskingPolicyTest.java ] || fail "tests not moved"
  [ ! -d $d/src/main/java/mfs/mw/service ] || fail "old package dir left behind"
  grep -q '^package mfs.mw.demo;' $d/src/main/java/mfs/mw/demo/DemoApplication.java || fail "package not renamed"
  grep -q 'rootProject.name = "mw-demo"' $d/settings.gradle.kts || fail "rootProject.name not renamed"
  grep -q 'name: mw-demo' $d/src/main/resources/application.yml || fail "application name not renamed"
  [ ! -e $d/compose.yml ] || fail "the copy has its own compose.yml (ADR D16: one deploy/docker-compose.yml)"
  # instead it prints the block to paste into deploy/docker-compose.yml
  assert_out_contains "deploy/docker-compose.yml"
  assert_out_contains "  mw-demo:"
  assert_out_contains "image: mfs-mw/mw-demo:local"
  assert_out_contains "build: { context: ../services/mw-demo, args: *jvm-build-args }"
  assert_out_lacks "mw-demo-init"
  assert_out_contains "demo-approle:/run/mw/approle:ro"
  # and its provisioning: the provision volume and the services.sh entry
  assert_out_contains "- demo-approle:/out/demo"
  assert_out_contains "deploy/provision/services.sh (append mw-demo:demo to SERVICES"
  assert_out_contains "provision_mw_demo() {"
  assert_out_contains "db_role demo_rw demo"
  grep -q 'schemas: demo$' $d/src/main/resources/application.yml || fail "DB schema not renamed"
  grep -q 'COMMENT ON SCHEMA demo ' $d/src/main/resources/db/migration/V1__baseline.sql || fail "migration schema not renamed"
  [ -x $d/gradlew ] || fail "gradlew not executable"
  [ -f $d/gradle/wrapper/gradle-wrapper.jar ] || fail "wrapper jar missing"
  [ ! -e $d/scaffold.sh ] || fail "scaffold.sh copied"
  grep -q '# mw-demo' $d/CLAUDE.md || fail "CLAUDE.md not generated for the service"
  refs="$(back_refs $d)"
  [ -z "$refs" ] || fail "references back to the template in: $refs"
  assert_ok mw_make "$PWD" check-isolation M=mw-demo
}

test_new_service_maps_hyphens_to_package_dots() {
  copy_template "$PWD"
  assert_ok mw_make "$PWD" new-service NAME=mw-adapter-telco
  d=services/mw-adapter-telco
  [ -f $d/src/main/java/mfs/mw/adapter/telco/AdapterTelcoApplication.java ] || fail "wrong package path"
  grep -q '^package mfs.mw.adapter.telco.filter;' $d/src/main/java/mfs/mw/adapter/telco/filter/CorrelationIdFilter.java || fail "sub-package not renamed"
  assert_out_contains "image: mfs-mw/mw-adapter-telco:local"
  grep -q 'schemas: adapter_telco$' $d/src/main/resources/application.yml || fail "DB schema not snake_case"
  [ -z "$(back_refs $d)" ] || fail "references back to the template"
}

test_new_service_rejects_names_that_are_not_java_packages() {
  copy_template "$PWD"
  assert_fails mw_make "$PWD" new-service NAME=mw-new
  assert_out_contains "Java"
  assert_fails mw_make "$PWD" new-service NAME=mw-adapter-2fa
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

# Spec 4.3 rule 1: a copy of the template plus contracts/ runs
# `./gradlew check bootJar && docker build .` from its own directory alone. Slow (the
# module's full check, Testcontainers included); skipped with a note when Docker is down.
test_template_copy_checks_and_builds_its_image_alone() {
  if ! docker info >/dev/null 2>&1; then
    echo "    SKIP: Docker not running" >&2; return 0
  fi
  copy_template "$PWD"
  (cd "$REPO_DIR/contracts" && tar cf - .) | (cd "$PWD/contracts" && tar xf -)
  tag="mwtest-template:$$"
  OUT="$(cd templates/mw-service-template && ./gradlew -q check bootJar 2>&1 && docker build -q -t "$tag" . 2>&1)" \
    || fail "./gradlew check bootJar && docker build . failed in the copy"
  docker image inspect "$tag" >/dev/null 2>&1 || fail "image $tag not built"
  docker rmi -f "$tag" >/dev/null 2>&1 || true
}

test_template_check_runs_the_coverage_gate() {
  # spec 22.1: >= 80% line coverage (JaCoCo) on service, mapper and client, in `check`
  # (make verify).
  local b="$TEMPLATE_SRC/build.gradle.kts"
  grep -qE '^[[:space:]]*jacoco$' "$b" || fail "jacoco plugin not applied"
  grep -q 'jacocoTestCoverageVerification' "$b" || fail "no coverage verification task configured"
  grep -qE 'minimum = "?0\.80"?\.toBigDecimal\(\)|minimum = BigDecimal\("0\.80"\)' "$b" || fail "gate is not 0.80"
  grep -qE 'counter = "LINE"' "$b" || fail "gate does not count lines"
  for p in service mapper client; do
    grep -q "\"$p\"" "$b" || fail "gate does not cover the $p package"
  done
  grep -qE 'tasks\.check \{[^}]*jacocoTestCoverageVerification' "$b" || fail "coverage gate not wired into check"
  grep -q 'toolVersion = libs.versions.jacoco' "$b" || fail "JaCoCo version not pinned from the catalog"
}

test_no_static_analysis_tool_in_the_template_docs_or_scripts() {
  # M0 review (ticket 49): SpotBugs/FindSecBugs dropped; `make verify` runs no static analysis.
  [ -e "$TEMPLATE_SRC/config/spotbugs" ] && fail "config/spotbugs still present"
  local hits
  hits="$(grep -rliE 'spotbugs|findsecbugs' "$TEMPLATE_SRC/build.gradle.kts" "$TEMPLATE_SRC/gradle/libs.versions.toml" \
    "$TEMPLATE_SRC/CLAUDE.md" "$TEMPLATE_SRC/scaffold.sh" "$REPO_DIR/CLAUDE.md" "$REPO_DIR/.github/pull_request_template.md" \
    "$REPO_DIR/Makefile" "$REPO_DIR/scripts"/*.sh 2>/dev/null)"
  [ -z "$hits" ] || fail "SpotBugs still mentioned in: $hits"
  grep -qi 'static analysis' "$REPO_DIR/scripts/verify.sh" "$REPO_DIR/Makefile" && fail "verify still claims static analysis"
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
