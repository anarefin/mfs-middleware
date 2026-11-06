#!/usr/bin/env bash
# Runs the admin UI Playwright suite (ui/mw-admin-ui, ticket 59) against the running, seeded stack:
# `make up && make seed`, then `make ui-e2e [GREP=@approvals]`.
#   - pnpm install --frozen-lockfile when node_modules is missing or older than pnpm-lock.yaml;
#   - pnpm exec playwright install chromium (a no-op once the browser is there);
#   - pnpm test:e2e [--grep GREP], with E2E_BASE_URL (https://localhost[:MW_HTTPS_PORT]),
#     E2E_SEED_MANIFEST (build/seed/manifest.json, MW_SEED_MANIFEST overrides), E2E_COMPOSE_PROJECT
#     and the three test credentials the suite needs, read from the generated deploy/.env (the ops
#     personas' password and TOTP secret, the robi USSD gateway secret); nothing else from it.
# Never starts, stops or changes the stack; the suite's global setup checks it and names the fix.
set -eu
. "$(dirname "$0")/lib/modules.sh"

grep_arg="${1:-}"
ui="$MW_ROOT/ui/mw-admin-ui"
[ -f "$ui/package.json" ] || die "ui-e2e: ui/mw-admin-ui does not exist"
command -v pnpm >/dev/null 2>&1 || die "ui-e2e: pnpm not found (Node.js 24 + pnpm, see README prerequisites)"
[ -f "$STACK_ENV" ] || die "ui-e2e: deploy/.env does not exist: start and seed the stack first (make up && make seed)"

# one value of deploy/.env (KEY=value lines, as scripts/gen-dev-env.sh writes them)
env_value() { sed -n "s/^$1=//p" "$STACK_ENV" | tail -1; }
for v in KC_OPS_TEST_PASSWORD KC_OPS_TEST_TOTP_SECRET USSD_ROBI_GATEWAY_SECRET; do
  val="$(env_value "$v")"
  [ -n "$val" ] || die "ui-e2e: $v is not in deploy/.env (make up generates it)"
  export "$v=$val"
done

port="${MW_HTTPS_PORT:-}"
export E2E_BASE_URL="${E2E_BASE_URL:-https://localhost${port:+:$port}}"
export E2E_SEED_MANIFEST="${MW_SEED_MANIFEST:-$MW_ROOT/build/seed/manifest.json}"
export E2E_COMPOSE_PROJECT="$(stack_project)"

cd "$ui"
if [ ! -f node_modules/.modules.yaml ] || [ pnpm-lock.yaml -nt node_modules/.modules.yaml ]; then
  info "ui-e2e: pnpm install --frozen-lockfile"
  pnpm install --frozen-lockfile
fi
pnpm exec playwright install chromium
info "ui-e2e: pnpm test:e2e${grep_arg:+ --grep $grep_arg} against $E2E_BASE_URL"
if [ -n "$grep_arg" ]; then
  pnpm test:e2e --grep "$grep_arg"
else
  pnpm test:e2e
fi
