#!/usr/bin/env bash
# Scaffolds a new service from templates/mw-service-template (spec 4.3, WP03).
# Usage: scripts/new-service.sh mw-<name>
# services/mw-<name> is picked up by the root settings.gradle.kts (./gradlew :mw-<name>:build). The copy
# and rename (package mfs.mw.<name>, project name, no reference back to the template) belong to the
# template's own scaffold.sh, which also prints the deploy/docker-compose.yml block to paste; called as:
#   templates/mw-service-template/scaffold.sh <NAME> <DEST_DIR>
set -eu
. "$(dirname "$0")/lib/common.sh"

name="${1:-}"
[ -n "$name" ] || die "usage: scripts/new-service.sh mw-<name>"
printf '%s' "$name" | grep -Eq '^mw-[a-z][a-z0-9]*(-[a-z0-9]+)*$' \
  || die "NAME must look like mw-<name> (lowercase letters, digits, single hyphens): '$name'"

dest="$MW_ROOT/services/$name"
[ ! -e "$dest" ] || die "services/$name already exists"

scaffold="$MW_ROOT/templates/mw-service-template/scaffold.sh"
[ -x "$scaffold" ] || die "templates/mw-service-template/scaffold.sh not found (the template arrives with WP03)"

"$scaffold" "$name" "$dest"
info "new-service: created services/$name"
