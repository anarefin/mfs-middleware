#!/usr/bin/env bash
# Stops the stack (deploy/docker-compose.yml), every profile.
# Usage: scripts/down.sh            DOWN_ARGS=-v also drops its volumes
#        scripts/down.sh --reset    every container, volume and orphan of every profile (make reset,
#                                   as BitsPay's deploy/reset.sh): the next make up starts fresh
set -eu
. "$(dirname "$0")/lib/modules.sh"

[ -f "$STACK_FILE" ] || die "down: deploy/docker-compose.yml does not exist"
"$(dirname "$0")/gen-dev-env.sh" >/dev/null   # compose needs the variables even to tear down
# shellcheck disable=SC2086 # one profile per word
stack_compose $STACK_PROFILES
if [ "${1:-}" = --reset ]; then
  info "reset: docker compose down -v --remove-orphans (every profile)"
  docker compose "${DC_F[@]}" down -v --remove-orphans
  info "reset: every container and volume of the stack removed; make up starts fresh"
else
  info "down: docker compose down ${DOWN_ARGS:-}"
  # shellcheck disable=SC2086
  docker compose "${DC_F[@]}" down ${DOWN_ARGS:-}
fi
