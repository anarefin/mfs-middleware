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
  [ -n "$val" ] || die "$SVC: $2 is not set (cp dev.env.defaults .env first)"
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

# pki_setup: the service certificate CA of mTLS phase 1 (spec 18.2, 18.4, D24, ticket 66), once: a
# root CA at pki/ (DEV ONLY: generated in OpenBao; production signs the intermediate with an offline
# root, open point 24) and the intermediate at pki_int/ that issues every service certificate.
# Mounts are made here, not in deploy/openbao/dev-unseal.sh (that runs its setup on the first start
# only, so an existing volume would never get them). Idempotent: an existing CA is kept.
pki_setup() {
  mounts="$(bao secrets list -format=json)"
  printf '%s' "$mounts" | grep -q '"pki/"' || bao secrets enable pki >/dev/null
  printf '%s' "$mounts" | grep -q '"pki_int/"' || bao secrets enable -path=pki_int pki >/dev/null
  bao secrets tune -max-lease-ttl=87600h pki >/dev/null
  bao secrets tune -max-lease-ttl=720h pki_int >/dev/null
  if [ -z "$(bao read -field=certificate pki/cert/ca 2>/dev/null)" ]; then
    bao write -field=certificate pki/root/generate/internal common_name="mfs-mw dev root CA" \
      issuer_name=dev-root key_type=ec key_bits=256 ttl=87600h >/dev/null
    log "pki: created the dev root CA"
  fi
  if [ -z "$(bao read -field=certificate pki_int/cert/ca 2>/dev/null)" ]; then
    bao write -field=csr pki_int/intermediate/generate/internal \
      common_name="mfs-mw dev services intermediate CA" key_type=ec key_bits=256 >"$tmp/int.csr"
    bao write -field=certificate pki/root/sign-intermediate csr=@"$tmp/int.csr" format=pem_bundle \
      ttl=43800h >"$tmp/int.pem"
    bao write pki_int/intermediate/set-signed certificate=@"$tmp/int.pem" >/dev/null
    rm -f "$tmp/int.csr" "$tmp/int.pem"
    log "pki: created the services intermediate CA"
  fi
}

# pki_role <svc>: the role a service issues its own certificate from: CN and DNS SAN = the service
# name (its compose DNS name), nothing else (no localhost, no IP), EC P-256, at most 72 h. Written
# on every run (a changed rule takes effect).
pki_role() {
  bao write "pki_int/roles/$1" allowed_domains="$1" allow_bare_domains=true allow_subdomains=false \
    allow_glob_domains=false allow_localhost=false allow_ip_sans=false enforce_hostnames=true \
    server_flag=true client_flag=true key_type=ec key_bits=256 ttl=72h max_ttl=72h >/dev/null
  log "pki: role $1 ready"
}

# in_pki <svc>: whether the service gets a certificate (PKI_SERVICES of services.sh).
in_pki() {
  case " $(printf '%s' "${PKI_SERVICES:-}" | tr '\n' ' ') " in *" $1 "*) return 0 ;; esac
  return 1
}

# approle: policy reading only secret/<svc> (and, for a PKI_SERVICES entry, issuing from its own
# pki_int role only), the AppRole, and a fresh secret-id plus the role-id in the service's own
# volume (Spring configtree, one file per property; the service runs as 10001).
approle() {
  {
    printf 'path "secret/data/%s" { capabilities = ["read"] }\n' "$SVC"
    if in_pki "$SVC"; then printf 'path "pki_int/issue/%s" { capabilities = ["update"] }\n' "$SVC"; fi
  } >"$tmp/policy.hcl"
  bao policy write "$SVC" - <"$tmp/policy.hcl" >/dev/null
  rm -f "$tmp/policy.hcl"
  bao write "auth/approle/role/$SVC" token_policies="$SVC" token_ttl=1h token_max_ttl=24h secret_id_ttl=0 >/dev/null
  mkdir -p "$OUT"
  rm -f "$OUT"/spring.cloud.vault.app-role.*
  bao read -field=role_id "auth/approle/role/$SVC/role-id" >"$OUT/spring.cloud.vault.app-role.role-id"
  bao write -f -field=secret_id "auth/approle/role/$SVC/secret-id" >"$OUT/spring.cloud.vault.app-role.secret-id"
  chown 10001:10001 "$OUT"/spring.cloud.vault.app-role.* 2>/dev/null || true
  chmod 0400 "$OUT"/spring.cloud.vault.app-role.*
  log "$SVC: AppRole ready"
}
