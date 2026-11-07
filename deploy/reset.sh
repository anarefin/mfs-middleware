#!/usr/bin/env bash
# Wipes ALL state of the stack together, every profile (deploy/RUNNING.html, step 11; BitsPay's
# deploy/reset.sh). PostgreSQL, Kafka, Valkey, OpenBao, Keycloak and the core-mock ledger describe
# the same books, so never reset one store alone. Same as `make reset`: docker compose down -v
# --remove-orphans for every profile. Start again with RUNNING.html step 4 (the seeder re-seeds).
set -eu
exec "$(cd "$(dirname "$0")" && pwd)/../scripts/down.sh" --reset
