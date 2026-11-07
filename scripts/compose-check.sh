#!/usr/bin/env bash
# Memory budget of the stack (make compose-check; also run by make verify / verify-changed): with
# every service (--profile core) of deploy/docker-compose.yml, the memory limits of all
# long-running services (no mw.oneshot=true label) add up to <= MW_RAM_BUDGET_MIB (default 8192: the laptop demo footprint),
# and each of them has a limit. Needs the docker compose CLI (no daemon) and node (>= 22).
set -eu
. "$(dirname "$0")/lib/modules.sh"

budget="${MW_RAM_BUDGET_MIB:-8192}"
[ -f "$STACK_FILE" ] || { info "compose-check: no deploy/docker-compose.yml, nothing to check"; exit 0; }
docker compose version >/dev/null 2>&1 || die "compose-check: the docker compose CLI is required"
command -v node >/dev/null 2>&1 || die "compose-check: node (>= 22) is required"

tmp="$(mktemp -d "${TMPDIR:-/tmp}/compose-check.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT
# --no-interpolate: the check needs no generated secret
docker compose -f "$STACK_FILE" --profile core config --no-interpolate --format json >"$tmp/model.json" 2>"$tmp/err" \
  || { cat "$tmp/err" >&2; die "compose-check: docker compose config failed"; }

node - "$tmp/model.json" "$budget" <<'EOF'
const fs = require('node:fs');
const [file, budget] = process.argv.slice(2);
const model = JSON.parse(fs.readFileSync(file, 'utf8'));
const kv = (x) => Array.isArray(x)
  ? Object.fromEntries(x.map((i) => { const k = i.indexOf('='); return k < 0 ? [i, ''] : [i.slice(0, k), i.slice(k + 1)]; }))
  : (x || {});
const mib = (v) => {
  if (typeof v === 'number') return v / 1048576;
  const m = /^\s*([0-9.]+)\s*([kmg]?)b?\s*$/i.exec(String(v));
  if (!m) return NaN;
  return Number(m[1]) * { '': 1 / 1048576, k: 1 / 1024, m: 1, g: 1024 }[m[2].toLowerCase()];
};
let total = 0; const missing = []; const rows = [];
for (const [name, svc] of Object.entries(model.services || {}).sort()) {
  if (kv(svc.labels)['mw.oneshot'] === 'true') continue;
  const lim = svc.deploy?.resources?.limits?.memory;
  const m = lim === undefined ? NaN : mib(lim);
  if (Number.isNaN(m)) { missing.push(name); continue; }
  total += m; rows.push(`${name}=${Math.round(m)}`);
}
if (missing.length) {
  console.error(`error: compose-check: long-running service(s) without a memory limit: ${missing.join(' ')}`);
  process.exit(1);
}
if (total > Number(budget)) {
  console.error(`error: compose-check: memory limits of long-running services add up to ${Math.round(total)} MiB, over the ${budget} MiB budget (${rows.join(' ')})`);
  process.exit(1);
}
console.log(`==> compose-check: long-running memory limits ${Math.round(total)} MiB of ${budget} MiB`);
EOF
