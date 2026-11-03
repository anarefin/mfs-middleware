#!/usr/bin/env bash
# Local quality gate (no CI/CD in the MVP).
# Usage: scripts/verify.sh [MODULE]
#   1. isolation check (D13, D14)   2. no @Autowired field injection   3. contracts lint
#   4. ./gradlew check in each selected module (unit, route, Testcontainers
#      integration tests and coverage as the module configures them)
set -eu
. "$(dirname "$0")/lib/modules.sh"

m="${1:-}"
modules="$(selected_modules "$m")"

"$(dirname "$0")/check-isolation.sh" "$m"
"$(dirname "$0")/check-field-injection.sh" "$m"
"$(dirname "$0")/contracts-lint.sh"

if [ -z "$modules" ]; then
  info "verify: no modules found under: $MODULE_PARENTS (nothing to build)"
  exit 0
fi

failed=""
for mod in $modules; do
  info "verify $mod: ./gradlew check"
  [ -x "$MW_ROOT/$mod/gradlew" ] || { printf 'error: %s has no executable gradlew\n' "$mod" >&2; failed="$failed $mod"; continue; }
  if ! (cd "$MW_ROOT/$mod" && ./gradlew check ${GRADLE_ARGS:-}); then
    failed="$failed $mod"
  fi
done

if [ -n "$failed" ]; then
  printf 'verify FAILED:%s\n' "$failed" >&2
  exit 1
fi
info "verify passed: $(printf '%s\n' "$modules" | wc -l | tr -d ' ') module(s)"
