#!/usr/bin/env bash
# Builds the stack's jars and images (make build). `make up` never builds, so run this after a
# code change.
# Usage: scripts/build.sh [MODULE] [JOBS]
#   1. ./gradlew bootJar in every stack module (services/* with a Dockerfile), or only MODULE,
#      JOBS modules at a time (default 3), each its own Gradle build; logs in build/build/<module>.log
#   2. docker compose build: every image of deploy/docker-compose.yml (each copies its module's
#      build/libs/*.jar), or MODULE's (<module>-init and <module>)
set -eu
. "$(dirname "$0")/lib/modules.sh"
. "$(dirname "$0")/lib/gate.sh"

m="${1:-}"
jobs="${2:-3}"
gate_jobs "$jobs"
[ -f "$STACK_FILE" ] || die "build: deploy/docker-compose.yml does not exist"

svcs=""
if [ -n "$m" ]; then
  name="$(basename "${m%/}")"
  stack_has_service "$name" || die "build: $name is not a service of deploy/docker-compose.yml"
  stack_has_service "$name-init" && svcs="$name-init"
  svcs="$svcs $name"
  # a UI module (ui/*) has no Gradle build: its image builds the app itself
  [ -d "$MW_ROOT/ui/$name" ] && mods="" || mods="$(stack_modules "$m")"
else
  mods="$(stack_modules)"
fi

out="$MW_ROOT/build/build"
rm -rf "$out"; mkdir -p "$out"
failed=""
if [ -n "$mods" ]; then
  # shellcheck disable=SC2086 # one module per word
  info "build: bootJar for $(printf '%s\n' $mods | wc -l | tr -d ' ') module(s), $jobs at a time"
  export MW_ROOT
  # shellcheck disable=SC2086
  printf '%s\n' $mods | xargs -P "$jobs" -n 1 "$(dirname "$0")/lib/build-jar.sh" "$out"
  for mod in $mods; do
    n="$(basename "$mod")"
    r="$(cat "$out/$n.result" 2>/dev/null || echo 'fail ?')"
    [ "${r% *}" = pass ] || failed="$failed $n"
  done
fi
[ -z "$failed" ] || die "build: bootJar failed:$failed (logs in build/build/<module>.log); no image built"

"$(dirname "$0")/gen-dev-env.sh" >/dev/null
# shellcheck disable=SC2086 # one profile per word
stack_compose $STACK_PROFILES
info "build: docker compose build${svcs:+ $svcs}"
# shellcheck disable=SC2086 # one service per word
docker compose "${DC_F[@]}" build $svcs || die "build: docker compose build failed"
info "build: images ready (make up starts them)"
