# Steps of the dev provisioning one-shot `provision` (sourced by provision.sh; POSIX sh). Each step
# works on the current service: SVC (mw-x), KV (secret/mw-x) and OUT (its approle volume), set by
# provision.sh before it calls the service's function in services.sh. Values go through files in
# $tmp (the vault CLI's @file form), never on a command line. Every step is idempotent.

log() { printf 'provision: %s\n' "$*"; }
die() { log "$*" >&2; exit 1; }

# vault_login: the dev root token from vault's init file (DEV ONLY, see deploy/vault/dev-unseal.sh).
# Compose starts `provision` once vault is healthy (unsealed, engines enabled, file written).
vault_login() {
  if [ -z "${VAULT_TOKEN:-}" ]; then
    INIT_FILE="${INIT_FILE:?VAULT_TOKEN or INIT_FILE is required}"
    VAULT_TOKEN="$(sed -n 's/.*"root_token": *"\([^"]*\)".*/\1/p' "$INIT_FILE" 2>/dev/null | head -1)"
  fi
  [ -n "$VAULT_TOKEN" ] || die "no Vault token in ${INIT_FILE:-VAULT_TOKEN} (is vault healthy?)"
  export VAULT_TOKEN VAULT_ADDR
}

random() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1"; }

has_field() { vault kv get -field="$1" "${2:-$KV}" >/dev/null 2>&1; }
field() { vault kv get -field="$1" "${2:-$KV}"; }

# kv_set key=@file...: write fields into the current secret (put when it does not exist yet).
kv_set() {
  if vault kv get "$KV" >/dev/null 2>&1; then vault kv patch "$KV" "$@" >/dev/null
  else vault kv put "$KV" "$@" >/dev/null; fi
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
# root CA at pki/ (DEV ONLY: generated in Vault; production signs the intermediate with an offline
# root, open point 24) and the intermediate at pki_int/ that issues every service certificate.
# Mounts are made here, not in deploy/vault/dev-unseal.sh (that runs its setup on the first start
# only, so an existing volume would never get them). Idempotent: an existing CA is kept.
pki_setup() {
  mounts="$(vault secrets list -format=json)"
  printf '%s' "$mounts" | grep -q '"pki/"' || vault secrets enable pki >/dev/null
  printf '%s' "$mounts" | grep -q '"pki_int/"' || vault secrets enable -path=pki_int pki >/dev/null
  vault secrets tune -max-lease-ttl=87600h pki >/dev/null
  vault secrets tune -max-lease-ttl=720h pki_int >/dev/null
  if [ -z "$(vault read -field=certificate pki/cert/ca 2>/dev/null)" ]; then
    vault write -field=certificate pki/root/generate/internal common_name="mfs-mw dev root CA" \
      issuer_name=dev-root key_type=ec key_bits=256 ttl=87600h >/dev/null
    log "pki: created the dev root CA"
  fi
  if [ -z "$(vault read -field=certificate pki_int/cert/ca 2>/dev/null)" ]; then
    vault write -field=csr pki_int/intermediate/generate/internal \
      common_name="mfs-mw dev services intermediate CA" key_type=ec key_bits=256 >"$tmp/int.csr"
    vault write -field=certificate pki/root/sign-intermediate csr=@"$tmp/int.csr" format=pem_bundle \
      ttl=43800h >"$tmp/int.pem"
    vault write pki_int/intermediate/set-signed certificate=@"$tmp/int.pem" >/dev/null
    rm -f "$tmp/int.csr" "$tmp/int.pem"
    log "pki: created the services intermediate CA"
  fi
}

# pki_role <svc>: the role a service issues its own certificate from: CN and DNS SAN = the service
# name (its compose DNS name), nothing else (no localhost, no IP), EC P-256, at most 72 h. Written
# on every run (a changed rule takes effect).
pki_role() {
  vault write "pki_int/roles/$1" allowed_domains="$1" allow_bare_domains=true allow_subdomains=false \
    allow_glob_domains=false allow_localhost=false allow_ip_sans=false enforce_hostnames=true \
    server_flag=true client_flag=true key_type=ec key_bits=256 ttl=72h max_ttl=72h >/dev/null
  log "pki: role $1 ready"
}

# apisix_edge: the edge credentials APISIX reads itself (ticket 100a, 100 split decision): APISIX's
# Vault secret manager speaks KV version 1 only, so a KV v1 mount apisix/ (secret/ is KV v2) holds
# one secret per channel client, apisix/<clientId> field password (EDGE_SECRETS of services.sh,
# synced from deploy/.env on every run); policy apisix-edge reads that mount and nothing else; the
# token APISIX uses (secret manager vault/edge, deploy/apisix/vault/edge.json) is created with the
# fixed dev id APISIX_VAULT_TOKEN (DEV ONLY, a fake value in dev.env.defaults), orphan and periodic,
# and renewed on every run. Values and the token id go through files, never on a command line.
apisix_edge() {
  [ -n "${APISIX_VAULT_TOKEN:-}" ] || die "edge: APISIX_VAULT_TOKEN is not set (cp dev.env.defaults .env first)"
  vault secrets list -format=json | grep -q '"apisix/"' || vault secrets enable -path=apisix -version=1 kv >/dev/null
  printf 'path "apisix/*" { capabilities = ["read"] }\n' | vault policy write apisix-edge - >/dev/null
  printf '%s' "$APISIX_VAULT_TOKEN" >"$tmp/tok"
  if vault write auth/token/lookup token=@"$tmp/tok" >/dev/null 2>&1; then
    vault write auth/token/renew token=@"$tmp/tok" >/dev/null
  else
    vault write auth/token/create-orphan id=@"$tmp/tok" policies=apisix-edge no_default_policy=true period=768h \
      display_name=apisix-edge >/dev/null
    log "edge: created the APISIX Vault token (policy apisix-edge)"
  fi
  rm -f "$tmp/tok"
  for e in ${EDGE_SECRETS:-}; do
    eval "val=\${${e#*:}:-}"
    [ -n "$val" ] || die "edge: ${e#*:} is not set (cp dev.env.defaults .env first)"
    printf '%s' "$val" >"$tmp/v"
    vault write "apisix/${e%%:*}" password=@"$tmp/v" >/dev/null
    rm -f "$tmp/v"
  done
  log "edge: apisix/ (KV v1) holds $(printf '%s\n' ${EDGE_SECRETS:-} | grep -c .) client secret(s)"
}

# in_pki <svc>: whether the service gets a certificate (PKI_SERVICES of services.sh).
in_pki() {
  case " $(printf '%s' "${PKI_SERVICES:-}" | tr '\n' ' ') " in *" $1 "*) return 0 ;; esac
  return 1
}

# approle: policy reading only secret/<svc> and its per-partner secrets secret/<svc>/<partner> (ADR
# D26) (and, for a PKI_SERVICES entry, issuing from its own pki_int role only), the AppRole, and a
# fresh secret-id plus the role-id in the service's own
# volume (Spring configtree, one file per property; the service runs as 10001).
approle() {
  {
    printf 'path "secret/data/%s" { capabilities = ["read"] }\n' "$SVC"
    printf 'path "secret/data/%s/*" { capabilities = ["read"] }\n' "$SVC"
    if in_pki "$SVC"; then printf 'path "pki_int/issue/%s" { capabilities = ["update"] }\n' "$SVC"; fi
    if [ -n "${EXTRA_POLICY:-}" ]; then printf '%s\n' "$EXTRA_POLICY"; fi
  } >"$tmp/policy.hcl"
  EXTRA_POLICY=""
  vault policy write "$SVC" - <"$tmp/policy.hcl" >/dev/null
  rm -f "$tmp/policy.hcl"
  vault write "auth/approle/role/$SVC" token_policies="$SVC" token_ttl=1h token_max_ttl=24h secret_id_ttl=0 >/dev/null
  mkdir -p "$OUT"
  rm -f "$OUT"/spring.cloud.vault.app-role.*
  vault read -field=role_id "auth/approle/role/$SVC/role-id" >"$OUT/spring.cloud.vault.app-role.role-id"
  vault write -f -field=secret_id "auth/approle/role/$SVC/secret-id" >"$OUT/spring.cloud.vault.app-role.secret-id"
  chown 10001:10001 "$OUT"/spring.cloud.vault.app-role.* 2>/dev/null || true
  chmod 0400 "$OUT"/spring.cloud.vault.app-role.*
  log "$SVC: AppRole ready"
}

# credential_upload_policy: the extra policy of mw-admin-api's partner credential upload (ticket 131, 131
# split decision): its staging area secret/<svc>/staged-credentials/* (Vault applies the most specific
# path only, so read is repeated there), and WRITE-ONLY access (create, update, patch; never read) to the
# per-partner secrets secret/<adapter>/<partner> of every mw-adapter-* service of the manifest (never the
# adapter's own service secret secret/<adapter>). Set as EXTRA_POLICY by provision_mw_admin_api.
credential_upload_policy() {
  printf 'path "secret/data/%s/staged-credentials/*" { capabilities = ["create", "update", "read"] }\n' "$SVC"
  printf 'path "secret/metadata/%s/staged-credentials/*" { capabilities = ["delete"] }\n' "$SVC"
  for entry in $SERVICES; do
    case "${entry%%:*}" in
      mw-adapter-*) printf 'path "secret/data/%s/*" { capabilities = ["create", "update", "patch"] }\n' "${entry%%:*}" ;;
    esac
  done
}

# partner_sim_client_key: the private_key_jwt credential of the Keycloak client partner-sim (realm
# partners, ticket 73, spec 18.2): an RSA-2048 key pair and a self-signed certificate, created once in
# the current secret (the simulator signs its client assertion with the private key; no client secret
# exists), then the certificate is registered on the client on every run (Keycloak's admin REST as the
# bootstrap admin), so a re-imported realm gets it back. Compose starts provision once keycloak is
# healthy. Credentials go to curl through files (@file), never on its command line.
partner_sim_client_key() {
  : "${KC_URL:=http://keycloak:8080}"
  [ -n "${KC_BOOTSTRAP_ADMIN_PASSWORD:-}" ] || die "$SVC: KC_BOOTSTRAP_ADMIN_PASSWORD is not set (cp dev.env.defaults .env first)"
  if ! has_field sim.partner-sim.private-key; then
    openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 3650 -subj /CN=partner-sim \
      -keyout "$tmp/partner.key" -out "$tmp/partner.crt" 2>/dev/null
    printf partner-sim >"$tmp/cid"
    kv_set "sim.partner-sim.private-key=@$tmp/partner.key" "sim.partner-sim.certificate=@$tmp/partner.crt" \
      "sim.partner-sim.client-id=@$tmp/cid"
    rm -f "$tmp/partner.key" "$tmp/partner.crt" "$tmp/cid"
    log "$SVC: created the partner-sim key pair (private_key_jwt)"
  fi
  field sim.partner-sim.certificate >"$tmp/partner.crt"
  printf '%s' "$KC_BOOTSTRAP_ADMIN_PASSWORD" >"$tmp/kcpw"
  at="$(curl -sf --max-time 10 --data-urlencode grant_type=password --data-urlencode client_id=admin-cli \
    --data-urlencode "username=${KC_BOOTSTRAP_ADMIN_USERNAME:-kc-admin}" --data-urlencode "password@$tmp/kcpw" \
    "$KC_URL/realms/master/protocol/openid-connect/token" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')" || at=""
  rm -f "$tmp/kcpw"
  [ -n "$at" ] || { rm -f "$tmp/partner.crt"; die "$SVC: no Keycloak admin token for the partner-sim certificate ($KC_URL)"; }
  printf 'Authorization: Bearer %s' "$at" >"$tmp/kcauth"
  id="$(curl -sf --max-time 10 -H "@$tmp/kcauth" "$KC_URL/admin/realms/partners/clients?clientId=partner-sim" \
    | sed -n 's/^\[{"id":"\([^"]*\)".*/\1/p')" || id=""
  ok=""
  if [ -n "$id" ] && curl -sf --max-time 10 -o /dev/null -H "@$tmp/kcauth" -F "keystoreFormat=Certificate PEM" \
    -F "file=@$tmp/partner.crt" "$KC_URL/admin/realms/partners/clients/$id/certificates/jwt.credential/upload-certificate"; then
    ok=1
  fi
  rm -f "$tmp/kcauth" "$tmp/partner.crt"
  [ -n "$ok" ] || die "$SVC: cannot register the partner-sim certificate in realm partners ($KC_URL)"
  log "$SVC: partner-sim certificate registered in realm partners"
}
