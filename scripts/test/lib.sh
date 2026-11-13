# Shared helpers for the shell test suite of scripts/ (bash 3.2 compatible, BSD/GNU tools).
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

# new_module ROOT DIR: plant a minimal module at ROOT/DIR: a subproject of the root build (a
# build.gradle.kts with inline versions; no settings file, wrapper or version catalog of its own).
new_module() {
  local root="$1" dir="$2"
  mkdir -p "$root/$dir"
  cat >"$root/$dir/build.gradle.kts" <<'EOF'
plugins {
    java
    id("org.springframework.boot") version "4.0.0"
}
dependencies {
    implementation(platform("org.apache.camel.springboot:camel-spring-boot-bom:4.14.0"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
}
EOF
}

# mw_run ROOT [VAR=VAL...] SCRIPT [ARG...]: run scripts/SCRIPT of this repo against a fixture root
# (MW_ROOT=ROOT, plus the VAR=VAL environment). Output goes to $OUT; exit status is returned.
mw_run() {
  local root="$1"; shift
  local envs=()
  while [ $# -gt 0 ]; do
    case "$1" in
      [A-Za-z_]*=*) envs+=("$1"); shift ;;
      *) break ;;
    esac
  done
  local script="$1"; shift
  OUT="$(env MW_ROOT="$root" ${envs[@]+"${envs[@]}"} "$REPO_DIR/scripts/$script" "$@" 2>&1)"
}

# root_gradlew: a stub ./gradlew at the fixture root that records its arguments and, for
# :mw-x:bootJar, writes services/mw-x/build/libs/mw-x.jar (the fixed jar name).
root_gradlew() {
  cat >gradlew <<'EOF'
#!/bin/sh
cd "$(dirname "$0")"
echo "$*" >> .gradlew-calls
for a in "$@"; do
  case "$a" in :*:bootJar) m="${a#:}"; m="${m%:bootJar}"; mkdir -p "services/$m/build/libs"; touch "services/$m/build/libs/$m.jar" ;; esac
done
exit 0
EOF
  chmod +x gradlew
}

fail() { printf '    FAIL: %s\n' "$*" >&2; [ -n "${OUT:-}" ] && printf '%s\n' "$OUT" | sed 's/^/      | /' >&2; exit 1; }

assert_ok() { "$@" || fail "expected success: $*"; }
assert_fails() { if "$@"; then fail "expected failure: $*"; fi; }
assert_out_contains() { case "$OUT" in *"$1"*) ;; *) fail "output lacks '$1'";; esac; }
assert_out_lacks() { case "$OUT" in *"$1"*) fail "output unexpectedly has '$1'";; esac; }
