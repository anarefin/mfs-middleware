#!/usr/bin/env bash
# End-to-end check after a code change, without `make verify`: rebuild only the modules changed
# since BASE, swap them into the running MVP stack, then run the Cucumber suite (tests/e2e).
# Usage: scripts/e2e-changed.sh [BASE] [TAGS]     (BASE defaults to main)
#   - changed modules: scripts/changed-modules.sh BASE (commits since BASE, staged, unstaged,
#     untracked)
#   - stack down, or contracts/ or deploy/ changed (ALL): full `scripts/up.sh` (every module)
#   - otherwise: MW_UP_ONLY="<changed>" scripts/up.sh (bootJar + recreate those services only);
#     no module changed: no rebuild, e2e still runs (e.g. only tests/e2e changed)
#   - e2e: the full suite by default, since a change in one module can break another module's
#     flow; E2E_SCOPE=modules runs only scenarios tagged with a changed module (@mw-a or @mw-b).
#     TAGS narrows either further.
# bootJar runs no tests: this is a fast feedback loop, not the gate (`make verify` still is).
set -eu
. "$(dirname "$0")/lib/modules.sh"

base="${1:-main}"
tags="${2:-}"
scope="${E2E_SCOPE:-all}"
here="$(dirname "$0")"
file="$MW_ROOT/deploy/compose/mvp/compose.yml"

changed="$("$here/changed-modules.sh" "$base")"
info "e2e-changed: changed since $base: $(printf '%s' "${changed:-none}" | tr '\n' ' ')"

running=""
[ -f "$file" ] && running="$(docker compose -f "$file" ps -q 2>/dev/null || true)"

if [ "$changed" = ALL ] || [ -z "$running" ]; then
  [ -z "$running" ] && info "e2e-changed: stack is not up: starting all of it"
  [ "$changed" = ALL ] && info "e2e-changed: contracts/ or deploy/ changed: rebuilding the whole stack"
  "$here/up.sh" ""
elif [ -n "$changed" ]; then
  MW_UP_ONLY="$(printf '%s' "$changed" | tr '\n' ' ')" "$here/up.sh" ""
else
  info "e2e-changed: no module changed: running e2e against the stack as it is"
fi

filter="$tags"
if [ "$scope" = modules ] && [ -n "$changed" ] && [ "$changed" != ALL ]; then
  mods="$(printf '%s\n' "$changed" | sed 's/^/@/' | paste -sd ' ' - | sed 's/ / or /g')"
  filter="($mods)${tags:+ and ($tags)}"
fi
exec "$here/suite.sh" e2e "" "$filter"
