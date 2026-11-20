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

# PKI_SERVICES: the services with a certificate from Vault PKI (mTLS phase 1, spec 18.2, D24, ticket
# 66): the two mTLS servers (mw-crypto; mw-auth's internal API, ticket 67) and every caller of them.
# Each gets a pki_int role named after it (CN and SAN = its compose DNS name, 72 h) and its AppRole
# policy may issue from that role only. A role may exist before its service is in SERVICES.
PKI_SERVICES="mw-crypto mw-auth mw-app-gateway mw-ussd mw-checkout mw-audit-dw mw-admin-api mw-data-export mw-adapter-interop"

# EDGE_SECRETS: the credentials APISIX reads from the Vault KV v1 mount apisix/ (ticket 100a, lib.sh
# apisix_edge), <clientId>:<VAR>: apisix/<clientId> field password = deploy/.env's VAR, synced on every
# run. A channel client of the mw-admin-api registry with auth method BASIC is projected as a consumer
# whose basic-auth password is $secret://vault/edge/<clientId>/password.
EDGE_SECRETS="ussd-gp:USSD_GP_GATEWAY_SECRET ussd-robi:USSD_ROBI_GATEWAY_SECRET ussd-bl:USSD_BL_GATEWAY_SECRET
  ussd-teletalk:USSD_TELETALK_GATEWAY_SECRET"

# BANK_CODES: the banks of deploy/partner-routes/bank/bank-profiles.csv (ticket 117); each bank-<code>
# profile's callback secret is BANK_GATEWAY_HMAC_SECRET in mw-adapter-bank and mw-simulators alike
# (ticket 117e; scripts/test/test_bank_profiles.sh keeps this list equal to the CSV).
BANK_CODES="dbbl brac city ebl ucb mtb prime pubali dhaka one southeast standard sonali janata agrani rupali ibbl ab premier nrbc"

SERVICES="mw-core-mock:core-mock mw-crypto:crypto mw-auth:auth mw-simulators:simulators mw-orchestrator:orchestrator
  mw-audit-dw:audit-dw mw-app-gateway:app-gateway mw-notify:notify mw-ussd:ussd mw-admin-api:admin-api
  mw-adapter-telco:adapter-telco mw-adapter-billpay:adapter-billpay mw-adapter-bank:adapter-bank mw-adapter-merchant:adapter-merchant
  mw-adapter-card:adapter-card mw-adapter-remit:adapter-remit mw-adapter-atm:adapter-atm
  mw-adapter-interop:adapter-interop
  mw-portal-api:portal-api mw-checkout:checkout mw-data-export:data-export mw-recon:recon mw-compliance:compliance"

# Ledger stand-in. No PIN key since ticket 72 (D22): it verifies mw-auth's assertions with the
# public set at mw-auth's assertion-jwks.json, which needs no secret.
provision_mw_core_mock() {
  gen_once spring.datasource.password 40
  db_role coremock_rw coremock
}

# The HSM gateway (ticket 62, spec 18.8, D23): the user PIN of the softhsm mw-crypto token it logs in
# with (deploy/softhsm; spec 18.4: Vault keeps the PKCS#11 token PIN), synced from deploy/.env.
provision_mw_crypto() {
  gen_once spring.datasource.password 40
  sync_env mw.crypto.pkcs11.user-pin SOFTHSM_MWCRYPTO_USER_PIN
  db_role crypto_rw crypto
}

# Wallet authentication (ticket 67, spec 18.7): its own schema auth; Redis (device sessions, nonces:
# ticket 68); its Keycloak realm services client
# for mw-crypto (tokenise, envelope; later PIN and JWS); its certificate comes from PKI_SERVICES.
provision_mw_auth() {
  gen_once spring.datasource.password 40
  sync_env mw.auth.crypto.client-secret KC_MW_AUTH_SECRET
  sync_env spring.data.redis.password REDIS_PASSWORD
  db_role auth_rw auth
}

