# Steps of the dev provisioning one-shot `provision` (sourced by provision.sh; POSIX sh). Each step
# works on the current service: SVC (mw-x), KV (secret/mw-x) and OUT (its approle volume), set by
# provision.sh before it calls the service's function in services.sh. Values go through files in
# $tmp (bao's @file form), never on a command line. Every step is idempotent.

log() { printf 'provision: %s\n' "$*"; }
die() { log "$*" >&2; exit 1; }

# bao_login: the dev root token from openbao's init file (DEV ONLY, see deploy/openbao/dev-unseal.sh).
# Compose starts `provision` once openbao is healthy (unsealed, engines enabled, file written).
bao_login() {
  if [ -z "${BAO_TOKEN:-}" ]; then
    INIT_FILE="${INIT_FILE:?BAO_TOKEN or INIT_FILE is required}"
    BAO_TOKEN="$(sed -n 's/.*"root_token": *"\([^"]*\)".*/\1/p' "$INIT_FILE" 2>/dev/null | head -1)"
  fi
  [ -n "$BAO_TOKEN" ] || die "no OpenBao token in ${INIT_FILE:-BAO_TOKEN} (is openbao healthy?)"
  export BAO_TOKEN BAO_ADDR
}

random() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1"; }

has_field() { bao kv get -field="$1" "${2:-$KV}" >/dev/null 2>&1; }
field() { bao kv get -field="$1" "${2:-$KV}"; }

# kv_set key=@file...: write fields into the current secret (put when it does not exist yet).
kv_set() {
  if bao kv get "$KV" >/dev/null 2>&1; then bao kv patch "$KV" "$@" >/dev/null
  else bao kv put "$KV" "$@" >/dev/null; fi
}

# gen_once <key> <length>: a random value, created once and never overwritten.
gen_once() {
  if has_field "$1"; then return 0; fi
  random "$2" >"$tmp/v"
  kv_set "$1=@$tmp/v"
  rm -f "$tmp/v"
  log "$SVC: created $1"
}

# sync_env <key> <VAR>: the generated deploy/.env value, written on every run (a regenerated .env
# takes effect). Compose requires the variable, so an empty one is an error.
sync_env() {
  eval "val=\${$2:-}"
  [ -n "$val" ] || die "$SVC: $2 is not set (run scripts/gen-dev-env.sh first)"
  printf '%s' "$val" >"$tmp/v"
  kv_set "$1=@$tmp/v"
  rm -f "$tmp/v"
  log "$SVC: $1 synced from $2"
}

# copy_key <from-svc> <from-key> <to-key>: another service's value, copied on every run (the core's
# PIN public key: the core team handing it over). The source must already exist (manifest order).
copy_key() {
  has_field "$2" "secret/$1" || die "$SVC: no $2 in secret/$1 (is $1 provisioned before $SVC?)"
  field "$2" "secret/$1" >"$tmp/v"
  kv_set "$3=@$tmp/v"
  rm -f "$tmp/v"
  log "$SVC: $3 copied from secret/$1"
}

# pin_keypair <key-id>: the core mock's RSA-2048 PIN key pair, created once (channels keep working).
pin_keypair() {
  if has_field mw.coremock.pin.private-key; then return 0; fi
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp/private.pem" 2>/dev/null
  openssl pkey -in "$tmp/private.pem" -pubout -out "$tmp/public.pem"
  printf '%s' "$1" >"$tmp/kid"
  kv_set "mw.coremock.pin.private-key=@$tmp/private.pem" "mw.coremock.pin.public-key=@$tmp/public.pem" \
    "mw.coremock.pin.key-id=@$tmp/kid"
  rm -f "$tmp/private.pem" "$tmp/public.pem" "$tmp/kid"
  log "$SVC: created PIN key pair $1"
}

# db_role <role> <schema> [<schema>...]: login role with spring.datasource.password, every schema
# owned by it (search_path the first), CONNECT only on the database. The advisory lock serialises
# against anything else changing the database ACL at the same time ("tuple concurrently updated").
db_role() {
  field spring.datasource.password >"$tmp/pw"
  psql -1 -v ON_ERROR_STOP=1 -q -v pw="$(cat "$tmp/pw")" -v role="$1" -v schema="$2" -v db="$PGDATABASE" <<'SQL'
DO $$ BEGIN PERFORM pg_advisory_xact_lock(hashtext('mw-service-init')); END $$;
SELECT format('CREATE ROLE %I LOGIN', :'role') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'role') \gexec
ALTER ROLE :"role" PASSWORD :'pw';
CREATE SCHEMA IF NOT EXISTS :"schema" AUTHORIZATION :"role";
ALTER ROLE :"role" IN DATABASE :"db" SET search_path = :"schema";
REVOKE ALL ON DATABASE :"db" FROM :"role";
GRANT CONNECT ON DATABASE :"db" TO :"role";
SQL
  rm -f "$tmp/pw"
  role="$1"; shift
  schemas="$*"; shift
  for extra in "$@"; do
    psql -v ON_ERROR_STOP=1 -q -v role="$role" -v schema="$extra" -v db="$PGDATABASE" <<'SQL'
CREATE SCHEMA IF NOT EXISTS :"schema" AUTHORIZATION :"role";
SQL
  done
  log "$SVC: role $role and schema(s) $schemas ready"
}

# approle: policy reading only secret/<svc>, the AppRole, and a fresh secret-id plus the role-id in
# the service's own volume (Spring configtree, one file per property; the service runs as 10001).
approle() {
  bao policy write "$SVC" - >/dev/null <<EOF
path "secret/data/$SVC" { capabilities = ["read"] }
EOF
  bao write "auth/approle/role/$SVC" token_policies="$SVC" token_ttl=1h token_max_ttl=24h secret_id_ttl=0 >/dev/null
  mkdir -p "$OUT"
  rm -f "$OUT"/spring.cloud.vault.app-role.*
  bao read -field=role_id "auth/approle/role/$SVC/role-id" >"$OUT/spring.cloud.vault.app-role.role-id"
  bao write -f -field=secret_id "auth/approle/role/$SVC/secret-id" >"$OUT/spring.cloud.vault.app-role.secret-id"
  chown 10001:10001 "$OUT"/spring.cloud.vault.app-role.* 2>/dev/null || true
  chmod 0400 "$OUT"/spring.cloud.vault.app-role.*
  log "$SVC: AppRole ready"
}
