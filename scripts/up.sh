#!/usr/bin/env bash
# Starts the local stack from deploy/docker-compose.yml (ADR D16). Never builds: images are reused
# as they are (`make build` makes them), so a warm `make up` only starts containers.
# Usage: scripts/up.sh [MODULE]
#   PROFILE=core (default) | minimal   which services start (the platform always does)
#   TIMERS=fast                        also load deploy/test-timers.env (short test timers)
#   MODULE                             only that service, plus what it depends on
# `docker compose up -d --wait` returns once every started service is healthy (one-shots exited
# 0, as their dependants require) and fails naming the one that is not. MW_UP_TIMEOUT (s, 300).
# COMPOSE_ARGS adds arguments to `up` (e.g. --force-recreate).
set -eu
. "$(dirname "$0")/lib/modules.sh"

m="${1:-}"
if [ ! -f "$STACK_FILE" ]; then
  info "up: deploy/docker-compose.yml does not exist yet, nothing to start"
  exit 0
fi
profile="${PROFILE:-core}"
case "$profile" in core | minimal) ;; *) die "PROFILE must be core or minimal, got '$profile'" ;; esac
svc=""
if [ -n "$m" ]; then
  svc="$(basename "${m%/}")"
  stack_has_service "$svc" || die "up: $svc is not a service of deploy/docker-compose.yml"
fi
"$(dirname "$0")/gen-dev-env.sh"
stack_compose "$profile"

# `up --wait` treats a one-shot that exits as a failure unless a started service depends on it, so
# the whole-stack start names the long-running services (their one-shot dependencies start and must
# exit 0 first), then runs each one-shot nothing depends on (apisix-init) and waits for its exit 0.
targets="$svc"
tail_oneshots=""
if [ -z "$svc" ] && command -v jq >/dev/null 2>&1; then
  model="$(docker compose "${DC_F[@]}" config --format json 2>/dev/null || true)"
  if [ -n "$model" ]; then
    targets="$(printf '%s' "$model" | jq -r '.services | to_entries[]
      | select((.value.labels // {})["mw.oneshot"] != "true") | .key' | sort | tr '\n' ' ')"
    tail_oneshots="$(printf '%s' "$model" | jq -r '[.services[] | (.depends_on // {}) | keys[]] as $deps
      | .services | to_entries[] | select((.value.labels // {})["mw.oneshot"] == "true")
      | select(.key as $k | $deps | index($k) | not) | .key' | sort | tr '\n' ' ')"
  fi
fi

info "up: docker compose --profile $profile${TIMERS:+ (TIMERS=$TIMERS)} up -d --wait ${COMPOSE_ARGS:-}${svc:+ $svc}"
# shellcheck disable=SC2086 # COMPOSE_ARGS and the service lists are lists of words
docker compose "${DC_F[@]}" up -d --wait --wait-timeout "${MW_UP_TIMEOUT:-300}" ${COMPOSE_ARGS:-} $targets \
  || die "up: the stack did not come up healthy (docker compose ps -a; docker compose logs <service>)"
for o in $tail_oneshots; do
  docker compose "${DC_F[@]}" up -d ${COMPOSE_ARGS:-} "$o" >/dev/null \
    && docker compose "${DC_F[@]}" wait "$o" >/dev/null \
    || die "up: one-shot $o did not exit 0 (docker compose logs $o)"
done
info "up: every started service is healthy, one-shots done"
