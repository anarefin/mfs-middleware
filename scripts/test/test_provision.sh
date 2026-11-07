# deploy/provision (the compose one-shot `provision`, the one dev provisioning one-shot that replaced the 13 mw-*-init
# containers): every service of the central manifest gets its OpenBao secret, database role/schema and
# AppRole, in manifest order, idempotently. bao, psql and openssl are stubbed: the stub bao keeps a KV
# store under .kv/<path>/<field> and logs every call.

PROVISION_DIR="$REPO_DIR/deploy/provision"
# the generated .env values the compose block passes in (obviously fake)
PROVISION_ENV="VALKEY_PASSWORD=valkey-t BILLAGG_HMAC_SECRET=billagg-t C2WBANK_HMAC_SECRET=c2w-t
  CHECKOUT_DEMO_MERCHANT_SECRET=merchant-t KC_MW_ADMIN_API_SECRET=admin-t KC_MW_PORTAL_API_SECRET=portal-t
  USSD_GP_GATEWAY_SECRET=gp-t USSD_ROBI_GATEWAY_SECRET=robi-t USSD_BL_GATEWAY_SECRET=bl-t
  USSD_TELETALK_GATEWAY_SECRET=teletalk-t"

provision_fixture() {
  mkdir -p .bin .kv out
  printf '{"root_token": "root-t"}\n' >dev-init.json
  cat >.bin/bao <<'EOF'
#!/bin/sh
# stub OpenBao CLI: kv get/put/patch on files, policy/approle calls only logged
root="$(cd "$(dirname "$0")/.." && pwd)"
echo "bao $*" >>"$root/.bao-calls"
[ "${BAO_TOKEN:-}" = root-t ] || { echo "no token" >&2; exit 2; }
field=""
case "$1 $2" in
  "kv get")
    shift 2
    case "$1" in -field=*) field="${1#-field=}"; shift ;; esac
    d="$root/.kv/$1"
    [ -d "$d" ] || exit 2
    [ -z "$field" ] && exit 0
    [ -f "$d/$field" ] || exit 2
    cat "$d/$field"; exit 0 ;;
  "kv put"|"kv patch")
    op="$2"; shift 2; d="$root/.kv/$1"; shift
    [ "$op" = patch ] && { [ -d "$d" ] || { echo "patch of a missing secret" >&2; exit 2; }; }
    [ "$op" = put ] && rm -rf "$d"
    mkdir -p "$d"
    for kvp in "$@"; do
      k="${kvp%%=*}"; v="${kvp#*=}"
      case "$v" in @*) cp "${v#@}" "$d/$k" ;; *) printf '%s' "$v" >"$d/$k" ;; esac
    done
    exit 0 ;;
  "policy write") cat >/dev/null; exit 0 ;;
esac
case "$*" in
  "read -field=role_id "*) printf 'role-id-of-%s' "$(echo "$3" | cut -d/ -f4)" ;;
  "write -f -field=secret_id "*) printf 'secret-id-%s' "$$" ;;
esac
exit 0
EOF
  cat >.bin/psql <<'EOF'
#!/bin/sh
root="$(cd "$(dirname "$0")/.." && pwd)"
{ echo "psql $*"; cat; } >>"$root/.psql-calls"
EOF
  cat >.bin/openssl <<'EOF'
#!/bin/sh
root="$(cd "$(dirname "$0")/.." && pwd)"
echo "openssl $*" >>"$root/.openssl-calls"
out=""; while [ $# -gt 0 ]; do [ "$1" = -out ] && out="$2"; shift; done
case " $(tail -1 "$root/.openssl-calls") " in
  *" -pubout "*) printf 'PUBLIC-PEM' >"$out" ;;
  *) printf 'PRIVATE-PEM' >"$out" ;;
esac
EOF
  chmod +x .bin/bao .bin/psql .bin/openssl
}

