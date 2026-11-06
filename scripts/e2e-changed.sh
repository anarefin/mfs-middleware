#!/usr/bin/env bash
# End-to-end check after a code change, without `make verify`: build only the modules changed
# since BASE, recreate them in the running stack, then run the Cucumber suite (tests/e2e).
# Usage: scripts/e2e-changed.sh [BASE] [TAGS]     (BASE defaults to main)
#   - changed modules: scripts/changed-modules.sh BASE (commits since BASE, staged, unstaged,
#     untracked)
#   - stack down, or contracts/ or deploy/ changed (ALL): `make build` (every module), then
#     `make up` (the whole stack, PROFILE / TIMERS as given)
#   - otherwise: `make build M=<module>` then `make up M=<module>` for each changed module (Compose
#     recreates the services whose image changed); no module changed: no build, e2e still runs
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

changed="$("$here/changed-modules.sh" "$base")"
info "e2e-changed: changed since $base: $(printf '%s' "${changed:-none}" | tr '\n' ' ')"

running=""
if [ -f "$STACK_FILE" ]; then
  stack_compose
  running="$(docker compose "${DC_F[@]}" ps -q 2>/dev/null || true)"
fi

if [ "$changed" = ALL ] || [ -z "$running" ]; then
  [ -z "$running" ] && info "e2e-changed: stack is not up: building and starting all of it"
  [ "$changed" = ALL ] && info "e2e-changed: contracts/ or deploy/ changed: rebuilding the whole stack"
  "$here/build.sh" "" "${JOBS:-3}"
  "$here/up.sh" ""
elif [ -n "$changed" ]; then
  for mod in $changed; do
    "$here/build.sh" "$mod" 1
    "$here/up.sh" "$mod"
  done
else
  info "e2e-changed: no module changed: running e2e against the stack as it is"
fi

filter="$tags"
if [ "$scope" = modules ] && [ -n "$changed" ] && [ "$changed" != ALL ]; then
  mods="$(printf '%s\n' "$changed" | sed 's/^/@/' | paste -sd ' ' - | sed 's/ / or /g')"
  filter="($mods)${tags:+ and ($tags)}"
fi
exec "$here/suite.sh" e2e "" "$filter"
