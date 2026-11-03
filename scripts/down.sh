#!/usr/bin/env bash
# Stops the MVP Compose project. Usage: scripts/down.sh   (DOWN_ARGS=-v also drops volumes)
set -eu
. "$(dirname "$0")/lib/modules.sh"

file="$MW_ROOT/deploy/compose/mvp/compose.yml"
[ -f "$file" ] || die "down: deploy/compose/mvp/compose.yml does not exist"
"$(dirname "$0")/gen-dev-env.sh"   # compose needs the variables even to tear down
info "down: docker compose -f ${file#"$MW_ROOT"/} down ${DOWN_ARGS:-}"
docker compose -f "$file" down ${DOWN_ARGS:-}
