#!/usr/bin/env bash
# Wipes ALL state of the stack together, every profile (BitsPay's deploy/reset.sh). PostgreSQL, Kafka,
# Valkey, OpenBao, Keycloak and the core-mock ledger describe the same books, so never reset one
# store alone. Runnable from any directory. Compose needs deploy/.env even to tear down, so it is
# generated first if missing; once the volumes are gone the tester logins switch to their documented
# fixed values (gen-dev-env.sh --reset-fixed, ADR D9). The next `up` starts fresh (the seeder re-seeds).
# MW_ROOT (default: the repo holding this file) points the script at another checkout (tests).
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
root="${MW_ROOT:-$(dirname "$here")}"
cd "$root/deploy"
MW_ROOT="$root" "$here/../scripts/gen-dev-env.sh" >/dev/null
docker compose -f "$root/deploy/docker-compose.yml" --env-file "$root/deploy/.env" \
  --profile minimal --profile core --profile seed --profile tester down -v --remove-orphans
MW_ROOT="$root" "$here/../scripts/gen-dev-env.sh" --reset-fixed
