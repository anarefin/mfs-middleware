#!/usr/bin/env bash
# Runs the Newman API suite (ticket 58) against the running stack, started with the test timers
# and the seed profile (from deploy/: docker compose
# --profile core --profile seed up -d --wait).
#
#   tests/api/run.sh                         every folder except "90 Slow" and "99 Destructive"
#   tests/api/run.sh --folder "01 P2P ..."   one folder by its full name or its number ("01");
#                                            repeat --folder for several
#   tests/api/run.sh --all                   every folder, the slow ones included
#
# Reads the dev credentials from deploy/.env (a copy of deploy/dev.env.defaults), copies mw-seeder's
# manifest out of its container to build/seed/manifest.json and re-applies the test-only APISIX
# routes once (apisix-init with MW_E2E_ROUTES=1, as the suite always needs; MW_API_SKIP_ROUTES=1
# skips both), then runs a pinned Newman with npx (no --delay-request: Newman 6
# refuses 0, and its default is no delay). Needs Node/npx and access to
# cdnjs.cloudflare.com (node-forge and elliptic, pinned by SHA-256 in the collection). The run
# state goes to tests/api/.local.postman_environment.json (gitignored) with every secret blanked;
# the JUnit report to tests/api/build/newman-report.xml. Knobs: MW_API_BASE_URL (default
# https://localhost:${MW_HTTPS_PORT:-443}), NEWMAN_VERSION, MW_SEED_MANIFEST (default
# build/seed/manifest.json, copied out of mw-seeder), MW_API_RUN_LEDGER (default
# build/api-test/run-customers.tsv: which run customer each run played customer01 as, run_customer.mjs).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
collection="$here/mfs-mw.postman_collection.json"
state="$here/.local.postman_environment.json"
newman="newman@${NEWMAN_VERSION:-6.2.1}"

folders=()
all=0
while [ $# -gt 0 ]; do
  case "$1" in
    --folder) [ -n "${2:-}" ] && folders+=("$2"); shift 2 ;;
    --all) all=1; shift ;;
    *) echo "run.sh: unknown option: $1" >&2; exit 2 ;;
  esac
done

# Folder names from the collection: every folder (minus 90/99 unless --all), or the ones asked for
# (a two-digit prefix selects the folder with that number).
names="$(node -e '
  const c = require(process.argv[1]); const all = process.argv[2] === "1"; const want = process.argv.slice(3);
  const names = c.item.map(i => i.name);
  const out = want.length ? want.map(w => names.find(n => n === w || n.startsWith(w + " ")) || ("?" + w))
                          : names.filter(n => all || !/^9[0-9] /.test(n));
  console.log(out.join("\n"));' "$collection" "$all" ${folders[@]+"${folders[@]}"})"
if grep -q '^?' <<<"$names"; then
  echo "run.sh: no such folder: $(grep '^?' <<<"$names" | cut -c2- | paste -sd, -)" >&2
  exit 2
fi

