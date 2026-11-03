#!/usr/bin/env bash
# Lints the root contracts/ folder (OpenAPI, AsyncAPI, JSON Schema, OWNERS.yml).
# Placeholder until WP04 adds the schemas and the real linter: passes when there
# is nothing to lint.
set -eu
. "$(dirname "$0")/lib/modules.sh"

dir="$MW_ROOT/contracts"
count=0
if [ -d "$dir" ]; then
  count="$(find "$dir" -type f ! -name '.gitkeep' ! -name 'README.md' | grep -c . || true)"
fi
if [ "$count" -eq 0 ]; then
  info "contracts-lint: no contract files yet (nothing to lint)"
  exit 0
fi
die "contracts-lint: $count contract file(s) found but no linter is configured yet (WP04)"
