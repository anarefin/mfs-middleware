#!/usr/bin/env bash
# Starts the local stack.
# Usage: scripts/up.sh [MODULE]
#   no MODULE: docker compose up the MVP project (deploy/compose/mvp/compose.yml,
#              which includes the platform and every module's compose.yml)
#   MODULE:    docker compose up only that module's own compose.yml (it joins the
#              external zone networks, so the platform project must already be up)
# Then waits until every long-running service is healthy. One-shot init containers carry the
# compose label mw.oneshot=true (restart "no", no healthcheck) and are not waited for.
# Fails at once, naming the service, when a long-running service is exited, dead or unhealthy,
# or a one-shot exits non-zero. MW_UP_TIMEOUT (s, default 300) and MW_UP_POLL (s, default 3).
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

timeout="${MW_UP_TIMEOUT:-300}"
poll="${MW_UP_POLL:-3}"
# check: one line per service, "failed <svc>(<why>)" or "pending <svc>(<state>)"; healthy
# services and running or finished one-shots print nothing. A long-running service without a
# healthcheck counts once it is running.
check() {
  docker compose -f "$file" ps -a --format '{{.Service}}	{{.State}}	{{.Health}}	{{.ExitCode}}	{{.Labels}}' |
    # tab is IFS whitespace, so empty fields (no health) would collapse: split on | instead
    tr '\t' '|' | while IFS='|' read -r svc state health code labels; do
      [ -n "$svc" ] || continue
      case ",$labels," in
        *,mw.oneshot=true,*)
          if [ "$state" = exited ] && [ "${code:-0}" != 0 ]; then printf 'failed %s(exit %s)\n' "$svc" "$code"; fi
          if [ "$state" = dead ]; then printf 'failed %s(dead)\n' "$svc"; fi
          continue ;;
      esac
      case "$state/$health" in
        exited/* | dead/*) printf 'failed %s(%s)\n' "$svc" "$state" ;;
        */unhealthy) printf 'failed %s(unhealthy)\n' "$svc" ;;
        */healthy | running/) ;;
        *) printf 'pending %s(%s)\n' "$svc" "${health:-$state}" ;;
      esac
    done
}
names() { sed -n "s/^$1 //p" | tr '\n' ' '; }
info "up: waiting up to ${timeout}s for every long-running service to be healthy"
start=$SECONDS
while :; do
  status="$(check)"
  failed="$(printf '%s\n' "$status" | names failed)"
  [ -n "$failed" ] && die "up: failed: ${failed% }"
  pending="$(printf '%s\n' "$status" | names pending)"
  [ -z "$pending" ] && { info "up: all long-running services are healthy"; exit 0; }
  [ $((SECONDS - start)) -ge "$timeout" ] && die "up: timed out after ${timeout}s; not healthy: ${pending% }"
  sleep "$poll"
done