env_file="$root/deploy/.env"
[ -f "$env_file" ] || { echo "run.sh: $env_file is missing: run `cd deploy && cp dev.env.defaults .env` and start the stack first" >&2; exit 1; }
env_value() { { grep -E "^$1=" "$env_file" || true; } | tail -1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//'; }
port="$(env_value MW_HTTPS_PORT)"
base_url="${MW_API_BASE_URL:-https://localhost:${port:-${MW_HTTPS_PORT:-443}}}"
secrets=(
  "e2eApiKey=$(env_value MW_E2E_API_KEY)"
  "kcCustomersPassword=$(env_value KC_CUSTOMERS_TEST_PASSWORD)"
  "kcPortalsPassword=$(env_value KC_PORTALS_TEST_PASSWORD)"
  "kcOpsPassword=$(env_value KC_OPS_TEST_PASSWORD)"
  "kcOpsTotpSecret=$(env_value KC_OPS_TEST_TOTP_SECRET)"
  "billaggSecret=$(env_value BILLAGG_HMAC_SECRET)"
  "checkoutSecret=$(env_value CHECKOUT_DEMO_MERCHANT_SECRET)"
)
for s in "${secrets[@]}"; do
  [ -n "${s#*=}" ] || { echo "run.sh: ${s%%=*} has no value in deploy/.env (see deploy/dev.env.defaults)" >&2; exit 1; }
done

if [ "${MW_API_SKIP_ROUTES:-0}" != 1 ]; then
  # shellcheck source=../../scripts/lib/common.sh
  . "$root/scripts/lib/common.sh"
  stack_seed_manifest
  stack_compose
  info "api-test: enabling the test-only gateway routes (apisix-init with MW_E2E_ROUTES=1)"
  set -a; . "$STACK_ENV"; set +a
  MW_E2E_ROUTES=1 docker compose "${DC_F[@]}" up -d --no-deps --force-recreate apisix-init >/dev/null
  docker compose "${DC_F[@]}" wait apisix-init >/dev/null \
    || { echo "run.sh: apisix-init failed to apply the test routes (docker compose logs apisix-init); is the stack up?" >&2; exit 1; }
fi

[ -f "$state" ] || cp "$here/local.postman_environment.json" "$state"
args=()
while IFS= read -r f; do args+=(--folder "$f"); done <<<"$names"
# The seeded baseline (mw-seeder, ticket 57): partner ocs's time limit, which the test timers of deploy/dev.env.defaults shorten;
# the scenarios size their partner timeouts from it.
manifest="${MW_SEED_MANIFEST:-$root/build/seed/manifest.json}"
[ -f "$manifest" ] || { echo "run.sh: no seed manifest at $manifest: start the stack with --profile seed (mw-seeder must exit 0) first" >&2; exit 1; }
ocs_timeout="$(node -e 'const m = require(process.argv[1]); const p = (m.partners || []).find(x => x.partnerId === "ocs"); console.log(p ? p.timeoutMs : "")' "$manifest")"
[ -n "$ocs_timeout" ] || { echo "run.sh: the seed manifest names no partner ocs" >&2; exit 1; }
# The run customer (mw-seeder pool) this run plays customer01 as, so repeated runs on one day never
# reach a wallet's daily debit limit (run_customer.mjs; picks recorded in build/api-test/).
read -r run_user run_msisdn < <(node "$here/run_customer.mjs" "$manifest" "${MW_API_RUN_LEDGER:-$root/build/api-test/run-customers.tsv}") \
  || { echo "run.sh: no run customer for this run (see above)" >&2; exit 1; }
[ -n "${run_msisdn:-}" ] || { echo "run.sh: no run customer for this run" >&2; exit 1; }
echo "==> api-test: customer01 plays as run customer $run_user (${run_msisdn:0:4}****${run_msisdn: -3})"
env_args=(--env-var "baseUrl=$base_url" --env-var "ocsTimeoutMs=$ocs_timeout"
  --env-var "runCustomerUser=$run_user" --env-var "runCustomerMsisdn=$run_msisdn")
for s in "${secrets[@]}"; do env_args+=(--env-var "$s"); done
mkdir -p "$here/build"

status=0
npx --yes "$newman" run "$collection" -e "$state" "${args[@]}" "${env_args[@]}" \
  --insecure --ignore-redirects --timeout-script 900000 --timeout-request 60000 \
  --export-environment "$state" \
  --reporters cli,junit --reporter-junit-export "$here/build/newman-report.xml" || status=$?

# Keep the run state, never the secrets.
node -e '
  const fs = require("fs"); const p = process.argv[1]; const env = JSON.parse(fs.readFileSync(p, "utf8"));
  const secret = new Set(["e2eApiKey","kcCustomersPassword","kcPortalsPassword","kcOpsPassword","kcOpsTotpSecret","billaggSecret","checkoutSecret"]);
  for (const v of env.values) if (secret.has(v.key)) v.value = "";
  fs.writeFileSync(p, JSON.stringify(env, null, 2) + "\n");' "$state"
exit $status