run_provision() {
  # shellcheck disable=SC2086
  OUT="$(env PATH="$PWD/.bin:$PATH" PGHOST=pg PGUSER=admin PGDATABASE=mw PGPASSWORD=pg-t \
    BAO_ADDR=http://bao:8200 INIT_FILE="$PWD/dev-init.json" OUT_ROOT="$PWD/out" $PROVISION_ENV "$@" \
    sh "$PROVISION_DIR/provision.sh" 2>&1)"
}

kv() { cat ".kv/secret/$1/$2"; }

# the services the manifest provisions, in order, as "name:out-dir"
manifest_services() { (. "$PROVISION_DIR/services.sh"; printf '%s\n' $SERVICES); }

test_every_service_with_an_approle_volume_is_in_the_manifest() {
  local s
  for s in mw-core-mock mw-simulators mw-orchestrator mw-audit-dw mw-app-gateway mw-notify mw-ussd \
    mw-admin-api mw-adapter-telco mw-adapter-billpay mw-adapter-bank mw-portal-api mw-checkout; do
    manifest_services | grep -q "^$s:" || fail "$s missing from deploy/provision/services.sh"
  done
  [ "$(manifest_services | head -1)" = mw-core-mock:core-mock ] \
    || fail "mw-core-mock is not first (ussd/checkout copy its PIN public key)"
}

test_provision_creates_every_secret_role_and_approle() {
  provision_fixture
  assert_ok run_provision
  local entry s d
  for entry in $(manifest_services); do
    s="${entry%%:*}"; d="${entry#*:}"
    [ -s ".kv/secret/$s/spring.datasource.password" ] || fail "$s: no database password"
    [ "$(wc -c <".kv/secret/$s/spring.datasource.password" | tr -d ' ')" = 40 ] || fail "$s: password not 40 chars"
    [ "$(cat "out/$d/spring.cloud.vault.app-role.role-id")" = "role-id-of-$s" ] || fail "$s: no role-id in out/$d"
    [ -s "out/$d/spring.cloud.vault.app-role.secret-id" ] || fail "$s: no secret-id"
    [ "$(stat -f %Lp "out/$d/spring.cloud.vault.app-role.role-id" 2>/dev/null || stat -c %a "out/$d/spring.cloud.vault.app-role.role-id")" = 400 ] \
      || fail "$s: approle files not 0400"
    grep -q "^bao policy write $s -$" .bao-calls || fail "$s: no policy"
    grep -q "^bao write auth/approle/role/$s token_policies=$s " .bao-calls || fail "$s: no AppRole"
  done
  assert_out_contains "provision: 13 services provisioned"
}

