#!/usr/bin/env bash
# Runs the test console's Playwright suite (ui/mw-test-console/e2e) against the console on the
# running, seeded stack: `make up TIMERS=fast && make seed && make tester`, then
# `make tester-e2e [GREP=@x]`. Each spec drives one page the way the guide does (app flows, USSD over
# HTTP and SMPP, a lost core reply resolved by TSQ, checkout with IPN, a bulk batch) and checks the
# middleware's answer. Installs with pnpm when needed; E2E_BASE_URL https://localhost[:MW_HTTPS_PORT].
set -eu
. "$(dirname "$0")/lib/modules.sh"

grep_arg="${1:-}"
ui="$MW_ROOT/ui/mw-test-console"
[ -f "$ui/package.json" ] || die "tester-e2e: ui/mw-test-console does not exist"
command -v pnpm >/dev/null 2>&1 || die "tester-e2e: pnpm not found (Node.js 24 + pnpm, see README prerequisites)"

port="${MW_HTTPS_PORT:-}"
export E2E_BASE_URL="${E2E_BASE_URL:-https://localhost${port:+:$port}}"
cd "$ui"
if [ ! -f node_modules/.modules.yaml ] || [ pnpm-lock.yaml -nt node_modules/.modules.yaml ]; then
  info "tester-e2e: pnpm install --frozen-lockfile"
  pnpm install --frozen-lockfile
fi
pnpm exec playwright install chromium
info "tester-e2e: pnpm test:e2e${grep_arg:+ --grep $grep_arg} against $E2E_BASE_URL/tester"
if [ -n "$grep_arg" ]; then
  pnpm test:e2e --grep "$grep_arg"
else
  pnpm test:e2e
fi
