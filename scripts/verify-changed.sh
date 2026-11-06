#!/usr/bin/env bash
# Quality gate for what a change touches (ticket 53): `make verify` for the changed modules only,
# several at a time.
# Usage: scripts/verify-changed.sh [BASE] [JOBS]     (BASE defaults to main, JOBS to 3)
#   1. modules: scripts/changed-modules.sh --contracts-precise BASE (a changed contract selects
#      the modules whose contracts.yml lists it, plus its owner; ALL selects every module)
#   2. isolation, field-injection, contracts lint and compose-check, as `make verify` runs them
#   3. ./gradlew check (test, integrationTest, conformance, JaCoCo) per selected module, JOBS at a
#      time; each module's output goes to build/verify-changed/<module>.log; Testcontainers reuse
#      as in verify.sh (scripts/lib/gate.sh), reused containers removed at the end unless KEEP_TC=1
#   4. a summary: pass/fail and duration per module; exits non-zero on any failure
# Warns when the stack (deploy/docker-compose.yml) is running: its containers compete with Testcontainers for
# Docker memory.
set -eu
. "$(dirname "$0")/lib/modules.sh"
. "$(dirname "$0")/lib/gate.sh"

here="$(cd "$(dirname "$0")" && pwd)"
out="$MW_ROOT/build/verify-changed"

base="${1:-main}"
jobs="${2:-3}"
gate_jobs "$jobs"

changed="$("$here/changed-modules.sh" --contracts-precise "$base")"
if [ "$changed" = ALL ]; then
  info "verify-changed: deploy/, scripts/, the root build, contracts/common or OWNERS.yml changed: every module"
  modules="$(all_modules)"
else
  modules="$(for m in $changed; do selected_modules "$m"; done)"
fi
info "verify-changed: modules since $base: $(printf '%s' "${modules:-none}" | xargs -n1 basename 2>/dev/null | tr '\n' ' ')"

gate_prechecks verify-changed ""
if [ -f "$STACK_FILE" ] && command -v docker >/dev/null 2>&1; then
  stack_compose
  if [ -n "$(docker compose "${DC_F[@]}" ps -q 2>/dev/null || true)" ]; then
    printf 'warning: the stack (deploy/docker-compose.yml) is running; its containers compete with the Testcontainers tests for Docker memory (make down to stop it)\n' >&2
  fi
fi

if [ -z "$modules" ]; then
  info "verify-changed passed: no module changed since $base"
  exit 0
fi

status=0
# shellcheck disable=SC2086 # one module per word
gate_check verify-changed "$out" "$jobs" $modules || status=1
tc_cleanup
[ "$status" -eq 0 ] || exit 1
info "verify-changed passed: $(printf '%s\n' $modules | wc -l | tr -d ' ') module(s)"
