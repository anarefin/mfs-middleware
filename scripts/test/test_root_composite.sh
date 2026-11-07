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

plant_module_with_tiers() {
  mkdir -p "services/$1"
  printf 'rootProject.name = "%s"\n' "$1" >"services/$1/settings.gradle.kts"
  cat >"services/$1/build.gradle.kts" <<EOF
plugins { base }
tasks.register("test") { doLast { println("TESTED $1") } }
tasks.register("integrationTest") { doLast { println("IT $1") } }
tasks.register("bootJar") { doLast { println("JARRED $1") } }
tasks.named("clean") { doLast { println("CLEANED $1") } }
tasks.named("check") { dependsOn("integrationTest") }
EOF
}

test_clean_build_runs_test_and_bootJar_after_clean_but_no_integration_tests() {
  command -v gradle >/dev/null || { echo "    (skipped: gradle not on PATH)"; return 0; }
  cp "$REPO_DIR/settings.gradle.kts" "$REPO_DIR/build.gradle.kts" .
  plant_module_with_tiers mw-a
  plant_module_with_tiers mw-b
  OUT="$(gradle -q --offline --no-daemon clean build 2>&1)" || fail "clean build failed: $OUT"
  assert_out_contains "TESTED mw-a"
  assert_out_contains "JARRED mw-b"
  case "$OUT" in *"IT mw-"*) fail "build ran an integration test: $OUT" ;; esac
  # every clean runs before any jar is built (a late clean would delete the jars)
  local last_clean first_jar
  last_clean="$(printf '%s\n' "$OUT" | grep -n '^CLEANED' | tail -1 | cut -d: -f1)"
  first_jar="$(printf '%s\n' "$OUT" | grep -n '^JARRED' | head -1 | cut -d: -f1)"
  [ "$last_clean" -lt "$first_jar" ] || fail "a clean ran after a jar was built: $OUT"
}

test_checkAll_works_with_no_modules() {
  command -v gradle >/dev/null || { echo "    (skipped: gradle not on PATH)"; return 0; }
  cp "$REPO_DIR/settings.gradle.kts" "$REPO_DIR/build.gradle.kts" .
  OUT="$(gradle -q --offline --no-daemon checkAll 2>&1)" || fail "checkAll failed"
}

test_repo_root_itself_passes_isolation() {
  OUT="$(make -s -C "$REPO_DIR" SELFTEST=0 check-isolation 2>&1)" || fail "repo root violates isolation"
}