# Partner, SMSC and USSD gateway simulators: they sign with the same secrets the adapters verify
# (tickets 22, 23) and authenticate at APISIX as the MNO gateways (consumers ussd_<mno>).
provision_mw_simulators() {
  gen_once spring.datasource.password 40
  sync_env sim.callback-secrets.billagg BILLAGG_HMAC_SECRET
  # the WS-Security UsernameToken password of the billagg SOAP front end (SOAP billers)
  sync_env sim.billagg.ws-password BILLAGG_WS_PASSWORD
  # the WS-Security password of the merchant system SOAP mock (MerchantSoapMock, ticket 115b)
  sync_env sim.merchant-soap.ws-password MERCHANT_SOAP_WS_PASSWORD
  sync_env sim.callback-secrets.c2wbank C2WBANK_HMAC_SECRET
  # the simulated bank gateway signs every bank-<code> callback with the dev bank secret (ticket 117e)
  local code
  for code in $BANK_CODES; do
    sync_env "sim.callback-secrets.bank-$code" BANK_GATEWAY_HMAC_SECRET
  done
  # the bill aggregator mocks of ticket 114 sign their callbacks with the dev aggregator secret
  sync_env sim.callback-secrets.sslcommerz BILLAGG_HMAC_SECRET
  sync_env sim.callback-secrets.ekpay BILLAGG_HMAC_SECRET
  sync_env sim.callback-secrets.agg3 BILLAGG_HMAC_SECRET
  sync_env sim.callback-secrets.agg4 BILLAGG_HMAC_SECRET
  # the card acquirer's 3-D Secure webhook (CyberSourceMock, ticket 118)
  sync_env sim.callback-secrets.cybersource CYBERSOURCE_HMAC_SECRET
  # the remittance partner signs its credits to mw-adapter-remit (UcbImtMock, ticket 119)
  sync_env sim.callback-secrets.ucb-imt UCB_IMT_HMAC_SECRET
  # the UCB ATM switch's HMAC (UcbAtmMock dispense status, ticket 120a)
  sync_env sim.callback-secrets.ucbatm UCBATM_HMAC_SECRET
  # the push server mock checks the key mw-notify sends (ticket 110)
  sync_env sim.push.api-key PUSH_API_KEY
  sync_env sim.ussd-gateway.credentials.gp USSD_GP_GATEWAY_SECRET
  sync_env sim.ussd-gateway.credentials.robi USSD_ROBI_GATEWAY_SECRET
  sync_env sim.ussd-gateway.credentials.bl USSD_BL_GATEWAY_SECRET
  sync_env sim.ussd-gateway.credentials.teletalk USSD_TELETALK_GATEWAY_SECRET
  # the payShield simulator logs in to the softhsm paysim token with it (ticket 64)
  sync_env sim.payshield.pkcs11.user-pin SOFTHSM_PAYSIM_USER_PIN
  # the file mocks (ticket 123): partner-sftp login, realm services client for mw-core-mock's statements
  sync_env sim.files.sftp.password SFTP_DEV_PASSWORD
  sync_env sim.files.trustbank.core.client-secret KC_MW_SIMULATORS_SECRET
  # the simulated partner's private_key_jwt key at Keycloak realm partners (ticket 73)
  partner_sim_client_key
  # the NPSB switch mock's XML-DSig key pair (ticket 121b; mw-adapter-interop trusts its certificate)
  npsb_switch_key
  db_role simulators_rw simulators
}

