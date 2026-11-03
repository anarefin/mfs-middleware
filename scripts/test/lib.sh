# Shared helpers for the shell test suite (bash 3.2 compatible, BSD/GNU tools).
# Each test file defines functions named test_*; run.sh sources this file and the
# test file, then calls every test_* function in a fresh subshell.

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# new_root: create an empty fixture repo root (services/, contracts/) and print its path.
new_root() {
  local d
  d="$(mktemp -d "${TMPDIR:-/tmp}/mwtest.XXXXXX")"
  mkdir -p "$d/services" "$d/contracts"
  printf '%s\n' "$d"
}

# new_module ROOT DIR: plant a minimal standalone Gradle module at ROOT/DIR.
# Its gradlew is a stub that records the tasks it was asked to run.
new_module() {
  local root="$1" dir="$2" name
  name="$(basename "$dir")"
  mkdir -p "$root/$dir/gradle"
  printf 'rootProject.name = "%s"\n' "$name" >"$root/$dir/settings.gradle.kts"
  cat >"$root/$dir/build.gradle.kts" <<'EOF'
plugins { java }
dependencies { testImplementation("org.junit.jupiter:junit-jupiter:5.13.4") }
EOF
  printf 'mwContractsDir=../../contracts\n' >"$root/$dir/gradle.properties"
  cat >"$root/$dir/gradle/libs.versions.toml" <<'EOF'
[versions]
spring-boot = "4.0.0"
camel = "4.14.0"

[libraries]
junit = { module = "org.junit.jupiter:junit-jupiter", version = "5.13.4" }
EOF
  cat >"$root/$dir/gradlew" <<'EOF'
#!/bin/sh
echo "$*" >> "$(dirname "$0")/.gradlew-calls"
[ -f "$(dirname "$0")/.gradlew-fail" ] && exit 1
exit 0
EOF
  chmod +x "$root/$dir/gradlew"
}

# mw_make ROOT TARGET [VAR=VAL...]: run the repo Makefile against a fixture root.
# Output goes to $OUT; exit status is returned.
mw_make() {
  local root="$1"; shift
  OUT="$(make -s -f "$REPO_DIR/Makefile" MW_ROOT="$root" SELFTEST=0 "$@" 2>&1)"
}

fail() { printf '    FAIL: %s\n' "$*" >&2; [ -n "${OUT:-}" ] && printf '%s\n' "$OUT" | sed 's/^/      | /' >&2; exit 1; }

assert_ok() { "$@" || fail "expected success: $*"; }
assert_fails() { if "$@"; then fail "expected failure: $*"; fi; }
assert_out_contains() { case "$OUT" in *"$1"*) ;; *) fail "output lacks '$1'";; esac; }
assert_out_lacks() { case "$OUT" in *"$1"*) fail "output unexpectedly has '$1'";; esac; }
