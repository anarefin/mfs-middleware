#!/usr/bin/env bash
# Prints every login and key a tester needs on the dev/test stack, grouped by where it is used
# (docs/testing-guide.html section 1): scripts/creds.sh
# Values come from deploy/.env (a copy of deploy/dev.env.defaults: the tester logins are fixed,
# documented values, the rest random) and, while the stack is up, the OpenBao token from the
# openbao-data volume (the fixed dev-root-token; a volume created before it: the init root token).
# Dev/test stack only: never a real secret.
set -eu
. "$(dirname "$0")/lib/common.sh"

[ -f "$STACK_ENV" ] || die "creds: deploy/.env does not exist (cd deploy && cp dev.env.defaults .env)"
env_val() { sed -n "s/^$1=//p" "$STACK_ENV" | tail -1; }
v() { local x; x="$(env_val "$1")"; printf '%s' "${x:-<not set: see deploy/dev.env.defaults>}"; }

port="${MW_HTTPS_PORT:-}"
base="https://localhost${port:+:$port}"

bao="start the stack, then run scripts/creds.sh again"
if [ -f "$STACK_FILE" ] && command -v docker >/dev/null 2>&1; then
  stack_compose core
  if tok="$(docker compose "${DC_F[@]}" exec -T openbao cat /openbao/file/dev-token 2>/dev/null)" && [ -n "$tok" ]; then
    bao="$tok"
  elif init="$(docker compose "${DC_F[@]}" exec -T openbao cat /openbao/file/dev-init.json 2>/dev/null)"; then
    tok="$(printf '%s' "$init" | sed -n 's/.*"root_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
    [ -n "$tok" ] && bao="$tok"
  fi
fi

cat <<EOF
Dev/test stack only, never real secrets. The logins testers type are fixed, documented values
(docs/testing-guide.html section 1); the other keys are random per checkout (deploy/.env).

== Test console  $base/tester  (scripts/tester.sh first)
   No sign-in. It signs in as the users below by itself.

== Ops console  $base/console   (Keycloak realm ops: password + 6-digit one-time code)
   Password of every ops user:  $(v KC_OPS_TEST_PASSWORD)
   No authenticator app needed (after the seed profile ran):
     seed-ops-maker       viewer, ops-maker           code: scripts/totp.sh seed-ops-maker
     seed-ops-checker     viewer, ops-checker         code: scripts/totp.sh seed-ops-checker
     seed-ops-viewer      viewer (read only)          code: scripts/totp.sh seed-ops-viewer
     seed-ops-compliance  compliance-officer          code: scripts/totp.sh seed-ops-compliance
   Enrol an authenticator app at the first sign-in (FreeOTP or Aegis; the codes are SHA-256):
     ops-maker01, ops-checker01, ops-viewer01, ops-compliance01, ops-auditor01,
     ops-admin01 (viewer, integration-maker, security-admin)

== App users (realm customers, client mw-app)   password: $(v KC_CUSTOMERS_TEST_PASSWORD)
   customer01 01711000001 | customer02 01711000002 | agent01 01811000001
   merchant01 01911000001 | merchant02               wallet PIN of every wallet: 1234

== Portal users (realm portals, client mw-portal)   password: $(v KC_PORTALS_TEST_PASSWORD)
   org-maker01, org-checker01, org-makerchecker01 (org ORG001), org-checker02 (other org), sp-viewer01

== Keycloak admin (no browser console at the edge; use kcadm.sh in the container)
   user kc-admin   password $(v KC_BOOTSTRAP_ADMIN_PASSWORD)

== OpenBao (internal only; docker compose exec openbao ...)
   root token  $bao

== APISIX
   test routes  header  apikey: $(v MW_E2E_API_KEY)      (consumer mw_e2e, /test/*)
   admin API    header  X-API-KEY: $(v APISIX_ADMIN_KEY)   (127.0.0.1:9180 inside the apisix container)

== Partner and merchant signing secrets
   checkout merchant merchant-demo (hmac-auth)  $(v CHECKOUT_DEMO_MERCHANT_SECRET)
   bill aggregator callbacks (HMAC-SHA256)      $(v BILLAGG_HMAC_SECRET)
   bank C2W callbacks (HMAC-SHA256)             $(v C2WBANK_HMAC_SECRET)

== USSD gateways (basic auth, user ussd-<mno>, POST $base/ussd/<mno>)
   gp $(v USSD_GP_GATEWAY_SECRET) | robi $(v USSD_ROBI_GATEWAY_SECRET) | bl $(v USSD_BL_GATEWAY_SECRET) | teletalk $(v USSD_TELETALK_GATEWAY_SECRET)
EOF
