#!/bin/sh
# One-shot, idempotent provisioning for mw-core-mock (dev/MVP stack; nothing else creates
# per-service database roles or OpenBao identities yet):
#   1. OpenBao KV secret/mw-core-mock: database password and the PIN key pair (RSA-2048, created
#      once; an existing secret is never overwritten, so channels keep working across runs).
#   2. PostgreSQL: login role coremock_rw and schema coremock owned by it (password synced from
#      the secret on every run).
#   3. OpenBao policy + AppRole mw-core-mock (read that one secret); a fresh secret-id and the
#      role-id are written to /out for the service (Spring configtree: one file per property).
# DEV ONLY: the OpenBao root token is read from the openbao-data volume (see dev-unseal.sh).
set -eu

SVC=mw-core-mock
ROLE=coremock_rw
SCHEMA=coremock
KV="secret/$SVC"
KEY_ID="${PIN_KEY_ID:-core-pin-dev-1}"
OUT="${OUT_DIR:-/out}"
: "${PGHOST:?}" "${PGUSER:?}" "${PGDATABASE:?}" "${PGPASSWORD:?}" "${BAO_ADDR:?}"

log() { printf 'mw-core-mock-init: %s\n' "$*"; }

if [ -z "${BAO_TOKEN:-}" ]; then
  INIT_FILE="${INIT_FILE:?BAO_TOKEN or INIT_FILE is required}"
  BAO_TOKEN="$(sed -n 's/.*"root_token": *"\([^"]*\)".*/\1/p' "$INIT_FILE" | head -1)"
fi
[ -n "$BAO_TOKEN" ] || { log "no OpenBao token" >&2; exit 1; }
export BAO_TOKEN BAO_ADDR

wait_for() { # description, command...
  desc="$1"; shift
  i=0
  until "$@" >/dev/null 2>&1; do
    i=$((i + 1)); [ "$i" -lt 60 ] || { log "timed out waiting for $desc" >&2; exit 1; }
    sleep 2
  done
}
wait_for postgres pg_isready
wait_for "openbao (unsealed)" bao status

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# 1. secret -------------------------------------------------------------------------------------
if bao kv get -field=mw.coremock.pin.private-key "$KV" >/dev/null 2>&1 \
   && bao kv get -field=spring.datasource.password "$KV" >/dev/null 2>&1; then
  log "secret $KV exists, keeping it"
else
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp/private.pem" 2>/dev/null
  openssl pkey -in "$tmp/private.pem" -pubout -out "$tmp/public.pem"
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40 >"$tmp/dbpw"
  bao kv put "$KV" \
    "spring.datasource.password=@$tmp/dbpw" \
    "mw.coremock.pin.private-key=@$tmp/private.pem" \
    "mw.coremock.pin.public-key=@$tmp/public.pem" \
    "mw.coremock.pin.key-id=$KEY_ID" >/dev/null
  log "created $KV (database password, PIN key pair $KEY_ID)"
fi
bao kv get -field=spring.datasource.password "$KV" >"$tmp/dbpw"

# 2. database role and schema ---------------------------------------------------------------------
psql -v ON_ERROR_STOP=1 -q -v pw="$(cat "$tmp/dbpw")" -v role="$ROLE" -v schema="$SCHEMA" -v db="$PGDATABASE" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'role') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'role') \gexec
ALTER ROLE :"role" PASSWORD :'pw';
CREATE SCHEMA IF NOT EXISTS :"schema" AUTHORIZATION :"role";
ALTER ROLE :"role" IN DATABASE :"db" SET search_path = :"schema";
REVOKE ALL ON DATABASE :"db" FROM :"role";
GRANT CONNECT ON DATABASE :"db" TO :"role";
SQL
log "role $ROLE and schema $SCHEMA ready"

# 3. AppRole --------------------------------------------------------------------------------------
bao policy write "$SVC" - >/dev/null <<EOF
path "secret/data/$SVC" { capabilities = ["read"] }
EOF
bao write "auth/approle/role/$SVC" token_policies="$SVC" token_ttl=1h token_max_ttl=24h secret_id_ttl=0 >/dev/null
mkdir -p "$OUT"
bao read -field=role_id "auth/approle/role/$SVC/role-id" >"$OUT/spring.cloud.vault.app-role.role-id"
bao write -f -field=secret_id "auth/approle/role/$SVC/secret-id" >"$OUT/spring.cloud.vault.app-role.secret-id"
chown 10001:10001 "$OUT"/spring.cloud.vault.app-role.* 2>/dev/null || true
chmod 0400 "$OUT"/spring.cloud.vault.app-role.*
log "AppRole $SVC ready"
