# The central provisioning manifest of the dev stack (compose one-shot `provision`, deploy/provision/provision.sh):
# one function per service, run in the order of SERVICES. Each entry is <service>:<volume dir>, the
# directory under OUT_ROOT where the service's <dir>-approle volume is mounted (docker-compose.yml).
# A function names what goes into secret/<service> and creates the database role(s); provision.sh
# then writes the AppRole. Steps: lib.sh. A new service adds an entry and a function here, plus its
# approle volume in the `provision` block (scripts/new-service.sh prints both).
#
# SECRETS_AHEAD: secrets written for a service that is not in the stack yet (no role, schema or
# AppRole), <service>:<function>, run after SERVICES. Empty while every service is in SERVICES.
SECRETS_AHEAD=""

# PKI_SERVICES: the services with a certificate from OpenBao PKI (mTLS phase 1, spec 18.2, D24, ticket
# 66): the two mTLS servers (mw-crypto; mw-auth's internal API, ticket 67) and every caller of them.
# Each gets a pki_int role named after it (CN and SAN = its compose DNS name, 72 h) and its AppRole
# policy may issue from that role only. A role may exist before its service is in SERVICES.
PKI_SERVICES="mw-crypto mw-auth mw-app-gateway mw-ussd mw-checkout mw-audit-dw mw-admin-api"

SERVICES="mw-core-mock:core-mock mw-crypto:crypto mw-auth:auth mw-simulators:simulators mw-orchestrator:orchestrator
  mw-audit-dw:audit-dw mw-app-gateway:app-gateway mw-notify:notify mw-ussd:ussd mw-admin-api:admin-api
  mw-adapter-telco:adapter-telco mw-adapter-billpay:adapter-billpay mw-adapter-bank:adapter-bank
  mw-portal-api:portal-api mw-checkout:checkout"

# Ledger stand-in. No PIN key since ticket 72 (D22): it verifies mw-auth's assertions with the
# public set at mw-auth's assertion-jwks.json, which needs no secret.
provision_mw_core_mock() {
  gen_once spring.datasource.password 40
  db_role coremock_rw coremock
}

# The HSM gateway (ticket 62, spec 18.8, D23): the user PIN of the softhsm mw-crypto token it logs in
# with (deploy/softhsm; spec 18.4: OpenBao keeps the PKCS#11 token PIN), synced from deploy/.env.
provision_mw_crypto() {
  gen_once spring.datasource.password 40
  sync_env mw.crypto.pkcs11.user-pin SOFTHSM_MWCRYPTO_USER_PIN
  db_role crypto_rw crypto
}

# Wallet authentication (ticket 67, spec 18.7): its own schema auth; Valkey (device sessions, nonces:
# ticket 68); its Keycloak realm services client
# for mw-crypto (tokenise, envelope; later PIN and JWS); its certificate comes from PKI_SERVICES.
provision_mw_auth() {
  gen_once spring.datasource.password 40
  sync_env mw.auth.crypto.client-secret KC_MW_AUTH_SECRET
  sync_env spring.data.redis.password VALKEY_PASSWORD
  db_role auth_rw auth
}

# Partner, SMSC and USSD gateway simulators: they sign with the same secrets the adapters verify
# (tickets 22, 23) and authenticate at APISIX as the MNO gateways (consumers ussd_<mno>).
provision_mw_simulators() {
  gen_once spring.datasource.password 40
  sync_env sim.callback-secrets.billagg BILLAGG_HMAC_SECRET
  sync_env sim.callback-secrets.c2wbank C2WBANK_HMAC_SECRET
  sync_env sim.ussd-gateway.credentials.gp USSD_GP_GATEWAY_SECRET
  sync_env sim.ussd-gateway.credentials.robi USSD_ROBI_GATEWAY_SECRET
  sync_env sim.ussd-gateway.credentials.bl USSD_BL_GATEWAY_SECRET
  sync_env sim.ussd-gateway.credentials.teletalk USSD_TELETALK_GATEWAY_SECRET
  # the payShield simulator logs in to the softhsm paysim token with it (ticket 64)
  sync_env sim.payshield.pkcs11.user-pin SOFTHSM_PAYSIM_USER_PIN
  # the simulated partner's private_key_jwt key at Keycloak realm partners (ticket 73)
  partner_sim_client_key
  db_role simulators_rw simulators
}

# Valkey: idempotency lock (ticket 50). The search hash key (ticket 35) is never rotated here: a new
# key would orphan every stored initiator_hash.
provision_mw_orchestrator() {
  gen_once spring.datasource.password 40
  sync_env spring.data.redis.password VALKEY_PASSWORD
  gen_once mw.orchestrator.search.hash-key 48
  db_role orchestrator_rw txn
}

