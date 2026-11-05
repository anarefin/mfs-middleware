#!/usr/bin/env bash
# Lints the root contracts/ folder: every JSON Schema compiles (draft 2020-12),
# every sample payload validates (valid*) or is rejected (invalid*), and
# OWNERS.yml names an owner for every contract file. See scripts/contracts-lint/lint.mjs.
# Then checks that the body schemas the services derive from their contracts still match them
# (scripts/derived-schemas.sh --check).
#
# Needs Node (>= 22). Dependencies are pinned in scripts/contracts-lint/package-lock.json
# and installed locally (npm ci) on first use; no global installs.
set -eu
. "$(dirname "$0")/lib/modules.sh"

dir="$MW_ROOT/contracts"
tool="$(cd "$(dirname "$0")/contracts-lint" && pwd)"

if [ ! -d "$dir" ] || [ -z "$(find "$dir" -type f ! -name '.gitkeep' ! -name 'README.md' | head -1)" ]; then
  info "contracts-lint: no contract files yet (nothing to lint)"
  exit 0
fi

command -v node >/dev/null 2>&1 || die "contracts-lint: node (>= 22) is required"
if [ ! -f "$tool/node_modules/.package-lock.json" ] || [ "$tool/package-lock.json" -nt "$tool/node_modules/.package-lock.json" ]; then
  info "contracts-lint: installing pinned linter dependencies (npm ci)"
  (cd "$tool" && npm ci --silent --no-audit --no-fund) || die "contracts-lint: npm ci failed in $tool"
fi

node "$tool/lint.mjs" "$dir" "$MW_ROOT"
# the services' derived body schemas (services/*/src/main/resources/derived-schemas)
exec "$(dirname "$0")/derived-schemas.sh" --check
