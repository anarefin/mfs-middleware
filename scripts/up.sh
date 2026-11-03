#!/usr/bin/env bash
# Starts the local stack.
# Usage: scripts/up.sh [MODULE]
#   no MODULE: docker compose up the MVP project (deploy/compose/mvp/compose.yml,
#              which includes the platform and every module's compose.yml)
#   MODULE:    docker compose up only that module's own compose.yml (it joins the
#              external zone networks, so the platform project must already be up)
set -eu
. "$(dirname "$0")/lib/modules.sh"

m="${1:-}"
if [ -n "$m" ]; then
  mod="$(selected_modules "$m")"
  file="$MW_ROOT/$mod/compose.yml"
  [ -f "$file" ] || die "$mod has no compose.yml"
else
  file="$MW_ROOT/deploy/compose/mvp/compose.yml"
  if [ ! -f "$file" ]; then
    info "up: deploy/compose/mvp/compose.yml does not exist yet, nothing to start"
    exit 0
  fi
  "$(dirname "$0")/gen-dev-env.sh"
fi
info "up: docker compose -f ${file#"$MW_ROOT"/} up -d"
docker compose -f "$file" up -d ${COMPOSE_ARGS:-}