# Three roles (spec 14.3, 16.4): schema owner audit_dw_rw (Flyway, spring.flyway.password), the
# service's audit_writer (spring.datasource.password) and the read-only audit_reader of the chain
# verify job and the search (ticket 50). Table grants come from the Flyway migrations.
provision_mw_audit_dw() {
  gen_once spring.datasource.password 40
  gen_once spring.flyway.password 40
  gen_once mw.audit-dw.chain-verify.reader-password 40
  # its Keycloak realm services client for mw-crypto (ticket 74, the signed audit anchor)
  sync_env mw.audit-dw.anchor.crypto.client-secret KC_MW_AUDIT_DW_SECRET
  field spring.flyway.password >"$tmp/ownerpw"
  field spring.datasource.password >"$tmp/writerpw"
  field mw.audit-dw.chain-verify.reader-password >"$tmp/readerpw"
  psql -1 -v ON_ERROR_STOP=1 -q \
    -v ownerpw="$(cat "$tmp/ownerpw")" -v writerpw="$(cat "$tmp/writerpw")" -v readerpw="$(cat "$tmp/readerpw")" \
    -v owner=audit_dw_rw -v writer=audit_writer -v reader=audit_reader -v schema=audit -v db="$PGDATABASE" <<'SQL'
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
  rm -f "$tmp/ownerpw" "$tmp/writerpw" "$tmp/readerpw"
  log "$SVC: roles audit_dw_rw, audit_writer, audit_reader and schema audit ready"
}

# Valkey: the device gate's key and session caches and nonces (tickets 29, 68). Its realm services
# client secret for mw-auth's internal API (ticket 68); its certificate comes from PKI_SERVICES.
provision_mw_app_gateway() {
  gen_once spring.datasource.password 40
  sync_env spring.data.redis.password VALKEY_PASSWORD
  sync_env mw.app-gateway.auth.client-secret KC_MW_APP_GATEWAY_SECRET
  db_role app_gateway_rw app_gateway
}

# SMPP 3.4 passwords are at most 8 characters.
provision_mw_notify() {
  gen_once spring.datasource.password 40
  gen_once mw.notify.smpp.password 8
  db_role notify_rw notify
}

# Valkey: USSD sessions. Its realm services client secret for mw-auth's internal API (txn
# authorisation, ticket 72); its certificate comes from PKI_SERVICES; the PIN transport key it
# fetches from mw-auth's public port (no secret).
provision_mw_ussd() {
  gen_once spring.datasource.password 40
  sync_env spring.data.redis.password VALKEY_PASSWORD
  sync_env mw.ussd.auth.client-secret KC_MW_USSD_SECRET
  db_role ussd_rw ussd
}

# Realm services client secret: internal query APIs of mw-orchestrator and mw-audit-dw (M3 gate).
# Its Flyway migrates two schemas: admin (search_path) and partner (partner configuration).
provision_mw_admin_api() {
  gen_once spring.datasource.password 40
  sync_env mw.admin-api.internal-auth.client-secret KC_MW_ADMIN_API_SECRET
  db_role admin_api_rw admin partner
}

provision_mw_adapter_telco() {
  gen_once spring.datasource.password 40
  db_role adapter_telco_rw partner_ref
}

# The dev aggregator's hmac-sha256 secret (ticket 22), shared with mw-simulators.
provision_mw_adapter_billpay() {
  gen_once spring.datasource.password 40
  sync_env mw.partner-secrets.billagg.hmac-secret BILLAGG_HMAC_SECRET
  db_role adapter_billpay_rw billpay_ref
}

# The dev C2W bank's hmac-sha256 secret (ticket 23), shared with mw-simulators.
provision_mw_adapter_bank() {
  gen_once spring.datasource.password 40
  sync_env mw.partner-secrets.c2wbank.hmac-secret C2WBANK_HMAC_SECRET
  db_role adapter_bank_rw bank_ref
}

# Realm services client secret: mw-orchestrator's internal query API (M3 gate).
provision_mw_portal_api() {
  gen_once spring.datasource.password 40
  sync_env mw.portal-api.orchestrator.auth.client-secret KC_MW_PORTAL_API_SECRET
  db_role portal_api_rw bulk
}

# The dev merchant's signing secret (ticket 31; the same value as the APISIX hmac-auth consumer
# merchant_demo) and its realm services client secret for mw-auth's internal API (checkout OTP and
# txn authorisation, ticket 72); its certificate comes from PKI_SERVICES.
provision_mw_checkout() {
  gen_once spring.datasource.password 40
  sync_env mw.checkout.merchants.merchant_demo.secret CHECKOUT_DEMO_MERCHANT_SECRET
  sync_env mw.checkout.auth.client-secret KC_MW_CHECKOUT_SECRET
  db_role checkout_rw checkout
}

