#!/usr/bin/env bash
# Starts the local stack.
# Usage: scripts/up.sh [MODULE]
#   no MODULE: docker compose up the MVP project (deploy/compose/mvp/compose.yml,
#              which includes the platform and every module's compose.yml)
#   MODULE:    docker compose up only that module's own compose.yml (it joins the
#              external zone networks, so the platform project must already be up)
# Then waits until every long-running service is healthy and every one-shot has finished. One-shot
# init containers carry the compose label mw.oneshot=true (restart "no", no healthcheck): they
# count once exited 0 (services can be healthy before e.g. kafka-init has created their topics).
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
# Never run stale code: an existing jar or mw-x:dev / mw-x-init:dev image may predate the
# source. So every stack module's jar is rebuilt (images copy build/libs/*.jar + the SBOM;
# Gradle is incremental, so this is cheap when nothing changed), one module at a time (parallel
# Gradle daemons are unreliable here), and compose rebuilds every image it builds (services and
# their init images; the layer cache keeps it cheap). MW_UP_NO_BUILD=1 skips both (faster, at
# your own risk: whatever jars and images exist are used).
build_args="--build"
if [ "${MW_UP_NO_BUILD:-0}" = 1 ]; then
  info "up: MW_UP_NO_BUILD=1: using existing jars and images as they are"
  build_args=""
else
  if [ -n "$m" ]; then stack_mods="$mod"; else stack_mods="$(cd "$MW_ROOT" && ls -d services/*/ 2>/dev/null | sed 's#/$##')"; fi
  for s in $stack_mods; do
    [ -f "$MW_ROOT/$s/Dockerfile" ] && [ -f "$MW_ROOT/$s/compose.yml" ] && [ -x "$MW_ROOT/$s/gradlew" ] || continue
    info "up: $s ./gradlew bootJar"
    (cd "$MW_ROOT/$s" && ./gradlew bootJar -q) || die "up: $s: bootJar failed"
  done
fi
case " ${COMPOSE_ARGS:-} " in *" --build "*) build_args="" ;; esac

info "up: docker compose -f ${file#"$MW_ROOT"/} up -d $build_args ${COMPOSE_ARGS:-}"
docker compose -f "$file" up -d $build_args ${COMPOSE_ARGS:-}

timeout="${MW_UP_TIMEOUT:-300}"
poll="${MW_UP_POLL:-3}"
# check: one line per service, "failed <svc>(<why>)" or "pending <svc>(<state>)"; healthy
# services and one-shots that exited 0 print nothing. A long-running service without a
# healthcheck counts once it is running.
check() {
  docker compose -f "$file" ps -a --format '{{.Service}}	{{.State}}	{{.Health}}	{{.ExitCode}}	{{.Labels}}' |
    # tab is IFS whitespace, so empty fields (no health) would collapse: split on | instead
    tr '\t' '|' | while IFS='|' read -r svc state health code labels; do
      [ -n "$svc" ] || continue
      case ",$labels," in
        *,mw.oneshot=true,*)
          case "$state" in
            exited) [ "${code:-0}" = 0 ] || printf 'failed %s(exit %s)\n' "$svc" "$code" ;;
            dead) printf 'failed %s(dead)\n' "$svc" ;;
            # still provisioning (e.g. kafka-init creating topics the healthy services need)
            *) printf 'pending %s(%s)\n' "$svc" "$state" ;;
          esac
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
  [ -z "$pending" ] && { info "up: all long-running services are healthy, one-shots done"; exit 0; }
  [ $((SECONDS - start)) -ge "$timeout" ] && die "up: timed out after ${timeout}s; not healthy: ${pending% }"
  sleep "$poll"
done
