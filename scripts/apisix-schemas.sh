#!/usr/bin/env bash
# APISIX request-validation schemas (deploy/apisix/schemas/*.json) and the derived schemas a
# service checks itself (services/*/src/main/resources/derived-schemas/*.json) derived from the OpenAPI
# contracts (spec 8.3, ticket 13). See scripts/contracts-lint/apisix-schemas.mjs for the rules.
# Usage: scripts/apisix-schemas.sh --check   fail when a schema differs from its contract
#        scripts/apisix-schemas.sh --write   rewrite the schemas from their contracts
# `make contracts-lint` (and so `make verify`) runs --check.
set -eu
. "$(dirname "$0")/lib/modules.sh"

mode="${1:---check}"
tool="$(cd "$(dirname "$0")/contracts-lint" && pwd)"
[ -d "$MW_ROOT/deploy/apisix/schemas" ] || exit 0

command -v node >/dev/null 2>&1 || die "apisix-schemas: node (>= 22) is required"
if [ ! -f "$tool/node_modules/.package-lock.json" ] || [ "$tool/package-lock.json" -nt "$tool/node_modules/.package-lock.json" ]; then
  info "apisix-schemas: installing pinned dependencies (npm ci)"
  (cd "$tool" && npm ci --silent --no-audit --no-fund) || die "apisix-schemas: npm ci failed in $tool"
fi
exec node "$tool/apisix-schemas.mjs" "$mode" "$MW_ROOT"
