#!/usr/bin/env bash
# Quality gate for what a change touches (ticket 53): `make verify` for the changed modules only,
# several at a time.
# Usage: scripts/verify-changed.sh [BASE] [JOBS]     (BASE defaults to main, JOBS to 3)
#   1. modules: scripts/changed-modules.sh --contracts-precise BASE (a changed contract selects
#      the modules whose contracts.yml lists it, plus its owner; ALL selects every module)
#   2. isolation, field-injection, contracts lint and compose-check, as `make verify` runs them
#   3. ./gradlew check (test, integrationTest, conformance, JaCoCo) per selected module, JOBS at a
#      time; each module's output goes to build/verify-changed/<module>.log
#   4. a summary: pass/fail and duration per module; exits non-zero on any failure
# Warns when the MVP compose project is running: its containers compete with Testcontainers for
# Docker memory.
set -eu
. "$(dirname "$0")/lib/modules.sh"

here="$(cd "$(dirname "$0")" && pwd)"
out="$MW_ROOT/build/verify-changed"

# --one MODULE: run ./gradlew check in one module, log to $out/<name>.log, result to $out/<name>.result.
if [ "${1:-}" = --one ]; then
  mod="$2"; name="$(basename "$mod")"
  start="$(date +%s)"
  status=fail
  if [ -x "$MW_ROOT/$mod/gradlew" ] \
    && (cd "$MW_ROOT/$mod" && ./gradlew check ${GRADLE_ARGS:-}) >"$out/$name.log" 2>&1; then
    status=pass
  elif [ ! -x "$MW_ROOT/$mod/gradlew" ]; then
    printf 'error: %s has no executable gradlew\n' "$mod" >"$out/$name.log"
  fi
  printf '%s %s\n' "$status" "$(( $(date +%s) - start ))" >"$out/$name.result"
  printf '==> verify-changed %s: %s (%ss)\n' "$name" "$status" "$(( $(date +%s) - start ))"
  exit 0
fi

base="${1:-main}"
jobs="${2:-3}"
case "$jobs" in '' | *[!0-9]* | 0) die "verify-changed: JOBS must be a positive number, got '$jobs'" ;; esac

changed="$("$here/changed-modules.sh" --contracts-precise "$base")"
if [ "$changed" = ALL ]; then
  info "verify-changed: deploy/, scripts/, the root build, contracts/common or OWNERS.yml changed: every module"
  modules="$(all_modules)"
else
  modules="$(for m in $changed; do selected_modules "$m"; done)"
fi
info "verify-changed: modules since $base: $(printf '%s' "${modules:-none}" | xargs -n1 basename 2>/dev/null | tr '\n' ' ')"

"$here/check-isolation.sh" ""
"$here/check-field-injection.sh" ""
"$here/contracts-lint.sh"
if docker compose version >/dev/null 2>&1; then
  "$here/compose-flat.sh" --check
else
  info "verify-changed: docker compose CLI not found, skipping compose-check"
fi
file="$MW_ROOT/deploy/compose/mvp/compose.yml"
if [ -f "$file" ] && command -v docker >/dev/null 2>&1; then
  compose_files "$file"
  if [ -n "$(docker compose "${DC_F[@]}" ps -q 2>/dev/null || true)" ]; then
    printf 'warning: the MVP compose project is running; its containers compete with the Testcontainers tests for Docker memory (make down to stop it)\n' >&2
  fi
fi

if [ -z "$modules" ]; then
  info "verify-changed passed: no module changed since $base"
  exit 0
fi

rm -rf "$out"
mkdir -p "$out"
printf '%s\n' $modules | xargs -P "$jobs" -I{} "$here/verify-changed.sh" --one {}

failed=""
printf '\nverify-changed summary (logs in build/verify-changed/):\n'
for mod in $modules; do
  name="$(basename "$mod")"
  result="fail ?"
  [ -f "$out/$name.result" ] && result="$(cat "$out/$name.result")"
  printf '  %-24s %s %ss\n' "$name" "${result% *}" "${result#* }"
  [ "${result% *}" = pass ] || failed="$failed $name"
done

if [ -n "$failed" ]; then
  printf 'verify-changed FAILED:%s\n' "$failed" >&2
  exit 1
fi
info "verify-changed passed: $(printf '%s\n' $modules | wc -l | tr -d ' ') module(s)"