# Redis: idempotency lock (ticket 50). The search hash key (ticket 35) is never rotated here: a new
# key would orphan every stored initiator_hash. Realm services client secret: the core wallet API
# (/core/v1, audience mw-core-mock, ADR D31, ticket 95a).
provision_mw_orchestrator() {
  gen_once spring.datasource.password 40
  sync_env spring.data.redis.password REDIS_PASSWORD
  sync_env mw.orchestrator.core.client-secret KC_MW_ORCHESTRATOR_SECRET
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

# Redis: the device gate's key and session caches and nonces (tickets 29, 68). Its realm services
# client secret for mw-auth's internal API (ticket 68); its certificate comes from PKI_SERVICES.
provision_mw_app_gateway() {
  gen_once spring.datasource.password 40
  sync_env spring.data.redis.password REDIS_PASSWORD
  sync_env mw.app-gateway.auth.client-secret KC_MW_APP_GATEWAY_SECRET
  # HMAC-SHA256 key of a previewId (ticket 105); created once, never a config-file value
  gen_once mw.app-gateway.preview.signing-key 48
  # secret the linked bank account column key is derived from (ticket 117b); created once
  gen_once mw.app-gateway.linked-accounts.encryption-key 48
  db_role app_gateway_rw app_gateway
}

# SMPP 3.4 passwords are at most 8 characters. The push server API key (ticket 110) is shared with
# mw-simulators' push mock.
provision_mw_notify() {
  gen_once spring.datasource.password 40
  gen_once mw.notify.smpp.password 8
  sync_env mw.notify.push.api-key PUSH_API_KEY
  db_role notify_rw notify
}

# Redis: USSD sessions. Its realm services client secret for mw-auth's internal API (txn
# authorisation, ticket 72); its certificate comes from PKI_SERVICES; the PIN transport key it
# fetches from mw-auth's public port (no secret).
provision_mw_ussd() {
  gen_once spring.datasource.password 40
  sync_env spring.data.redis.password REDIS_PASSWORD
  sync_env mw.ussd.auth.client-secret KC_MW_USSD_SECRET
  db_role ussd_rw ussd
}

# Realm services client secret: internal query APIs of mw-orchestrator and mw-audit-dw (M3 gate).
# Its Flyway migrates two schemas: admin (search_path) and partner (partner configuration).
provision_mw_admin_api() {
  gen_once spring.datasource.password 40
  sync_env mw.admin-api.internal-auth.client-secret KC_MW_ADMIN_API_SECRET
  # the APISIX Admin API key: the channel client registry projects its consumers there (ticket 100a)
  sync_env mw.apisix.admin-key APISIX_ADMIN_KEY
  db_role admin_api_rw admin partner
  # partner credential upload (ticket 131): staging area + write-only access to the adapters' partner secrets
  EXTRA_POLICY="$(credential_upload_policy)"
}

# Operator OCS secrets (ticket 112a), one Vault secret per partner, read by the route when it
# loads. The database password stays on the service secret, so KV is restored before db_role.
provision_mw_adapter_telco() {
  gen_once spring.datasource.password 40
  KV="secret/$SVC/gp-ocs"
  gen_once api-key 40
  KV="secret/$SVC/robi-ocs"
  gen_once hmac-secret 40
  KV="secret/$SVC/teletalk-ocs"
  gen_once client-secret 40
  KV="secret/$SVC/bl-ocs"
  gen_once basic-user 24
  gen_once basic-password 40
  KV="secret/$SVC"
  db_role adapter_telco_rw partner_ref
}

# The dev aggregator's hmac-sha256 secret (ticket 22) and the WS-Security password of its SOAP
# front end (field ws-password, the SOAP billers), shared with mw-simulators. Partner secrets
# live one secret per partner, secret/mw-adapter-billpay/<partner> (ADR D28: read by the partner
# route through Camel's vault function when it loads).
provision_mw_adapter_billpay() {
  gen_once spring.datasource.password 40
  KV="secret/$SVC/billagg"
  sync_env hmac-secret BILLAGG_HMAC_SECRET
  sync_env ws-password BILLAGG_WS_PASSWORD
  # The bill aggregators of ticket 114 (deploy/partner-routes/aggregators/generate.mjs): callbacks are
  # hmac-sha256 with the dev aggregator secret (shared with mw-simulators); the outbound credential
  # follows each one's auth (the simulator checks only that the scheme is present).
  local agg
  for agg in sslcommerz ekpay agg3 agg4; do
    KV="secret/$SVC/$agg"; sync_env hmac-secret BILLAGG_HMAC_SECRET
  done
  KV="secret/$SVC/ekpay"; gen_once client-secret 40
  KV="secret/$SVC/agg3"; gen_once ws-password 40
  KV="secret/$SVC/agg4"; gen_once basic-user 24; gen_once basic-password 40
  KV="secret/$SVC"
  db_role adapter_billpay_rw billpay_ref
}

# The dev C2W bank's hmac-sha256 secret (ticket 23), shared with mw-simulators: the field hmac-secret
# of the partner's own secret secret/mw-adapter-bank/c2wbank, read by its route (ADR D28, ticket 84).
provision_mw_adapter_bank() {
  gen_once spring.datasource.password 40
  KV="secret/$SVC/c2wbank"
  sync_env hmac-secret C2WBANK_HMAC_SECRET
  # Bank gateway profiles (ticket 117, deploy/partner-routes/bank/bank-profiles.csv): every bank
  # signs its callbacks hmac-sha256 with the dev secret mw-simulators shares (ticket 117e); the
  # outbound auth field follows the profile's auth column.
  local code
  for code in $BANK_CODES; do
    KV="secret/$SVC/bank-$code"; sync_env hmac-secret BANK_GATEWAY_HMAC_SECRET
  done
  for code in brac ebl mtb pubali one standard; do
    KV="secret/$SVC/bank-$code"; gen_once api-key 40
  done
  for code in sonali agrani ibbl premier; do
    KV="secret/$SVC/bank-$code"; gen_once basic-user 24; gen_once basic-password 40
  done
  for code in janata rupali ab nrbc; do
    KV="secret/$SVC/bank-$code"; gen_once ws-password 40
  done
  KV="secret/$SVC"
  db_role adapter_bank_rw bank_ref
}

# The dev merchant system's WS-Security password (ticket 115), shared with mw-simulators: the field
# ws-password of the partner's own secret secret/mw-adapter-merchant/merchant-soap, read by its route.
provision_mw_adapter_merchant() {
  gen_once spring.datasource.password 40
  KV="secret/$SVC/merchant-soap"
  sync_env ws-password MERCHANT_SOAP_WS_PASSWORD
  KV="secret/$SVC"
  db_role adapter_merchant_rw merchant_ref
}

# The dev card acquirer's HMAC secret (ticket 118), shared with mw-simulators (CyberSourceMock): the
# field hmac-secret of the partner's own secret secret/mw-adapter-card/cybersource, read by its route.
provision_mw_adapter_card() {
  gen_once spring.datasource.password 40
  KV="secret/$SVC/cybersource"
  sync_env hmac-secret CYBERSOURCE_HMAC_SECRET
  KV="secret/$SVC"
  db_role adapter_card_rw card_ref
}

# The dev remittance partner's HMAC secret (ticket 119), shared with mw-simulators (UcbImtMock): the
# field hmac-secret of the partner's own secret secret/mw-adapter-remit/ucb-imt, which verifies its
# signed credits (and signs the outbound confirmations).
provision_mw_adapter_remit() {
  gen_once spring.datasource.password 40
  # HMAC key of the remittance fingerprint (M9 review: never a plain hash of the MSISDN); created once
  gen_once mw.remit.fingerprint-key 48
  KV="secret/$SVC/ucb-imt"
  sync_env hmac-secret UCB_IMT_HMAC_SECRET
  KV="secret/$SVC"
  db_role adapter_remit_rw remit_ref
}

# The UCB ATM switch (ticket 120a): WS-Security UsernameToken password of the inbound SOAP API
# (secret/mw-adapter-atm, field mw.atm.inbound.ws-password) and the HMAC of the dispense-status
# call (secret/mw-adapter-atm/ucbatm, field hmac-secret), the same value as mw-simulators' UcbAtmMock.
provision_mw_adapter_atm() {
  gen_once spring.datasource.password 40
  sync_env mw.atm.inbound.ws-password UCBATM_WS_PASSWORD
  KV="secret/$SVC/ucbatm"
  sync_env hmac-secret UCBATM_HMAC_SECRET
  KV="secret/$SVC"
  db_role adapter_atm_rw atm_ref
}

# The NPSB interop adapter (ticket 121a): its database role and schema. Its messages are signed and
# verified through mw-crypto (XML-DSig, ticket 121b), so it holds no key material of its own: the realm
# services client secret for mw-crypto (crypto-client-secret); its own client certificate comes from
# PKI_SERVICES. The switch's certificate npsb-certificate-pem is the NPSB switch mock's (mw-simulators,
# npsb_switch_key), so the stack verifies the mock's signed answers and inbound pacs.008 pushes.
provision_mw_adapter_interop() {
  gen_once spring.datasource.password 40
  sync_env crypto-client-secret KC_MW_ADAPTER_INTEROP_SECRET
  npsb_switch_certificate
  db_role adapter_interop_rw interop_ref
}

# Realm services client secret: mw-orchestrator's internal query API (M3 gate).
provision_mw_portal_api() {
  gen_once spring.datasource.password 40
  sync_env mw.portal-api.orchestrator.auth.client-secret KC_MW_PORTAL_API_SECRET
  db_role portal_api_rw bulk
}

# The dev merchants' signing secrets (ticket 31; each the same value as its APISIX hmac-auth consumer
# merchant_demo, merchant_pharmacy, merchant_fashion) and its realm services client secret for mw-auth's internal API (checkout OTP and
# txn authorisation, ticket 72); its certificate comes from PKI_SERVICES.
# Realm services client for mw-crypto tokenise, and the dev SFTP password of the warehouse drop.
provision_mw_data_export() {
  gen_once spring.datasource.password 40
  sync_env mw.export.sftp.password SFTP_DEV_PASSWORD
  sync_env mw.export.crypto.client-secret KC_MW_DATA_EXPORT_SECRET
  db_role data_export_rw data_export
}

# Trust-bank SFTP password (ticket 126) and the realm services client secret of the trust position job's core
# e-money total (ticket 127c). No mTLS certificate: the service does not call mw-crypto.
provision_mw_recon() {
  gen_once spring.datasource.password 40
  sync_env mw.recon.trust.password SFTP_DEV_PASSWORD
  sync_env mw.recon.core.client-secret KC_MW_RECON_SECRET
  db_role recon_rw recon
}

# Customer eKYC (ticket 124a): its own schema compliance. Ticket 124b adds the mw-crypto client secret
# (KC_MW_COMPLIANCE_SECRET), the EC credentials and its PKI_SERVICES entry.
provision_mw_compliance() {
  gen_once spring.datasource.password 40
  db_role compliance_rw compliance
}

provision_mw_checkout() {
  gen_once spring.datasource.password 40
  sync_env mw.checkout.merchants.merchant_demo.secret CHECKOUT_DEMO_MERCHANT_SECRET
  sync_env mw.checkout.merchants.merchant_pharmacy.secret CHECKOUT_PHARMACY_MERCHANT_SECRET
  sync_env mw.checkout.merchants.merchant_fashion.secret CHECKOUT_FASHION_MERCHANT_SECRET
  sync_env mw.checkout.auth.client-secret KC_MW_CHECKOUT_SECRET
  db_role checkout_rw checkout
}

