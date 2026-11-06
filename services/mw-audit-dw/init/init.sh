#!/bin/sh
# One-shot, idempotent provisioning for mw-audit-dw (dev/MVP stack; nothing else creates
# per-service database roles or OpenBao identities yet):
#   1. OpenBao KV secret/mw-audit-dw: two database passwords, created once and never overwritten:
#      spring.flyway.password (schema owner audit_dw_rw, migrations only) and
#      spring.datasource.password (audit_writer, what the service runs as; spec 14.3, 16.4), and
#      mw.audit-dw.chain-verify.reader-password (audit_reader, read-only, for the chain verify job;
#      ticket 50).
#   2. PostgreSQL: login roles audit_dw_rw, audit_writer and audit_reader, schema audit owned by
#      audit_dw_rw (passwords synced from the secret on every run). Table grants come from the
#      Flyway migrations (audit_writer: INSERT only on audit.event, V4; audit_reader: SELECT only
#      on audit.event and audit.chain_head, V5).
#   3. OpenBao policy + AppRole mw-audit-dw (read that one secret); a fresh secret-id and the
#      role-id are written to /out for the service (Spring configtree: one file per property).
# DEV ONLY: the OpenBao root token is read from the openbao-data volume (see dev-unseal.sh).
set -eu

SVC=mw-audit-dw
OWNER=audit_dw_rw
WRITER=audit_writer
READER=audit_reader
SCHEMA=audit
KV="secret/$SVC"
OUT="${OUT_DIR:-/out}"
: "${PGHOST:?}" "${PGUSER:?}" "${PGDATABASE:?}" "${PGPASSWORD:?}" "${BAO_ADDR:?}"

log() { printf 'mw-audit-dw-init: %s\n' "$*"; }

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
if bao kv get -field=spring.datasource.password "$KV" >/dev/null 2>&1 &&
  bao kv get -field=spring.flyway.password "$KV" >/dev/null 2>&1; then
  log "secret $KV exists, keeping it"
else
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40 >"$tmp/writerpw"
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40 >"$tmp/ownerpw"
  bao kv put "$KV" "spring.datasource.password=@$tmp/writerpw" "spring.flyway.password=@$tmp/ownerpw" >/dev/null
  log "created $KV (database passwords)"
fi
bao kv get -field=spring.datasource.password "$KV" >"$tmp/writerpw"
bao kv get -field=spring.flyway.password "$KV" >"$tmp/ownerpw"
# The reader password (ticket 50) is added to an existing secret once, never overwritten.
if ! bao kv get -field=mw.audit-dw.chain-verify.reader-password "$KV" >/dev/null 2>&1; then
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40 >"$tmp/readerpw"
  bao kv patch "$KV" "mw.audit-dw.chain-verify.reader-password=@$tmp/readerpw" >/dev/null
  log "added the audit_reader password to $KV"
fi
bao kv get -field=mw.audit-dw.chain-verify.reader-password "$KV" >"$tmp/readerpw"

# 2. database roles and schema --------------------------------------------------------------------
provision_db() {
  psql -1 -v ON_ERROR_STOP=1 -q \
  -v ownerpw="$(cat "$tmp/ownerpw")" -v writerpw="$(cat "$tmp/writerpw")" -v readerpw="$(cat "$tmp/readerpw")" \
  -v owner="$OWNER" -v writer="$WRITER" -v reader="$READER" -v schema="$SCHEMA" -v db="$PGDATABASE" <<'SQL'
DO $$ BEGIN PERFORM pg_advisory_xact_lock(hashtext('mw-service-init')); END $$;
SELECT format('CREATE ROLE %I LOGIN', :'owner') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'owner') \gexec
SELECT format('CREATE ROLE %I LOGIN', :'writer') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'writer') \gexec
SELECT format('CREATE ROLE %I LOGIN', :'reader') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'reader') \gexec
ALTER ROLE :"owner" LOGIN PASSWORD :'ownerpw';
ALTER ROLE :"writer" LOGIN PASSWORD :'writerpw';
ALTER ROLE :"reader" LOGIN PASSWORD :'readerpw';
CREATE SCHEMA IF NOT EXISTS :"schema" AUTHORIZATION :"owner";
ALTER ROLE :"owner" IN DATABASE :"db" SET search_path = :"schema";
ALTER ROLE :"writer" IN DATABASE :"db" SET search_path = :"schema";
ALTER ROLE :"reader" IN DATABASE :"db" SET search_path = :"schema";
REVOKE ALL ON DATABASE :"db" FROM :"owner", :"writer", :"reader";
GRANT CONNECT ON DATABASE :"db" TO :"owner", :"writer", :"reader";
SQL
}
# The other services' init steps run at the same time on a fresh stack: an advisory lock
# serialises the provisioning transactions (they all change the database ACL, "tuple concurrently
# updated"), and one short retry covers anything else that races. The statements are idempotent.
provision_db || { log "database provisioning raced another init step, retrying"; sleep 1; provision_db; } ||
  { log "database provisioning failed" >&2; exit 1; }
log "roles $OWNER, $WRITER, $READER and schema $SCHEMA ready"

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
