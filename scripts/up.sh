#!/usr/bin/env bash
# Starts the local stack.
# Usage: scripts/up.sh [MODULE]
#   no MODULE: docker compose up the MVP project (deploy/compose/mvp/compose.yml,
#              which includes the platform and every module's compose.yml)
#   MODULE:    docker compose up only that module's own compose.yml (it joins the
#              external zone networks, so the platform project must already be up)
# Then waits until every long-running service is healthy (one-shot init containers in
# MW_UP_ONESHOT are skipped). MW_UP_TIMEOUT (s, default 300) and MW_UP_POLL (s, default 3).
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

oneshot=" ${MW_UP_ONESHOT:-kafka-init etcd-init} "
timeout="${MW_UP_TIMEOUT:-300}"
poll="${MW_UP_POLL:-3}"
# not_healthy: names of long-running services that are not healthy yet. A service without a
# healthcheck counts once it is running.
not_healthy() {
  docker compose -f "$file" ps -a --format '{{.Service}}	{{.State}}	{{.Health}}' |
    while IFS="$(printf '\t')" read -r svc state health; do
      [ -n "$svc" ] || continue
      case "$oneshot" in *" $svc "*) continue ;; esac
      if [ "$health" = healthy ] || { [ -z "$health" ] && [ "$state" = running ]; }; then continue; fi
      printf '%s(%s) ' "$svc" "${health:-$state}"
    done
}
info "up: waiting up to ${timeout}s for every long-running service to be healthy"
start=$SECONDS
while :; do
  pending="$(not_healthy)"
  [ -z "$pending" ] && { info "up: all long-running services are healthy"; exit 0; }
  [ $((SECONDS - start)) -ge "$timeout" ] && die "up: timed out after ${timeout}s; not healthy: ${pending% }"
  sleep "$poll"
done
