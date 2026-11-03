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
  grep -rIlE 'service-template|service\.template|service/template|ServiceTemplate|SERVICE_TEMPLATE|templates/' "$1" || true
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
  grep -q 'mw-demo:' $d/compose.yml || fail "compose service not renamed"
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
  grep -q 'MW_ADAPTER_TELCO_IMAGE' $d/compose.yml || fail "image variable not renamed"
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
  df="$TEMPLATE_SRC/Dockerfile"; cf="$TEMPLATE_SRC/compose.yml"
  user="$(grep -E '^USER ' "$df" | tail -1 | awk '{print $2}')"
  [ -n "$user" ] || fail "Dockerfile has no USER"
  case "$user" in root|0|0:*) fail "Dockerfile runs as root: $user" ;; esac
  grep -q 'HEALTHCHECK' "$df" || fail "Dockerfile has no HEALTHCHECK"
  grep -q '/actuator/health/readiness' "$df" || fail "Dockerfile healthcheck not on readiness"
  grep -q 'healthcheck:' "$cf" || fail "compose.yml has no healthcheck"
  grep -q '/actuator/health/readiness' "$cf" || fail "compose healthcheck not on readiness"
  grep -q 'read_only: true' "$cf" || fail "compose.yml root FS not read-only"
  grep -q 'restart: unless-stopped' "$cf" || fail "compose.yml restart policy missing"
}
