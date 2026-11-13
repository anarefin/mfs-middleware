#!/usr/bin/env bash
# Wipes ALL state of the stack together, every profile (BitsPay's deploy/reset.sh). PostgreSQL, Kafka,
# Valkey, OpenBao, Keycloak and the core-mock ledger describe the same books, so never reset one
# store alone. Runnable from any directory. Compose needs deploy/.env even to tear down, so it is
# copied from deploy/dev.env.defaults first if missing (ADR D9). The next `up` starts fresh (the
# seeder re-seeds).
# MW_ROOT (default: the repo holding this file) points the script at another checkout (tests).
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
root="${MW_ROOT:-$(dirname "$here")}"
cd "$root/deploy"
[ -f .env ] || cp dev.env.defaults .env
docker compose -f "$root/deploy/docker-compose.yml" \
  --profile minimal --profile core --profile seed --profile tester down -v --remove-orphans
