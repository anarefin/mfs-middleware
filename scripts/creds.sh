#!/usr/bin/env bash
# Prints every login and key a tester needs on the dev/test stack, grouped by where it is used
# (docs/testing-guide.html section 1): scripts/creds.sh
# Values come from deploy/.env (a copy of deploy/dev.env.defaults: the tester logins are fixed,
# documented values, the rest random) and, while the stack is up, the Vault token from the
# vault-data volume (the fixed dev-root-token; a volume created before it: the init root token).
# Dev/test stack only: never a real secret.
set -eu
. "$(dirname "$0")/lib/common.sh"

[ -f "$STACK_ENV" ] || die "creds: deploy/.env does not exist (cd deploy && cp dev.env.defaults .env)"
env_val() { sed -n "s/^$1=//p" "$STACK_ENV" | tail -1; }
v() { local x; x="$(env_val "$1")"; printf '%s' "${x:-<not set: see deploy/dev.env.defaults>}"; }

port="${MW_HTTPS_PORT:-}"
base="https://localhost${port:+:$port}"

vault="start the stack, then run scripts/creds.sh again"
if [ -f "$STACK_FILE" ] && command -v docker >/dev/null 2>&1; then
  stack_compose core
  if tok="$(docker compose "${DC_F[@]}" exec -T vault cat /vault/file/dev-token 2>/dev/null)" && [ -n "$tok" ]; then
    vault="$tok"
  elif init="$(docker compose "${DC_F[@]}" exec -T vault cat /vault/file/dev-init.json 2>/dev/null)"; then
    tok="$(printf '%s' "$init" | sed -n 's/.*"root_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
    [ -n "$tok" ] && vault="$tok"
  fi
fi

cat <<EOF
Dev/test stack only, never real secrets. The logins testers type are fixed, documented values
(docs/testing-guide.html section 1); the other keys are random per checkout (deploy/.env).

== Test console  $base/tester  (scripts/tester.sh first)
   Every login and key below, in the browser:  $base/tester/credentials
   What the seeder created (personas, wallets):  $base/tester/seed-data
   No sign-in. It signs in as the users below by itself.

== Ops console  $base/console   (Keycloak realm ops: password + 6-digit one-time code)
   Password of every ops user:  $(v KC_OPS_TEST_PASSWORD)
   Every ops user has the pre-set code secret (no authenticator app needed):
     seed-ops-maker       viewer, ops-maker           code: scripts/totp.sh seed-ops-maker
     seed-ops-checker     viewer, ops-checker         code: scripts/totp.sh seed-ops-checker
     seed-ops-viewer      viewer (read only)          code: scripts/totp.sh seed-ops-viewer
     seed-ops-compliance  compliance-officer          code: scripts/totp.sh seed-ops-compliance
     seed-ops-secadmin1   security-admin (keys)       code: scripts/totp.sh seed-ops-secadmin1
     seed-ops-secadmin2   security-admin (keys)       code: scripts/totp.sh seed-ops-secadmin2
     ops-maker01, ops-checker01, ops-viewer01, ops-compliance01, ops-auditor01,
     ops-admin01 (viewer, integration-maker, security-admin)
                                                  code: scripts/totp.sh ops-maker01

== App users: mw-auth login (MSISDN + PIN + bound device; ticket 69)   app PIN (mw-auth login): $(v MW_SEED_APP_PIN)
   customer01 01711000001 | customer02 01711000002 | agent01 01811000001
   merchant01 01911000001 | merchant02 01911000002 | seed-customer01 01712000001 | seed-agent01 01812000001
   transaction PIN (every channel, checked by mw-auth since ticket 72): the app PIN $(v MW_SEED_APP_PIN)
   A token from the shell: MW_SEED_APP_PIN=... node scripts/lib/mw-auth-token.mjs 01711000001
   (binds a new device; MW_EDGE_URL, MW_SIM_URL). There is no Keycloak login for app users.

== Portal users (realm portals, client mw-portal: password + 6-digit one-time code)   password: $(v KC_PORTALS_TEST_PASSWORD)
   Every portal user has the pre-set code secret (no authenticator app needed):
     seed-org-maker, seed-org-checker (org ORG001)   code: scripts/totp.sh seed-org-maker
     org-maker01, org-checker01, org-makerchecker01 (org ORG001), org-checker02 (other org), sp-viewer01 (no org),
     sp-viewer02 (SP portal, org ORG002)
                                                     code: scripts/totp.sh org-maker01

== Keycloak admin (no browser console at the edge; use kcadm.sh in the container)
   user kc-admin   password $(v KC_BOOTSTRAP_ADMIN_PASSWORD)

== Vault (internal only; docker compose exec vault ...)
   root token  $vault

== APISIX
   test routes  header  apikey: $(v MW_E2E_API_KEY)      (consumer mw_e2e, /test/*)
   admin API    header  X-API-KEY: $(v APISIX_ADMIN_KEY)   (127.0.0.1:9180 inside the apisix container)

== SoftHSM PKCS#11 tokens (deploy/softhsm; p11-kit RPC on 127.0.0.1, see deploy/softhsm/README.md)
   mw-crypto  port 5657  label $(v SOFTHSM_MWCRYPTO_LABEL)  user PIN $(v SOFTHSM_MWCRYPTO_USER_PIN)  SO PIN $(v SOFTHSM_MWCRYPTO_SO_PIN)
   paysim     port 5658  label $(v SOFTHSM_PAYSIM_LABEL)  user PIN $(v SOFTHSM_PAYSIM_USER_PIN)  SO PIN $(v SOFTHSM_PAYSIM_SO_PIN)

== Partner and merchant signing secrets
   checkout merchant merchant-demo (hmac-auth)  $(v CHECKOUT_DEMO_MERCHANT_SECRET)
   checkout merchant merchant-pharmacy          $(v CHECKOUT_PHARMACY_MERCHANT_SECRET)
   checkout merchant merchant-fashion           $(v CHECKOUT_FASHION_MERCHANT_SECRET)
   bill aggregator callbacks (HMAC-SHA256)      $(v BILLAGG_HMAC_SECRET)
   bill aggregator SOAP (WS-Security, user bits) $(v BILLAGG_WS_PASSWORD)
   bank C2W callbacks (HMAC-SHA256)             $(v C2WBANK_HMAC_SECRET)
   push server API key (mw-notify -> push mock)  $(v PUSH_API_KEY)

== USSD gateways (basic auth, user ussd-<mno>, POST $base/ussd/<mno>)
   gp $(v USSD_GP_GATEWAY_SECRET) | robi $(v USSD_ROBI_GATEWAY_SECRET) | bl $(v USSD_BL_GATEWAY_SECRET) | teletalk $(v USSD_TELETALK_GATEWAY_SECRET)
EOF
