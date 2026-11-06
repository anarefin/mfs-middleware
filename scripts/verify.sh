#!/usr/bin/env bash
# Local quality gate (no CI/CD in the MVP).
# Usage: scripts/verify.sh [MODULE] [JOBS]     (JOBS defaults to 3; 1 is the old serial run)
#   1. isolation check (D13, D14)   2. no @Autowired field injection   3. contracts lint
#   3b. generated root compose.yml up to date, memory budget kept (compose-flat.sh --check)
#   4. ./gradlew check in each selected module (unit, route, Testcontainers integration and
#      conformance tests and coverage as the module configures them), JOBS modules at a time; each
#      module's output goes to build/verify/<module>.log, then a pass/fail and duration summary.
#      Testcontainers reuse is on (scripts/lib/gate.sh): each module's PostgreSQL and Kafka are
#      shared by its test JVMs and removed when its check ends; without MODULE every reused
#      container of this checkout is removed at the end. KEEP_TC=1 keeps them.
set -eu
. "$(dirname "$0")/lib/modules.sh"
. "$(dirname "$0")/lib/gate.sh"

m="${1:-}"
jobs="${2:-3}"
gate_jobs "$jobs"
modules="$(selected_modules "$m")"

"$(dirname "$0")/check-isolation.sh" "$m"
"$(dirname "$0")/check-field-injection.sh" "$m"
"$(dirname "$0")/contracts-lint.sh"
# the generated root compose.yml matches its sources and the stack fits the memory budget
if docker compose version >/dev/null 2>&1; then
  "$(dirname "$0")/compose-flat.sh" --check
else
  info "verify: docker compose CLI not found, skipping compose-check"
fi

if [ -z "$modules" ]; then
  info "verify: no modules found under: $MODULE_PARENTS (nothing to build)"
  exit 0
fi

status=0
# shellcheck disable=SC2086 # one module per word
gate_check verify "$MW_ROOT/build/verify" "$jobs" $modules || status=1
[ -n "$m" ] || tc_cleanup
[ "$status" -eq 0 ] || exit 1
info "verify passed: $(printf '%s\n' "$modules" | wc -l | tr -d ' ') module(s)"
