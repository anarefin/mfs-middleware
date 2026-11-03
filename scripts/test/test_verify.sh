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
