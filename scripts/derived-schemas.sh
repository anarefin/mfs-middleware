#!/usr/bin/env bash
# Body schemas a service checks itself (services/*/src/main/resources/derived-schemas/*.json),
# derived from the OpenAPI contracts (spec 8.3; body schemas are validated in the service, never
# at the APISIX edge: M3 gate decision). See scripts/contracts-lint/derived-schemas.mjs for the rules.
# Usage: scripts/derived-schemas.sh --check   fail when a schema differs from its contract
#        scripts/derived-schemas.sh --write   rewrite the schemas from their contracts
# scripts/contracts-lint.sh (./gradlew :contractsLint, part of ./gradlew build) runs --check.
set -eu
. "$(dirname "$0")/lib/common.sh"

mode="${1:---check}"
tool="$(cd "$(dirname "$0")/contracts-lint" && pwd)"
[ -n "$(find "$MW_ROOT/services" -path '*/src/main/resources/derived-schemas/*.json' 2>/dev/null | head -1)" ] || exit 0

command -v node >/dev/null 2>&1 || die "derived-schemas: node (>= 22) is required"
if [ ! -f "$tool/node_modules/.package-lock.json" ] || [ "$tool/package-lock.json" -nt "$tool/node_modules/.package-lock.json" ]; then
  info "derived-schemas: installing pinned dependencies (npm ci)"
  (cd "$tool" && npm ci --silent --no-audit --no-fund) || die "derived-schemas: npm ci failed in $tool"
fi
exec node "$tool/derived-schemas.mjs" "$mode" "$MW_ROOT"
