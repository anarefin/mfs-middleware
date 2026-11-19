# scripts/check-mock-control-prod.sh (./gradlew :mockControlProdCheck, FRS MCT-08, ticket 103): only the test
# doubles turn on the Spring profile mock-control.

compose_with() {
  mkdir -p deploy
  cat >deploy/docker-compose.yml
}

test_mock_control_check_passes_when_only_the_mocks_enable_it() {
  compose_with <<'YAML'
x-jvm-env: &jvm-env
  SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-dev}
services:
  mw-core-mock:
    environment:
      <<: *jvm-env
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-dev},mock-control
  mw-simulators:
    environment:
      SPRING_PROFILES_ACTIVE: mock-control
  mw-orchestrator:
    environment:
      <<: *jvm-env
      # SPRING_PROFILES_ACTIVE: mock-control   (a comment does not count)
YAML
  assert_ok mw_run "$PWD" check-mock-control-prod.sh
}

test_mock_control_check_fails_on_another_service() {
  compose_with <<'YAML'
services:
  mw-orchestrator:
    environment:
      SPRING_PROFILES_ACTIVE: dev,mock-control
YAML
  assert_fails mw_run "$PWD" check-mock-control-prod.sh
  assert_out_contains "mw-orchestrator:4"
}

test_mock_control_check_fails_on_the_shared_anchor() {
  compose_with <<'YAML'
x-jvm-env: &jvm-env
  SPRING_PROFILES_ACTIVE: mock-control
services:
  mw-core-mock:
    environment:
      <<: *jvm-env
YAML
  assert_fails mw_run "$PWD" check-mock-control-prod.sh
  assert_out_contains "outside a service"
}

test_mock_control_check_fails_on_the_env_defaults() {
  compose_with <<'YAML'
services:
  mw-core-mock:
    environment: {}
YAML
  printf 'MW_ENV=local\nSPRING_PROFILES_ACTIVE=dev,mock-control\n' >deploy/dev.env.defaults
  assert_fails mw_run "$PWD" check-mock-control-prod.sh
  assert_out_contains "dev.env.defaults"
}

test_mock_control_check_fails_on_a_module_switching_it_on() {
  compose_with <<'YAML'
services: {}
YAML
  mkdir -p services/mw-a/src/main/resources
  printf 'spring:\n  profiles:\n    active: mock-control\n' >services/mw-a/src/main/resources/application.yml
  assert_fails mw_run "$PWD" check-mock-control-prod.sh
  assert_out_contains "services/mw-a/src/main/resources/application.yml"
}

test_real_repo_enables_mock_control_only_on_the_mocks() {
  OUT="$("$REPO_DIR/scripts/check-mock-control-prod.sh" 2>&1)" || fail "mock-control outside the mocks: $OUT"
}

test_mock_control_check_runs_under_the_root_check() {
  grep -q '"check-mock-control-prod.sh"' "$REPO_DIR/build.gradle.kts" || fail "no root task runs it"
  awk '/tasks\.named\("check"\)/{f=1} f' "$REPO_DIR/build.gradle.kts" | grep -q mockControlProdCheck \
    || fail "root check does not depend on mockControlProdCheck"
}
