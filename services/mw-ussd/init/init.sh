#!/bin/sh
# One-shot, idempotent provisioning for mw-ussd (dev/MVP stack; nothing else creates
# per-service database roles or OpenBao identities yet):
#   1. OpenBao KV secret/mw-ussd: database password (created once; an existing secret is never
#      overwritten), the Valkey password (synced from VALKEY_PASSWORD on every run) and the core's
#      PIN public key + key id (copied on every run from what mw-core-mock exported, playing the core
#      team handing over its public key; a public key, but delivered like a secret, M11).
#   2. PostgreSQL: login role ussd_rw and schema ussd owned by it (password synced from the secret
#      on every run).
#   3. OpenBao policy + AppRole mw-ussd (read that one secret); a fresh secret-id and the role-id
#      are written to /out for the service (Spring configtree: one file per property).
# DEV ONLY: the OpenBao root token is read from the openbao-data volume (see dev-unseal.sh).
set -eu

SVC=mw-ussd
ROLE=ussd_rw
SCHEMA=ussd
KV="secret/$SVC"
CORE_KV="${CORE_KV:-secret/mw-core-mock}"
OUT="${OUT_DIR:-/out}"
: "${PGHOST:?}" "${PGUSER:?}" "${PGDATABASE:?}" "${PGPASSWORD:?}" "${BAO_ADDR:?}"

log() { printf 'mw-ussd-init: %s\n' "$*"; }

if [ -z "${BAO_TOKEN:-}" ]; then
  INIT_FILE="${INIT_FILE:?BAO_TOKEN or INIT_FILE is required}"
  # On a fresh stack OpenBao writes this file on its first start, possibly after this step began.
  i=0
  until BAO_TOKEN="$(sed -n 's/.*"root_token": *"\([^"]*\)".*/\1/p' "$INIT_FILE" 2>/dev/null | head -1)" &&
    [ -n "$BAO_TOKEN" ]; do
    i=$((i + 1)); [ "$i" -lt 60 ] || break
    sleep 2
  done
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
if bao kv get -field=spring.datasource.password "$KV" >/dev/null 2>&1; then
  log "secret $KV exists, keeping it"
else
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40 >"$tmp/dbpw"
  bao kv put "$KV" "spring.datasource.password=@$tmp/dbpw" >/dev/null
  log "created $KV (database password)"
fi
bao kv get -field=spring.datasource.password "$KV" >"$tmp/dbpw"

# 1b. Valkey password (USSD sessions) -------------------------------------------------------------
if [ -n "${VALKEY_PASSWORD:-}" ]; then
  printf '%s' "$VALKEY_PASSWORD" >"$tmp/valkey"
  bao kv patch "$KV" "spring.data.redis.password=@$tmp/valkey" >/dev/null
  rm -f "$tmp/valkey"
  log "Valkey password spring.data.redis.password synced"
else
  log "VALKEY_PASSWORD not set: spring.data.redis.password left as it is" >&2
fi

# 1c. the core's PIN public key (mw-core-mock-init creates the pair on a fresh stack) -------------
wait_for "the core PIN key ($CORE_KV)" bao kv get -field=mw.coremock.pin.public-key "$CORE_KV"
bao kv get -field=mw.coremock.pin.public-key "$CORE_KV" >"$tmp/pinpub"
printf '%s' "$(bao kv get -field=mw.coremock.pin.key-id "$CORE_KV")" >"$tmp/pinkid"
bao kv patch "$KV" "mw.ussd.pin.public-key=@$tmp/pinpub" "mw.ussd.pin.key-id=@$tmp/pinkid" >/dev/null
log "core PIN public key $(cat "$tmp/pinkid") synced"

# 2. database role and schema ---------------------------------------------------------------------
provision_db() {
  psql -v ON_ERROR_STOP=1 -q -v pw="$(cat "$tmp/dbpw")" -v role="$ROLE" -v schema="$SCHEMA" -v db="$PGDATABASE" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'role') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'role') \gexec
ALTER ROLE :"role" PASSWORD :'pw';
CREATE SCHEMA IF NOT EXISTS :"schema" AUTHORIZATION :"role";
ALTER ROLE :"role" IN DATABASE :"db" SET search_path = :"schema";
REVOKE ALL ON DATABASE :"db" FROM :"role";
GRANT CONNECT ON DATABASE :"db" TO :"role";
SQL
}
# The other services' init steps change the same database ACL at the same time on a fresh
# stack ("tuple concurrently updated"): the statements are idempotent, so try again.
n=0
until provision_db; do
  n=$((n + 1)); [ "$n" -lt 5 ] || { log "database provisioning failed" >&2; exit 1; }
  log "database provisioning raced another init step, retrying"; sleep 2
done
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
