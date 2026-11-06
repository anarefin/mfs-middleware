# make verify: per-module and all-module quality gate.

test_verify_passes_with_no_modules() {
  assert_ok mw_make "$PWD" verify
  assert_out_contains "no modules"
}

test_verify_runs_gradle_check_in_a_single_dummy_module() {
  new_module "$PWD" services/mw-dummy
  assert_ok mw_make "$PWD" verify
  grep -q 'check' services/mw-dummy/.gradlew-calls || fail "gradlew check not run"
}

test_verify_fails_when_a_module_check_fails() {
  new_module "$PWD" services/mw-dummy
  touch services/mw-dummy/.gradlew-fail
  assert_fails mw_make "$PWD" verify
}

test_verify_with_M_runs_only_that_module() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  assert_ok mw_make "$PWD" verify M=mw-a
  [ -f services/mw-a/.gradlew-calls ] || fail "mw-a not checked"
  [ ! -f services/mw-b/.gradlew-calls ] || fail "mw-b checked although M=mw-a"
}

test_verify_without_M_runs_every_module() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  assert_ok mw_make "$PWD" verify
  [ -f services/mw-a/.gradlew-calls ] || fail "mw-a not checked"
  [ -f services/mw-b/.gradlew-calls ] || fail "mw-b not checked"
}

test_verify_with_unknown_M_fails() {
  assert_fails mw_make "$PWD" verify M=mw-nope
  assert_out_contains "unknown module"
}

test_verify_fails_when_isolation_is_broken() {
  new_module "$PWD" services/mw-a
  echo 'includeBuild("../mw-b")' >>services/mw-a/settings.gradle.kts
  assert_fails mw_make "$PWD" verify
  assert_out_contains "ISOLATION"
  [ ! -f services/mw-a/.gradlew-calls ] || fail "gradle ran despite isolation failure"
}

# Ticket 54: modules JOBS at a time, Testcontainers reuse, reused containers removed afterwards.

# docker_stub: a docker on PATH that records its arguments in .docker-calls, prints one container
# id for `ps`, and fails everything else (so compose-check is skipped).
docker_stub() {
  mkdir -p .bin
  printf '#!/bin/sh\necho "$*" >>"%s/.docker-calls"\ncase "$1" in ps) echo c0ffee; exit 0 ;; rm) exit 0 ;; esac\nexit 1\n' "$PWD" >.bin/docker
  chmod +x .bin/docker
}

test_verify_checks_modules_JOBS_at_a_time_with_a_log_and_a_summary() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  assert_ok mw_make "$PWD" verify JOBS=2
  [ -f build/verify/mw-a.log ] || fail "no per-module log for mw-a"
  [ -f build/verify/mw-b.log ] || fail "no per-module log for mw-b"
  assert_out_contains "verify summary"
  assert_out_contains "mw-b"
  assert_out_contains "pass"
}

test_verify_with_JOBS_1_still_checks_every_module_and_names_the_failure() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  touch services/mw-a/.gradlew-fail
  assert_fails mw_make "$PWD" verify JOBS=1
  [ -f services/mw-b/.gradlew-calls ] || fail "mw-b not checked after mw-a failed"
  assert_out_contains "verify FAILED: mw-a"
}

test_verify_rejects_a_bad_JOBS_value() {
  new_module "$PWD" services/mw-a
  assert_fails mw_make "$PWD" verify JOBS=0
  assert_out_contains "JOBS must be a positive number"
}

test_verify_enables_testcontainers_reuse_for_gradle() {
  new_module "$PWD" services/mw-a
  printf '#!/bin/sh\necho "reuse=$TESTCONTAINERS_REUSE_ENABLE" >>"$(dirname "$0")/.gradlew-env"\n' >services/mw-a/gradlew
  assert_ok mw_make "$PWD" verify
  grep -qx 'reuse=true' services/mw-a/.gradlew-env || fail "TESTCONTAINERS_REUSE_ENABLE not true: $(cat services/mw-a/.gradlew-env 2>/dev/null)"
}

test_verify_removes_the_reused_test_containers_unless_KEEP_TC() {
  new_module "$PWD" services/mw-a
  docker_stub
  PATH="$PWD/.bin:$PATH" mw_make "$PWD" verify || fail "verify failed"
  grep -q 'ps -aq --filter label=mw.module' .docker-calls || fail "no labelled container lookup: $(cat .docker-calls)"
  grep -q "label=mw.root=$PWD" .docker-calls || fail "lookup not scoped to this checkout: $(cat .docker-calls)"
  grep -q '^rm -f c0ffee' .docker-calls || fail "reused containers not removed: $(cat .docker-calls)"
  rm -f .docker-calls
  PATH="$PWD/.bin:$PATH" mw_make "$PWD" verify KEEP_TC=1 || fail "verify KEEP_TC=1 failed"
  ! grep -q '^rm' .docker-calls 2>/dev/null || fail "containers removed although KEEP_TC=1"
}

test_verify_with_M_removes_only_that_modules_containers() {
  new_module "$PWD" services/mw-a
  docker_stub
  PATH="$PWD/.bin:$PATH" mw_make "$PWD" verify M=mw-a || fail "verify failed"
  grep -q 'label=mw.module=mw-a' .docker-calls || fail "mw-a containers not looked up: $(cat .docker-calls)"
  ! grep -q 'filter label=mw.module --filter' .docker-calls || fail "M= removed every module's containers"
}
