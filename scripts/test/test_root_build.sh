# The root Gradle build (spec 4.2, D13, D14): a multi-module build whose subprojects are the
# services/* folders with a build.gradle.kts; the root holds module discovery and the repo checks
# (wired into the root `check`, so `./gradlew clean build` runs them), never shared build logic.

settings="$REPO_DIR/settings.gradle.kts"
root_build="$REPO_DIR/build.gradle.kts"

# code FILE: the file without whole-line // comments
code() { grep -vE '^[[:space:]]*(//|/\*|\*)' "$1"; }

test_settings_auto_includes_every_service_folder_with_a_build_file() {
  code "$settings" | grep -q 'rootProject.name = "mfs-middleware"' || fail "rootProject.name is not mfs-middleware"
  code "$settings" | grep -q 'file("services")' || fail "settings does not discover services/*"
  code "$settings" | grep -q 'build.gradle.kts' || fail "settings does not require a build.gradle.kts"
  code "$settings" | grep -qE 'include\(dir\.name\)' || fail "settings does not include each folder by name"
  code "$settings" | grep -q 'projectDir = dir' || fail "settings does not point the project at services/<name>"
}

test_settings_has_no_composite_or_shared_configuration() {
  if code "$settings" | grep -qE 'includeBuild|pluginManagement|dependencyResolutionManagement|versionCatalogs'; then
    fail "settings.gradle.kts holds more than module discovery"
  fi
  if code "$settings" | grep -qE 'templates|tests/perf'; then fail "settings includes templates/ or tests/perf"; fi
  return 0
}

test_every_services_folder_is_a_plain_subproject() {
  local d n=0
  for d in "$REPO_DIR"/services/*/; do
    d="${d%/}"
    [ -f "$d/build.gradle.kts" ] || fail "$(basename "$d") has no build.gradle.kts, so it is not part of the build"
    for f in settings.gradle.kts settings.gradle gradlew gradlew.bat gradle/wrapper gradle/libs.versions.toml; do
      [ ! -e "$d/$f" ] || fail "services/$(basename "$d")/$f: a module has no settings, wrapper or catalog of its own"
    done
    grep -q 'rootProject\.name' "$d/build.gradle.kts" && fail "services/$(basename "$d")/build.gradle.kts uses rootProject.name (use project.name)"
    n=$((n + 1))
  done
  [ "$n" -gt 0 ] || fail "no services/* modules"
}

test_root_build_shares_nothing_with_the_modules() {
  if code "$root_build" | grep -qE '(^|[^A-Za-z])(allprojects|subprojects)[[:space:]]*[{(]|apply[[:space:]]*\(?[[:space:]]*from|dependencies[[:space:]]*\{'; then
    fail "root build.gradle.kts configures other projects or applies shared logic"
  fi
  # only the core base plugin (root lifecycle tasks)
  local plugins
  plugins="$(awk '/^plugins[[:space:]]*\{/{f=1;next} f&&/^\}/{exit} f' "$root_build" | grep -vE '^[[:space:]]*(//|$)' | tr -d ' ')"
  [ "$plugins" = base ] || fail "root plugins block is not just base: $plugins"
  [ ! -e "$REPO_DIR/buildSrc" ] || fail "root buildSrc/ exists"
  [ ! -e "$REPO_DIR/gradle/libs.versions.toml" ] || fail "root version catalog exists"
}

test_root_check_depends_on_every_repo_check() {
  local t s
  for t in checkIsolation:check-isolation.sh checkFieldInjection:check-field-injection.sh \
           contractsLint:contracts-lint.sh composeCheck:compose-check.sh scriptTests:test/run.sh; do
    s="${t#*:}"; t="${t%%:*}"
    code "$root_build" | grep -q "\"$t\"" || fail "no root task $t"
    code "$root_build" | grep -q "\"$s\"" || fail "root task $t does not run scripts/$s"
    [ -f "$REPO_DIR/scripts/$s" ] || fail "scripts/$s does not exist"
    awk '/tasks\.named\("check"\)/{f=1} f' "$root_build" | grep -q "$t" || fail "root check does not depend on $t"
  done
  code "$root_build" | grep -q 'group = "verification"' || fail "repo checks are not in group verification"
  code "$root_build" | grep -q 'outputs.file' || fail "repo checks declare no output (never up to date)"
}

test_gradle_properties_enable_parallel_cached_builds_with_a_docker_budget() {
  local p="$REPO_DIR/gradle.properties"
  grep -q '^org.gradle.parallel=true' "$p" || fail "parallel off"
  grep -q '^org.gradle.caching=true' "$p" || fail "build cache off"
  grep -q '^org.gradle.workers.max=' "$p" || fail "no workers.max (Docker memory budget)"
  grep -q '^mwContractsDir=../../contracts' "$p" || fail "mwContractsDir not ../../contracts"
}

test_no_makefile_and_no_composite_scripts_remain() {
  local f
  for f in Makefile scripts/build.sh scripts/verify.sh scripts/verify-changed.sh scripts/changed-modules.sh \
           scripts/up.sh scripts/seed.sh scripts/down.sh scripts/lib/modules.sh scripts/lib/gate.sh \
           scripts/lib/check-module.sh scripts/lib/build-jar.sh; do
    [ ! -e "$REPO_DIR/$f" ] || fail "$f is back (./gradlew, docker compose and the kept scripts replace it)"
  done
  # no kept script calls make or a per-module wrapper
  local hits
  hits="$(grep -nE '(^|[;&|(`[:space:]])make [a-z]|cd [^ ]*services/[^ ]* && \./gradlew|services/[^ ]*/gradlew|lib/modules\.sh' \
    "$REPO_DIR"/scripts/*.sh "$REPO_DIR"/tests/api/run.sh "$REPO_DIR"/deploy/*.sh "$REPO_DIR"/templates/mw-service-template/scaffold.sh || true)"
  [ -z "$hits" ] || fail "make / per-module wrapper still used: $hits"
}
