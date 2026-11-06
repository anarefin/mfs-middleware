#!/usr/bin/env bash
# Seeds the running stack (make seed): runs the seed profile's one-shot mw-seeder (ticket 57), fails
# unless it exits 0, then copies its manifest (seeded ids and personas, no secret) out of the
# seed-manifest volume to build/seed/manifest.json for the API and Playwright suites (MW_SEED_MANIFEST
# overrides the path). Idempotent: a second run changes nothing and logs every step unchanged.
# A no-op while deploy/docker-compose.yml has no mw-seeder service.
set -eu
. "$(dirname "$0")/lib/modules.sh"

[ -f "$STACK_FILE" ] || die "seed: deploy/docker-compose.yml does not exist"
if ! stack_has_service mw-seeder; then
  info "seed: no mw-seeder service in deploy/docker-compose.yml yet (ticket 57), nothing to seed"
  exit 0
fi
"$(dirname "$0")/gen-dev-env.sh" >/dev/null
stack_compose core seed
info "seed: docker compose --profile seed up -d mw-seeder, then wait for its exit"
# --force-recreate: a container that already exited 0 would otherwise not run again
docker compose "${DC_F[@]}" up -d --force-recreate mw-seeder || die "seed: mw-seeder did not start"
docker compose "${DC_F[@]}" wait mw-seeder >/dev/null \
  || die "seed: mw-seeder exited non-zero (docker compose logs mw-seeder)"
info "seed: mw-seeder exited 0"
manifest="${MW_SEED_MANIFEST:-$MW_ROOT/build/seed/manifest.json}"
mkdir -p "$(dirname "$manifest")"
docker compose "${DC_F[@]}" cp mw-seeder:/run/mw/seed/manifest.json "$manifest" \
  || die "seed: cannot copy the manifest out of mw-seeder"
info "seed: manifest at ${manifest#"$MW_ROOT"/}"
