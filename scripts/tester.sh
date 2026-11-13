#!/usr/bin/env bash
# Starts the test console (ui/mw-test-console, ADR D17; dev/test only) on the running stack and
# prints its URL. Start and seed the stack first (deploy/RUNNING.html), then scripts/tester.sh.
#   1. re-runs apisix-init with MW_E2E_ROUTES=1: the test-only routes (core-mock, simulators, mail,
#      the merchant_demo consumer) and the console's own route /tester (routes-test/test-console.json);
#      a later `docker compose up` re-runs apisix-init without them (run scripts/tester.sh once more);
#   2. docker compose --profile core --profile tester up -d --wait mw-test-console (never builds:
#      `docker compose --profile tester build mw-test-console` in deploy/ builds mfs-mw/mw-test-console:local).
# Memory: about 192 MiB on top of the core profile (the tester profile is outside scripts/compose-check.sh).
set -eu
. "$(dirname "$0")/lib/common.sh"

[ -f "$STACK_FILE" ] || die "tester: deploy/docker-compose.yml does not exist"
stack_has_service mw-test-console || die "tester: deploy/docker-compose.yml has no mw-test-console service"
ensure_env >/dev/null
stack_compose core tester
set -a; . "$STACK_ENV"; set +a

info "tester: enabling the test-only gateway routes (apisix-init with MW_E2E_ROUTES=1)"
MW_E2E_ROUTES=1 docker compose "${DC_F[@]}" up -d --no-deps --force-recreate apisix-init >/dev/null
docker compose "${DC_F[@]}" wait apisix-init >/dev/null \
  || die "tester: apisix-init failed to apply the test routes (docker compose logs apisix-init); is the stack up?"

info "tester: docker compose --profile tester up -d --wait mw-test-console"
docker compose "${DC_F[@]}" up -d --no-deps --wait --wait-timeout "${MW_UP_TIMEOUT:-120}" mw-test-console \
  || die "tester: mw-test-console did not come up healthy (docker compose --profile tester build mw-test-console; docker compose logs mw-test-console)"
port="${MW_HTTPS_PORT:-}"
info "tester: open https://localhost${port:+:$port}/tester (ops console: https://localhost${port:+:$port}/console); guide: docs/testing-guide.html"
