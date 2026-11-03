# Root composite build (spec 4.2): includeBuild per services/* module, aggregate tasks only.
# Uses the real Gradle (system `gradle`), so it is slower than the other tests.

plant_real_module() {
  mkdir -p "services/$1"
  printf 'rootProject.name = "%s"\n' "$1" >"services/$1/settings.gradle.kts"
  printf 'plugins { base }\ntasks.named("check") { doLast { println("CHECKED %s") } }\n' "$1" >"services/$1/build.gradle.kts"
}

test_checkAll_runs_check_in_every_service_but_not_the_template() {
  command -v gradle >/dev/null || { echo "    (skipped: gradle not on PATH)"; return 0; }
  cp "$REPO_DIR/settings.gradle.kts" "$REPO_DIR/build.gradle.kts" .
  plant_real_module mw-a
  plant_real_module mw-b
  mkdir -p templates/mw-service-template
  printf 'rootProject.name = "tpl"\n' >templates/mw-service-template/settings.gradle.kts
  OUT="$(gradle -q --offline --no-daemon checkAll 2>&1)" || fail "checkAll failed"
  assert_out_contains "CHECKED mw-a"
  assert_out_contains "CHECKED mw-b"
}

test_checkAll_works_with_no_modules() {
  command -v gradle >/dev/null || { echo "    (skipped: gradle not on PATH)"; return 0; }
  cp "$REPO_DIR/settings.gradle.kts" "$REPO_DIR/build.gradle.kts" .
  OUT="$(gradle -q --offline --no-daemon checkAll 2>&1)" || fail "checkAll failed"
}

test_repo_root_itself_passes_isolation() {
  OUT="$(make -s -C "$REPO_DIR" SELFTEST=0 check-isolation 2>&1)" || fail "repo root violates isolation"
}
