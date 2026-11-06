#!/usr/bin/env bash
# Runs a black-box suite in tests/<suite> (its own Gradle build) against the running stack.
# Usage: scripts/suite.sh perf [MODULE]
#   perf: ./gradlew gatlingRun, then ./gradlew perfOverhead (always, also after a failed run, so
#         the overhead breakdown of a missed target is written); extra -D settings from
#         MW_PERF_ARGS (e.g. "-Dmw.perf.steady=10m"); MODULE is passed as -Dmw.module=<module>
#         (the mix always covers the whole stack)
# It needs the test-only gateway routes and re-applies them first. (The API suite has its own
# runner, tests/api/run.sh, which applies them the same way: make api-test.)
# Passes (does nothing) while the suite does not exist yet.
set -eu
. "$(dirname "$0")/lib/modules.sh"

suite="${1:?suite name (perf)}"
m="${2:-}"
name=""
if [ -n "$m" ]; then
  mod="$(selected_modules "$m")"
  name="$(basename "$mod")"
fi

dir="$MW_ROOT/tests/$suite"
if [ ! -x "$dir/gradlew" ]; then
  info "$suite: no $suite suite in tests/$suite yet, nothing to run"
  exit 0
fi

# The suite's test-only gateway routes (deploy/apisix/routes-test: core-mock's test API, the
# simulators' control API, behind key-auth) are applied only when apisix-init runs with
# MW_E2E_ROUTES=1; a default `make up` removes them. Re-run it on the running stack with the flag
# and wait for it.
enable_test_routes() {
  [ -f "$STACK_FILE" ] || return 0
  "$(dirname "$0")/gen-dev-env.sh"
  stack_compose
  set -a; . "$STACK_ENV"; set +a
  info "$suite: enabling the test-only gateway routes (apisix-init with MW_E2E_ROUTES=1)"
  MW_E2E_ROUTES=1 docker compose "${DC_F[@]}" up -d --no-deps --force-recreate apisix-init >/dev/null
  docker compose "${DC_F[@]}" wait apisix-init >/dev/null \
    || die "$suite: apisix-init failed to apply the test routes (docker compose logs apisix-init); is the stack up (make up)?"
}

args=()
case "$suite" in
  perf)
    [ -n "$name" ] && args+=("-Dmw.module=$name")
    # shellcheck disable=SC2206 # word splitting of the extra settings is intended
    [ -n "${MW_PERF_ARGS:-}" ] && args+=(${MW_PERF_ARGS})
    enable_test_routes
    info "perf: tests/perf ./gradlew gatlingRun ${args[*]:-}"
    rc=0
    (cd "$dir" && ./gradlew gatlingRun ${args[@]+"${args[@]}"}) || rc=$?
    info "perf: overhead breakdown (./gradlew perfOverhead)"
    (cd "$dir" && ./gradlew perfOverhead ${args[@]+"${args[@]}"}) || rc=1
    [ "$rc" = 0 ] || die "perf: target missed or run failed; see tests/perf/build/reports/perf/overhead.md and the Gatling report in tests/perf/build/reports/gatling/"
    exit 0
    ;;
  *) die "unknown suite '$suite'" ;;
esac
