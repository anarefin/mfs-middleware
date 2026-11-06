#!/bin/sh
# One-shot, idempotent provisioning for mw-app-gateway (dev/MVP stack; nothing else creates
# per-service database roles or OpenBao identities yet):
#   1. OpenBao KV secret/mw-app-gateway: database password (created once; an existing secret is
#      never overwritten).
#      Also the platform's Valkey password (spring.data.redis.password, synced on every run).
#   2. PostgreSQL: login role app_gateway_rw and schema app_gateway owned by it (password synced from
#      the secret on every run).
#   3. OpenBao policy + AppRole mw-app-gateway (read that one secret); a fresh secret-id and the
#      role-id are written to /out for the service (Spring configtree: one file per property).
# DEV ONLY: the OpenBao root token is read from the openbao-data volume (see dev-unseal.sh).
set -eu

SVC=mw-app-gateway
ROLE=app_gateway_rw
SCHEMA=app_gateway
KV="secret/$SVC"
OUT="${OUT_DIR:-/out}"
: "${PGHOST:?}" "${PGUSER:?}" "${PGDATABASE:?}" "${PGPASSWORD:?}" "${BAO_ADDR:?}"

log() { printf 'mw-app-gateway-init: %s\n' "$*"; }

# Compose starts this step once openbao is healthy: unsealed, engines enabled and the dev init
# file written (depends_on openbao: service_healthy), so nothing here polls.
if [ -z "${BAO_TOKEN:-}" ]; then
  INIT_FILE="${INIT_FILE:?BAO_TOKEN or INIT_FILE is required}"
  BAO_TOKEN="$(sed -n 's/.*"root_token": *"\([^"]*\)".*/\1/p' "$INIT_FILE" 2>/dev/null | head -1)"
fi
[ -n "$BAO_TOKEN" ] || { log "no OpenBao token in ${INIT_FILE:-BAO_TOKEN} (is openbao healthy?)" >&2; exit 1; }
export BAO_TOKEN BAO_ADDR

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# 1. secret -------------------------------------------------------------------------------------
if bao kv get -field=spring.datasource.password "$KV" >/dev/null 2>&1; then
  log "secret $KV exists, keeping it"
else
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40 >"$tmp/dbpw"
  bao kv put "$KV" "spring.datasource.password=@$tmp/dbpw" >/dev/null
  log "created $KV (database password)"
fi
bao kv get -field=spring.datasource.password "$KV" >"$tmp/dbpw"

# 1b. Valkey password (ticket 29: device sessions, nonce cache) ----------------------------------
# The platform's dev Valkey password (deploy/.env, scripts/gen-dev-env.sh), synced
# on every run as spring.data.redis.password, so a regenerated .env takes effect.
if [ -n "${VALKEY_PASSWORD:-}" ]; then
  printf '%s' "$VALKEY_PASSWORD" >"$tmp/valkey"
  bao kv patch "$KV" "spring.data.redis.password=@$tmp/valkey" >/dev/null
  rm -f "$tmp/valkey"
  log "Valkey password spring.data.redis.password synced"
else
  log "VALKEY_PASSWORD not set: spring.data.redis.password left as it is" >&2
fi

# 2. database role and schema ---------------------------------------------------------------------
provision_db() {
  psql -1 -v ON_ERROR_STOP=1 -q -v pw="$(cat "$tmp/dbpw")" -v role="$ROLE" -v schema="$SCHEMA" -v db="$PGDATABASE" <<'SQL'
DO $$ BEGIN PERFORM pg_advisory_xact_lock(hashtext('mw-service-init')); END $$;
SELECT format('CREATE ROLE %I LOGIN', :'role') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'role') \gexec
ALTER ROLE :"role" PASSWORD :'pw';
CREATE SCHEMA IF NOT EXISTS :"schema" AUTHORIZATION :"role";
ALTER ROLE :"role" IN DATABASE :"db" SET search_path = :"schema";
REVOKE ALL ON DATABASE :"db" FROM :"role";
GRANT CONNECT ON DATABASE :"db" TO :"role";
SQL
}
# The other services' init steps run at the same time on a fresh stack: an advisory lock
# serialises the provisioning transactions (they all change the database ACL, "tuple concurrently
# updated"), and one short retry covers anything else that races. The statements are idempotent.
provision_db || { log "database provisioning raced another init step, retrying"; sleep 1; provision_db; } ||
  { log "database provisioning failed" >&2; exit 1; }
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