test_provision_writes_the_service_specific_keys() {
  provision_fixture
  assert_ok run_provision
  [ "$(kv mw-orchestrator spring.data.redis.password)" = valkey-t ] || fail "orchestrator valkey"
  [ "$(wc -c <.kv/secret/mw-orchestrator/mw.orchestrator.search.hash-key | tr -d ' ')" = 48 ] || fail "hash key"
  [ "$(kv mw-app-gateway spring.data.redis.password)" = valkey-t ] || fail "app-gateway valkey"
  [ "$(kv mw-ussd spring.data.redis.password)" = valkey-t ] || fail "ussd valkey"
  [ "$(kv mw-adapter-billpay mw.partner-secrets.billagg.hmac-secret)" = billagg-t ] || fail "billpay hmac"
  [ "$(kv mw-adapter-bank mw.partner-secrets.c2wbank.hmac-secret)" = c2w-t ] || fail "bank hmac"
  [ "$(kv mw-simulators sim.callback-secrets.billagg)" = billagg-t ] || fail "sim billagg"
  [ "$(kv mw-simulators sim.callback-secrets.c2wbank)" = c2w-t ] || fail "sim c2wbank"
  local m
  for m in gp robi bl teletalk; do
    [ "$(kv mw-simulators "sim.ussd-gateway.credentials.$m")" = "$m-t" ] || fail "sim ussd $m"
  done
  [ "$(kv mw-admin-api mw.admin-api.internal-auth.client-secret)" = admin-t ] || fail "admin-api client secret"
  [ "$(kv mw-portal-api mw.portal-api.orchestrator.auth.client-secret)" = portal-t ] || fail "portal-api client secret"
  [ "$(kv mw-checkout mw.checkout.merchants.merchant_demo.secret)" = merchant-t ] || fail "checkout merchant"
  [ "$(wc -c <.kv/secret/mw-notify/mw.notify.smpp.password | tr -d ' ')" = 8 ] || fail "SMPP password not 8 chars"
  # audit-dw: three roles, three passwords
  [ -s .kv/secret/mw-audit-dw/spring.flyway.password ] || fail "audit-dw owner password"
  [ -s .kv/secret/mw-audit-dw/mw.audit-dw.chain-verify.reader-password ] || fail "audit-dw reader password"
  grep -q 'owner=audit_dw_rw' .psql-calls && grep -q 'writer=audit_writer' .psql-calls \
    && grep -q 'reader=audit_reader' .psql-calls || fail "audit-dw roles not provisioned"
  # core-mock key pair, copied to ussd and checkout
  [ "$(kv mw-core-mock mw.coremock.pin.private-key)" = PRIVATE-PEM ] || fail "core private key"
  [ "$(kv mw-core-mock mw.coremock.pin.key-id)" = core-pin-dev-1 ] || fail "core key id"
  [ "$(kv mw-ussd mw.ussd.pin.public-key)" = PUBLIC-PEM ] || fail "ussd PIN key"
  [ "$(kv mw-ussd mw.ussd.pin.key-id)" = core-pin-dev-1 ] || fail "ussd key id"
  [ "$(kv mw-checkout mw.checkout.pin.public-key)" = PUBLIC-PEM ] || fail "checkout PIN key"
  [ "$(kv mw-checkout mw.checkout.pin.key-id)" = core-pin-dev-1 ] || fail "checkout key id"
  # one role + schema per plain service
  grep -q 'role=orchestrator_rw .*schema=txn' .psql-calls || fail "orchestrator role/schema"
  # mw-admin-api also owns schema partner (its Flyway migrates both)
  grep -q 'role=admin_api_rw .*schema=admin ' .psql-calls || fail "admin-api role/schema"
  grep -q 'role=admin_api_rw .*schema=partner ' .psql-calls || fail "admin-api second schema partner"
}

test_a_second_run_keeps_generated_secrets_and_resyncs_env_ones() {
  provision_fixture
  assert_ok run_provision
  local pw hk pk
  pw="$(kv mw-orchestrator spring.datasource.password)"
  hk="$(kv mw-orchestrator mw.orchestrator.search.hash-key)"
  pk="$(kv mw-core-mock mw.coremock.pin.private-key)"
  : >.openssl-calls
  assert_ok run_provision VALKEY_PASSWORD=valkey-new
  [ "$(kv mw-orchestrator spring.datasource.password)" = "$pw" ] || fail "database password regenerated"
  [ "$(kv mw-orchestrator mw.orchestrator.search.hash-key)" = "$hk" ] || fail "hash key regenerated"
  [ "$(kv mw-core-mock mw.coremock.pin.private-key)" = "$pk" ] || fail "PIN key regenerated"
  [ ! -s .openssl-calls ] || fail "openssl ran again: $(cat .openssl-calls)"
  [ "$(kv mw-orchestrator spring.data.redis.password)" = valkey-new ] || fail "valkey not resynced"
}

test_provision_fails_fast_without_an_openbao_token() {
  provision_fixture
  : >dev-init.json
  assert_fails run_provision
  assert_out_contains "no OpenBao token"
  [ ! -e .psql-calls ] || fail "database touched without a token"
}

test_provision_fails_when_a_required_env_secret_is_missing() {
  provision_fixture
  assert_fails run_provision VALKEY_PASSWORD=
  assert_out_contains "VALKEY_PASSWORD"
}

test_provision_never_polls_or_sleeps() {
  local f
  for f in "$PROVISION_DIR"/*.sh; do
    grep -qE 'sleep|wait_for|pg_isready|until .*bao status' "$f" && fail "$f polls or sleeps"
  done
  return 0
}
