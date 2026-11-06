# APISIX edge config (spec 8.3, WP09, ticket 13): static checks on what deploy/apisix/apply.sh
# would send to the Admin API (APISIX_DRY_RUN), and on the body schemas the services derive from
# their OpenAPI contracts (body schemas are checked in the service, never at the edge: M3 gate).
# The live gateway tests are scripts/edge-test.sh (make edge-test).

APPLY="$REPO_DIR/deploy/apisix/apply.sh"

# render [DIR]: dry-run apply.sh over DIR (default: the repo's deploy/apisix) into ./out;
# one file per Admin API object at out/<kind>/<id>.json.
render() {
  local dir="${1:-$REPO_DIR/deploy/apisix}"
  rm -rf out
  # the MNO USSD consumers' passwords and allow-lists (generated .env / compose in the stack);
  # a caller may override or empty any of them
  OUT="$(USSD_GP_GATEWAY_SECRET="${USSD_GP_GATEWAY_SECRET-fakeGpSecret}" \
    USSD_ROBI_GATEWAY_SECRET="${USSD_ROBI_GATEWAY_SECRET-fakeRobiSecret}" \
    USSD_BL_GATEWAY_SECRET="${USSD_BL_GATEWAY_SECRET-fakeBlSecret}" \
    USSD_TELETALK_GATEWAY_SECRET="${USSD_TELETALK_GATEWAY_SECRET-fakeTeletalkSecret}" \
    USSD_GP_ALLOWED_CIDRS="${USSD_GP_ALLOWED_CIDRS-10.1.0.0/16}" \
    USSD_ROBI_ALLOWED_CIDRS="${USSD_ROBI_ALLOWED_CIDRS-10.2.0.0/16,192.168.0.0/16}" \
    USSD_BL_ALLOWED_CIDRS="${USSD_BL_ALLOWED_CIDRS-10.3.0.0/16}" \
    USSD_TELETALK_ALLOWED_CIDRS="${USSD_TELETALK_ALLOWED_CIDRS-10.4.0.0/16}" \
    MW_PUBLIC_HOST="${MW_PUBLIC_HOST-localhost}" \
    APISIX_DRY_RUN="$PWD/out" APISIX_CONFIG_DIR="$dir" bash "$APPLY" 2>&1)"
}

# obj KIND ID: the rendered object
obj() { cat "out/$1/$2.json"; }

test_dry_run_renders_every_object_as_valid_json() {
  render || fail "dry run failed"
  local f n=0
  for f in out/*/*.json; do
    [ -f "$f" ] || continue
    jq -e . "$f" >/dev/null || fail "$f is not valid JSON: $(head -c 300 "$f")"
    n=$((n + 1))
  done
  [ -f out/routes/app-v1.json ] || fail "no app-v1 route rendered (have: $(ls out/*))"
  [ -f out/routes/healthz.json ] || fail "no healthz route rendered"
  [ -d out/ssls ] && fail "dry run must not touch the edge certificate"
  return 0
}

test_every_desc_fits_the_admin_api_limit() {
  # The Admin API refuses an object whose desc is over 256 characters (apisix-init then fails).
  local f long
  for f in $(find "$REPO_DIR/deploy/apisix" -name '*.json'); do
    long="$(jq -r '[.. | objects | select(has("desc")) | .desc | select(length > 256)] | length' "$f")"
    [ "$long" = "0" ] || fail "$f has a desc over 256 characters"
  done
  return 0
}

test_lua_includes_are_inlined_verbatim() {
  mkdir -p fx/routes fx/lua
  printf 'return function(conf, ctx)\n\t-- "quoted" \\ back\\slash & amp\n  return nil\nend\n' >fx/lua/f.lua
  cat >fx/routes/r.json <<'EOF'
{ "uri": "/x", "plugins": {
  "serverless-pre-function": { "phase": "rewrite", "functions": ["@lua:lua/f.lua"] } } }
EOF
  render "$PWD/fx" || fail "dry run failed"
  jq -e . out/routes/r.json >/dev/null || fail "not JSON: $(cat out/routes/r.json)"
  [ "$(jq -r '.plugins["serverless-pre-function"].functions[0]' out/routes/r.json)" = "$(cat fx/lua/f.lua)" ] \
    || fail "lua not inlined verbatim: $(jq '.plugins["serverless-pre-function"].functions[0]' out/routes/r.json)"
}

# "@list:<NAME>": a comma-separated variable (CIDRs) as a JSON array of strings
test_a_list_include_renders_a_json_array_and_refuses_odd_entries() {
  mkdir -p fx/routes
  printf '{ "uri": "/x", "plugins": { "ip-restriction": { "whitelist": "@list:MW_T_CIDRS" } } }\n' >fx/routes/r.json
  MW_T_CIDRS='10.0.0.0/8, 192.168.1.7,::1/128' render "$PWD/fx" || fail "dry run failed"
  [ "$(jq -c '.plugins["ip-restriction"].whitelist' out/routes/r.json)" = '["10.0.0.0/8","192.168.1.7","::1/128"]' ] \
    || fail "list not rendered: $(cat out/routes/r.json)"
  if MW_T_CIDRS='10.0.0.0/8,"x"' render "$PWD/fx"; then fail "accepted a list entry that is not an address"; fi
  assert_out_contains "MW_T_CIDRS"
  if MW_T_CIDRS= render "$PWD/fx"; then fail "accepted an empty list"; fi
  assert_out_contains "MW_T_CIDRS is not set"
}

# A file removed from routes/ or consumers/ is listed in retired.txt, so apply.sh deletes its
# object instead of leaving it in etcd (the single ussd-http route became one route per MNO).
test_retired_objects_are_deleted() {
  render || fail "dry run failed: $OUT"
  assert_out_contains "DELETE /apisix/admin/routes/ussd-http"
  [ -f out/routes/ussd-http.json ] && fail "retired route still applied"
  return 0
}

test_a_missing_include_fails_the_apply() {
  mkdir -p fx/routes
  printf '{ "uri": "/x", "plugins": { "serverless-pre-function": { "functions": ["@lua:lua/nope.lua"] } } }\n' >fx/routes/r.json
  if render "$PWD/fx"; then fail "dry run passed with a missing include"; fi
  assert_out_contains "nope.lua"
}

# ---- test-only objects (routes-test/, consumers-test/): only with MW_E2E_ROUTES=1 ----

# The ops realm's direct-grant token endpoint (ticket 37 e2e operators) is never public: test-only,
# key-auth for mw_e2e from private networks, a strict quota per source address (security review).
test_the_ops_token_route_is_test_only_behind_key_auth_and_a_strict_quota() {
  [ -f "$REPO_DIR/deploy/apisix/routes/auth-ops-token.json" ] && fail "auth-ops-token must not be a regular route"
  MW_E2E_ROUTES= MW_E2E_API_KEY= render || fail "dry run failed: $OUT"
  [ -f out/routes/auth-ops-token.json ] && fail "ops token route applied without MW_E2E_ROUTES=1"
  assert_out_contains "DELETE /apisix/admin/routes/auth-ops-token"
  MW_E2E_ROUTES=1 MW_E2E_API_KEY=k3yFromDotEnv CHECKOUT_DEMO_MERCHANT_SECRET=s3cretFromDotEnv render || fail "dry run failed: $OUT"
  local r=out/routes/auth-ops-token.json
  [ -f "$r" ] || fail "ops token route not applied with MW_E2E_ROUTES=1"
  jq -e '.plugins["key-auth"] and .plugins["consumer-restriction"].whitelist == ["mw_e2e"]' "$r" >/dev/null \
    || fail "ops token route lacks key-auth for mw_e2e: $(jq -c .plugins "$r")"
  jq -e '.plugins["ip-restriction"].whitelist | index("0.0.0.0/0") | not' "$r" >/dev/null \
    || fail "ops token route open to every address"
  jq -e '.plugins["limit-count"] | .key == "remote_addr" and .count <= 10 and .rejected_code == 429' "$r" >/dev/null \
    || fail "ops token route lacks a strict per-address quota: $(jq -c '.plugins["limit-count"]' "$r")"
}

test_test_only_objects_are_not_applied_by_default_and_removed_if_present() {
  MW_E2E_ROUTES= MW_E2E_API_KEY= render || fail "dry run failed: $OUT"
  [ -f out/routes/test-core-mock.json ] && fail "test route applied without MW_E2E_ROUTES=1"
  [ -f out/consumers/mw_e2e.json ] && fail "test consumer applied without MW_E2E_ROUTES=1"
  [ -f out/consumers/merchant_demo.json ] && fail "test consumer applied without MW_E2E_ROUTES=1"
  assert_out_contains "DELETE /apisix/admin/routes/test-core-mock"
  assert_out_contains "DELETE /apisix/admin/consumers/mw_e2e"
  [ -f out/routes/app-v1.json ] || fail "regular routes must still be applied"
}

test_e2e_flag_applies_the_test_route_behind_key_auth_from_private_networks() {
  MW_E2E_ROUTES=1 MW_E2E_API_KEY=k3yFromDotEnv CHECKOUT_DEMO_MERCHANT_SECRET=s3cretFromDotEnv render || fail "dry run failed: $OUT"
  local r=out/routes/test-core-mock.json c=out/consumers/mw_e2e.json
  [ -f "$r" ] || fail "test route not applied with MW_E2E_ROUTES=1 (have: $(ls out/*))"
  jq -e '.plugins["key-auth"] and .plugins["consumer-restriction"].whitelist == ["mw_e2e"]' "$r" >/dev/null \
    || fail "test route lacks key-auth for mw_e2e: $(jq -c .plugins "$r")"
  jq -e '.plugins["ip-restriction"].whitelist | index("0.0.0.0/0") | not' "$r" >/dev/null \
    || fail "test route open to every address"
  [ "$(jq -r '.plugins["key-auth"].key' "$c")" = k3yFromDotEnv ] || fail "consumer key not from MW_E2E_API_KEY: $(cat "$c")"
  assert_out_lacks "k3yFromDotEnv"
  assert_out_lacks "DELETE /apisix/admin/routes/test-core-mock"
}

# Ticket 58: the audit test API of mw-audit-dw is test-only, GET only, behind the same gate.
test_the_audit_dw_test_route_is_test_only_behind_key_auth() {
  MW_E2E_ROUTES= MW_E2E_API_KEY= render || fail "dry run failed: $OUT"
  [ -f out/routes/test-audit-dw.json ] && fail "audit test route applied without MW_E2E_ROUTES=1"
  assert_out_contains "DELETE /apisix/admin/routes/test-audit-dw"
  MW_E2E_ROUTES=1 MW_E2E_API_KEY=k3yFromDotEnv CHECKOUT_DEMO_MERCHANT_SECRET=s3cretFromDotEnv render || fail "dry run failed: $OUT"
  local r=out/routes/test-audit-dw.json
  [ -f "$r" ] || fail "audit test route not applied with MW_E2E_ROUTES=1"
  jq -e '.plugins["key-auth"] and .plugins["consumer-restriction"].whitelist == ["mw_e2e"]' "$r" >/dev/null \
    || fail "audit test route lacks key-auth for mw_e2e: $(jq -c .plugins "$r")"
  jq -e '.plugins["ip-restriction"].whitelist | index("0.0.0.0/0") | not' "$r" >/dev/null \
    || fail "audit test route open to every address"
  jq -e '.methods == ["GET"] and .uri == "/test/audit-dw/v1/*" and (.upstream.nodes | has("mw-audit-dw:8080"))' "$r" >/dev/null \
    || fail "audit test route must be GET /test/audit-dw/v1/* to mw-audit-dw: $(jq -c '{methods,uri,upstream}' "$r")"
}

# Ticket 58: the orchestrator's test API (publish a REVERSAL, idempotency lookup) is test-only,
# behind the same gate, and rewrites /test/orchestrator/v1/* to the service's /test/v1/*.
test_the_orchestrator_test_route_is_test_only_behind_key_auth() {
  MW_E2E_ROUTES= MW_E2E_API_KEY= render || fail "dry run failed: $OUT"
  [ -f out/routes/test-orchestrator.json ] && fail "orchestrator test route applied without MW_E2E_ROUTES=1"
  assert_out_contains "DELETE /apisix/admin/routes/test-orchestrator"
  MW_E2E_ROUTES=1 MW_E2E_API_KEY=k3yFromDotEnv CHECKOUT_DEMO_MERCHANT_SECRET=s3cretFromDotEnv render || fail "dry run failed: $OUT"
  local r=out/routes/test-orchestrator.json
  [ -f "$r" ] || fail "orchestrator test route not applied with MW_E2E_ROUTES=1"
  jq -e '.plugins["key-auth"] and .plugins["consumer-restriction"].whitelist == ["mw_e2e"]' "$r" >/dev/null \
    || fail "orchestrator test route lacks key-auth for mw_e2e: $(jq -c .plugins "$r")"
  jq -e '.plugins["ip-restriction"].whitelist | index("0.0.0.0/0") | not' "$r" >/dev/null \
    || fail "orchestrator test route open to every address"
  jq -e '.uri == "/test/orchestrator/v1/*" and (.upstream.nodes | has("mw-orchestrator:8080"))
    and .plugins["proxy-rewrite"].regex_uri == ["^/test/orchestrator(/v1/.*)$", "/test$1"]' "$r" >/dev/null \
    || fail "orchestrator test route must map /test/orchestrator/v1/* to mw-orchestrator /test/v1/*: $(jq -c '{uri,upstream,plugins}' "$r")"
}

# Ticket 31: the dev checkout merchant is a test-only hmac-auth consumer whose secret comes from
# the generated .env; the merchant API routes authenticate with hmac-auth and forward the
# consumer name, the hosted page route has no auth plugin but a quota per source address.
test_checkout_routes_use_hmac_auth_and_the_dev_merchant_is_test_only() {
  MW_E2E_ROUTES=1 MW_E2E_API_KEY=k CHECKOUT_DEMO_MERCHANT_SECRET=s3cretFromDotEnv render || fail "dry run failed: $OUT"
  local c=out/consumers/merchant_demo.json r
  [ "$(jq -r '.plugins["hmac-auth"].secret_key' "$c")" = s3cretFromDotEnv ] || fail "merchant secret not from CHECKOUT_DEMO_MERCHANT_SECRET: $(cat "$c")"
  assert_out_lacks "s3cretFromDotEnv"
  for r in checkout-v1-sessions checkout-v1-session-get; do
    jq -e '.plugins["hmac-auth"].allowed_algorithms == ["hmac-sha256"] and (.plugins["hmac-auth"].signed_headers | index("@request-target") and index("date"))' "out/routes/$r.json" >/dev/null \
      || fail "$r: hmac-auth must sign method, path and Date: $(jq -c '.plugins["hmac-auth"]' "out/routes/$r.json")"
    jq -e '.plugins["proxy-rewrite"].headers.set["X-Mw-Merchant-Id"] == "$consumer_name" and .plugins["limit-count"].key == "consumer_name"' "out/routes/$r.json" >/dev/null \
      || fail "$r: consumer name not forwarded / not the quota key"
  done
  jq -e '.plugins["hmac-auth"].validate_request_body == true and (.plugins["hmac-auth"].signed_headers | index("digest"))
    and .plugins["client-control"].max_body_size > 0' \
    out/routes/checkout-v1-sessions.json >/dev/null || fail "create session: body digest not checked or no size cap"
  jq -e '(.plugins | keys) == ["client-control", "limit-count"] and .plugins["limit-count"].key == "remote_addr"' out/routes/checkout-pay.json >/dev/null \
    || fail "checkout-pay: $(jq -c '.plugins | keys' out/routes/checkout-pay.json)"
  MW_E2E_ROUTES= MW_E2E_API_KEY= render || fail "dry run failed: $OUT"
  assert_out_contains "DELETE /apisix/admin/consumers/merchant_demo"
}

test_e2e_flag_without_a_key_fails() {
  if MW_E2E_ROUTES=1 MW_E2E_API_KEY= render; then fail "applied the test route without a key"; fi
  assert_out_contains "MW_E2E_API_KEY"
}

# ---- body schemas: in the services, never at the edge (M3 gate decision, spec 8.3) ----

# APISIX request-validation forwards a validated JSON body re-encoded, so a service could not
# verify a signature over the bytes the client sent; each service checks its own derived schema.
test_no_route_validates_bodies_at_the_edge() {
  MW_E2E_ROUTES=1 MW_E2E_API_KEY=k CHECKOUT_DEMO_MERCHANT_SECRET=s render || fail "dry run failed: $OUT"
  local f
  for f in out/routes/*.json; do
    jq -e '.plugins // {} | has("request-validation") | not' "$f" >/dev/null || fail "$f still has request-validation"
  done
  [ -e "$REPO_DIR/deploy/apisix/schemas" ] && fail "deploy/apisix/schemas is back"
  [ -e "$REPO_DIR/scripts/apisix-schemas.sh" ] && fail "scripts/apisix-schemas.sh is back"
  if grep -n '@json:' "$APPLY"; then fail "apply.sh still inlines @json schemas"; fi
  return 0
}

# fixture_schemas: a root with contracts/ and every service's derived-schemas/ copied from the repo
fixture_schemas() {
  local d m
  cp -R "$REPO_DIR/contracts/." contracts/
  for d in "$REPO_DIR"/services/*/src/main/resources/derived-schemas; do
    [ -d "$d" ] || continue
    m="$(basename "$(dirname "$(dirname "$(dirname "$(dirname "$d")")")")")"
    mkdir -p "services/$m/src/main/resources"
    cp -R "$d" "services/$m/src/main/resources/derived-schemas"
  done
}

schemas() { OUT="$(MW_ROOT="$PWD" "$REPO_DIR/scripts/derived-schemas.sh" "$@" 2>&1)"; }

test_repo_derived_schemas_match_their_contracts() {
  fixture_schemas
  schemas --check || fail "derived schemas drifted from contracts/"
  # every file on disk is checked (the count grows with each route, so it is counted, not fixed)
  local n
  n="$(ls services/*/src/main/resources/derived-schemas/*.json 2>/dev/null | wc -l | tr -d ' ')"
  [ "$n" -gt 0 ] || fail "no derived schemas found in services/"
  assert_out_contains "$n derived schema(s)"
}

# Review A6: every public JSON body behind an APISIX route has a derived schema in its service
# (partner callbacks excluded: the adapters verify their partner-specific wire format).
test_every_public_json_body_has_a_derived_schema_in_its_service() {
  local row file src req
  for row in \
    "mw-app-gateway/app-v1-transaction-request.json|api/mw-app-gateway/app.v1.openapi.yaml#/components/schemas/TransactionRequest|txnType" \
    "mw-app-gateway/app-v1-device-otp-request.json|api/mw-app-gateway/app.v1.openapi.yaml#/components/schemas/DeviceOtpRequest|" \
    "mw-app-gateway/app-v1-device-binding-request.json|api/mw-app-gateway/app.v1.openapi.yaml#/components/schemas/DeviceBindingRequest|otp" \
    "mw-app-gateway/app-v1-fetch-bill-request.json|api/mw-app-gateway/app.v1.openapi.yaml#/components/schemas/FetchBillRequest|billNumber" \
    "mw-checkout/checkout-v1-create-session.json|api/mw-checkout/checkout.v1.openapi.yaml#/components/schemas/CreateSessionRequest|orderId" \
    "mw-checkout/checkout-pay-v1-otp.json|api/mw-checkout/checkout.v1.openapi.yaml#/components/schemas/OtpRequest|wallet" \
    "mw-checkout/checkout-pay-v1-confirm.json|api/mw-checkout/checkout.v1.openapi.yaml#/components/schemas/ConfirmRequest|encryptedPin" \
    "mw-portal-api/portal-org-v1-reject-request.json|api/mw-portal-api/portal-org.v1.openapi.yaml#/paths/~1portal~1org~1v1~1bulk~1batches~1{batchId}~1reject/post/requestBody/content/application~1json/schema|reason" \
    "mw-ussd/ussd-http-v1-step.json|api/mw-ussd/ussd-http.v1.openapi.yaml#/components/schemas/UssdStep|sessionId"; do
    IFS='|' read -r file src req <<<"$row"
    file="$REPO_DIR/services/${file%%/*}/src/main/resources/derived-schemas/${file#*/}"
    [ -f "$file" ] || fail "no $file"
    jq -e --arg s "$src" '."x-mw-derived-from" == $s and .type == "object"' "$file" >/dev/null || fail "$file: not derived from $src"
    if [ -n "$req" ]; then
      jq -e --arg r "$req" '.required | index($r) != null' "$file" >/dev/null || fail "$file: $req not required"
    fi
    if jq -e '[.. | objects | select(has("pattern") or has("format") or has("$ref"))] | length > 0' "$file" >/dev/null; then
      fail "pattern/format/\$ref left in $file"
    fi
  done
}

test_app_submit_schema_is_the_openapi_transaction_request_minus_value_echoing_keywords() {
  local s="$REPO_DIR/services/mw-app-gateway/src/main/resources/derived-schemas/app-v1-transaction-request.json"
  [ -f "$s" ] || fail "no $s"
  jq -e '."x-mw-derived-from" == "api/mw-app-gateway/app.v1.openapi.yaml#/components/schemas/TransactionRequest"' "$s" >/dev/null \
    || fail "source not recorded"
  jq -e '.required == ["txnType", "amount", "encryptedPin"]
    and (.properties.txnType.enum | index("P2P") != null and index("TOPUP") != null)
    and .properties.partner.additionalProperties == false
    and .properties.partner.properties.partnerId.enum == ["gp", "robi", "banglalink", "teletalk", "c2wbank"]
    and .properties.debitParty.required == ["type", "id"] and .properties.debitParty.additionalProperties == false
    and .properties.amount.required == ["valueMinor", "currency"]
    and .properties.amount.additionalProperties == false
    and .properties.amount.properties.valueMinor.minimum == 1
    and .properties.creditParty.required == ["id"]
    and .properties.encryptedPin.properties.ciphertext.maxLength == 4096' "$s" >/dev/null \
    || fail "not the TransactionRequest shape: $(jq -c . "$s")"
  # propertyNames becomes a closed properties list
  jq -e '.properties.attributes.properties | keys == ["reference"]' "$s" >/dev/null || fail "attributes allow-list lost"
  jq -e '.properties.attributes.additionalProperties == false' "$s" >/dev/null || fail "attributes not closed"
  # a validator message for a failed pattern or format repeats the value (an MSISDN): no pattern, no format
  if jq -e '[.. | objects | select(has("pattern") or has("format") or has("propertyNames") or has("$ref"))] | length > 0' "$s" >/dev/null; then
    fail "pattern/format/propertyNames/\$ref left in $s"
  fi
}

test_checkout_create_session_schema_is_derived_in_the_service() {
  local s="$REPO_DIR/services/mw-checkout/src/main/resources/derived-schemas/checkout-v1-create-session.json"
  [ -f "$s" ] || fail "no $s"
  jq -e '."x-mw-derived-from" == "api/mw-checkout/checkout.v1.openapi.yaml#/components/schemas/CreateSessionRequest"
    and (.required | index("orderId") != null and index("ipnUrl") != null)
    and .properties.amount.properties.currency.enum == ["BDT"]' "$s" >/dev/null \
    || fail "not the CreateSessionRequest shape: $(jq -c . "$s")"
}

test_a_contract_change_makes_the_check_fail_until_written() {
  fixture_schemas
  sed -i.bak 's/required: \[txnType, amount, encryptedPin\]/required: [txnType, amount, encryptedPin, locale]/' \
    contracts/api/mw-app-gateway/app.v1.openapi.yaml
  grep -q 'encryptedPin, locale]' contracts/api/mw-app-gateway/app.v1.openapi.yaml || fail "fixture edit did not apply"
  if schemas --check; then fail "check passed on a stale schema"; fi
  assert_out_contains "app-v1-transaction-request.json"
  schemas --write || fail "write failed"
  jq -e '.required | index("locale")' services/mw-app-gateway/src/main/resources/derived-schemas/app-v1-transaction-request.json >/dev/null || fail "not rewritten"
  schemas --check || fail "check fails after write"
}

test_an_unsupported_keyword_fails_the_derivation() {
  fixture_schemas
  sed -i.bak 's/maxProperties: 20/maxProperties: 20, patternProperties: { "^x": { type: string } }/' contracts/api/mw-app-gateway/app.v1.openapi.yaml
  grep -q 'patternProperties' contracts/api/mw-app-gateway/app.v1.openapi.yaml || fail "fixture edit did not apply"
  if schemas --check; then fail "unsupported keyword accepted"; fi
  assert_out_contains "patternProperties"
}

test_contracts_lint_runs_the_derived_schema_check() {
  fixture_schemas
  printf '{ "x-mw-derived-from": "api/mw-checkout/checkout.v1.openapi.yaml#/components/schemas/CreateSessionRequest" }\n' \
    >services/mw-checkout/src/main/resources/derived-schemas/checkout-v1-create-session.json
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "checkout-v1-create-session.json"
}

# ---- USSD edge (spec 8.2, M3 gate): one consumer per MNO bound to its own path ----

test_each_mno_has_its_own_ussd_route_bound_to_its_consumer() {
  render || fail "dry run failed: $OUT"
  local mno r c
  for mno in gp robi bl teletalk; do
    r="out/routes/ussd-http-$mno.json"; c="out/consumers/ussd_$mno.json"
    [ -f "$r" ] || fail "no route ussd-http-$mno (have: $(ls out/routes))"
    jq -e --arg m "$mno" '.uri == "/ussd/\($m)" and .methods == ["POST"]
      and .plugins["basic-auth"].hide_credentials == true
      and .plugins["consumer-restriction"].type == "consumer_name"
      and .plugins["consumer-restriction"].whitelist == ["ussd_\($m)"]
      and .plugins["consumer-restriction"].rejected_code == 403
      and .plugins["client-control"].max_body_size == 4096
      and (.upstream.nodes | has("mw-ussd:8080"))' "$r" >/dev/null \
      || fail "$r: $(jq -c '{uri, plugins: (.plugins | keys)}' "$r")"
    jq -e --arg m "$mno" '.username == "ussd_\($m)" and .plugins["basic-auth"].username == "ussd-\($m)"
      and (.plugins["basic-auth"].password | length > 0)' "$c" >/dev/null || fail "$c: $(jq -c '.plugins | keys' "$c")"
  done
  [ "$(jq -r '.plugins["basic-auth"].password' out/consumers/ussd_robi.json)" = fakeRobiSecret ] \
    || fail "robi password not from USSD_ROBI_GATEWAY_SECRET"
  assert_out_lacks fakeRobiSecret
}

test_each_ussd_route_takes_its_ip_allow_list_from_config() {
  render || fail "dry run failed: $OUT"
  [ "$(jq -c '.plugins["ip-restriction"].whitelist' out/routes/ussd-http-robi.json)" = '["10.2.0.0/16","192.168.0.0/16"]' ] \
    || fail "robi allow-list: $(jq -c '.plugins["ip-restriction"]' out/routes/ussd-http-robi.json)"
  [ "$(jq -c '.plugins["ip-restriction"].whitelist' out/routes/ussd-http-gp.json)" = '["10.1.0.0/16"]' ] || fail "gp allow-list"
  # no fallback in the platform compose file: an unset list stops the stack (and the apply),
  # never opens the route; the dev ranges come from the generated .env (scripts/gen-dev-env.sh)
  local mno
  for mno in GP ROBI BL TELETALK; do
    grep -qE "USSD_${mno}_ALLOWED_CIDRS: [\$][{]USSD_${mno}_ALLOWED_CIDRS:[?]" "$REPO_DIR/deploy/docker-compose.yml" \
      || fail "USSD_${mno}_ALLOWED_CIDRS is not required in the platform compose file"
    grep -q "USSD_${mno}_ALLOWED_CIDRS" "$REPO_DIR/scripts/gen-dev-env.sh" || fail "USSD_${mno}_ALLOWED_CIDRS not generated"
  done
}

test_each_ussd_route_limits_each_msisdn_without_storing_it() {
  render || fail "dry run failed: $OUT"
  local r=out/routes/ussd-http-bl.json fn
  jq -e '.plugins["limit-count"] | .key_type == "var" and .key == "mw_ussd_msisdn" and .policy == "redis"
    and .group == "ussd-http-bl" and .count > 0 and .time_window > 0' "$r" >/dev/null \
    || fail "limit-count: $(jq -c '.plugins["limit-count"]' "$r")"
  fn="$(jq -r '.plugins["serverless-pre-function"] | select(.phase == "access") | .functions[0]' "$r")"
  printf '%s' "$fn" | grep -q 'ctx.var.mw_ussd_msisdn' || fail "no per-MSISDN key set"
  printf '%s' "$fn" | grep -q 'resty.sha256' || fail "the MSISDN is not hashed before it becomes a Valkey key"
}

test_a_missing_ussd_allow_list_fails_the_apply() {
  if USSD_TELETALK_ALLOWED_CIDRS= render; then fail "applied the teletalk route without an allow-list"; fi
  assert_out_contains "USSD_TELETALK_ALLOWED_CIDRS"
}

test_a_missing_ussd_secret_fails_the_apply() {
  if USSD_BL_GATEWAY_SECRET= render; then fail "applied the bl consumer without a password"; fi
  assert_out_contains "USSD_BL_GATEWAY_SECRET"
}

test_ussd_secrets_are_generated_and_passed_to_apisix_and_the_simulator() {
  local mno v
  for mno in GP ROBI BL TELETALK; do
    v="USSD_${mno}_GATEWAY_SECRET"
    grep -q "$v" "$REPO_DIR/scripts/gen-dev-env.sh" || fail "$v not generated"
    grep -A40 -E '^  apisix-init:' "$REPO_DIR/deploy/docker-compose.yml" | grep -q "$v: " || fail "$v not passed to apisix-init"
    grep -A20 -E "^  mw-simulators-init:" "$REPO_DIR/deploy/docker-compose.yml" | grep -q "$v: " || fail "$v not passed to mw-simulators-init"
  done
}

# ---- make edge-test (scripts/edge-test.sh): the live tests need a running stack ----

test_edge_test_fails_clearly_without_a_stack() {
  assert_fails mw_make "$PWD" edge-test
  assert_out_contains "deploy/docker-compose.yml"
  mkdir -p deploy .bin
  touch deploy/docker-compose.yml
  assert_fails mw_make "$PWD" edge-test
  assert_out_contains "make up"
  printf 'APISIX_ADMIN_KEY=t\n' >deploy/.env
  # docker stub: the project resolves, but nothing publishes apisix:9443
  printf '#!/bin/sh\ncase "$*" in *" config "*) echo "{\\"name\\":\\"mw\\"}" ;; esac\nexit 0\n' >.bin/docker
  chmod +x .bin/docker
  OUT="$(PATH="$PWD/.bin:$PATH" make -s -f "$REPO_DIR/Makefile" MW_ROOT="$PWD" SELFTEST=0 edge-test 2>&1)" && fail "passed without apisix"
  assert_out_contains "apisix is not running"
}

# ---- edge policies (spec 8.3) as rendered for the Admin API ----

# all_plugins: "<object> <plugin>" for every plugin of every rendered object
all_plugins() { for f in out/*/*.json; do jq -r --arg o "${f#out/}" '.plugins // {} | keys[] | "\($o) \(.)"' "$f"; done; }

test_a_global_rule_sets_the_correlation_id_after_stripping_internal_headers() {
  render || fail "dry run failed"
  local g
  g="$(cat out/global_rules/*.json | jq -s '[.[] | .plugins] | add')"
  printf '%s' "$g" | jq -e '.["request-id"] | .header_name == "X-Correlation-Id" and .include_in_response == true' >/dev/null \
    || fail "no global request-id on X-Correlation-Id: $g"
  # the hygiene runs right before request-id reads the header: its _meta.pre_function
  printf '%s' "$g" | jq -r '.["request-id"]._meta.pre_function' | grep -q 'x-mw-' \
    || fail "request-id's pre_function does not strip X-Mw-* headers"
  printf '%s' "$g" | jq -r '.["request-id"]._meta.pre_function' | grep -q 'x-correlation-id' \
    || fail "request-id's pre_function does not drop unsafe correlation IDs"
  printf '%s' "$g" | jq -r '.["request-id"]._meta.pre_function' | grep -qF '[0-9]{6}' \
    || fail "request-id's pre_function does not drop correlation IDs with a run of 6+ digits"
  # APISIX refuses a plugin used by two global rules: keep the edge in one rule
  [ "$(ls out/global_rules | wc -l | tr -d ' ')" = 1 ] || fail "more than one global rule: $(ls out/global_rules)"
}

test_a_global_rule_turns_edge_errors_into_the_error_envelope() {
  render || fail "dry run failed"
  local fns
  phase_fns() { cat out/global_rules/*.json | jq -r --arg p "$1" '.. | objects | select(.phase == $p) | .functions[]'; }
  phase_fns header_filter | grep -q 'correlationId' || fail "no header_filter building the envelope"
  phase_fns body_filter | grep -q 'ngx.arg\[1\]' || fail "no body_filter replacing the body"
  fns="$(phase_fns header_filter)"
  for code in MW-VAL-001 MW-AUTH-001 MW-AUTH-002 MW-VAL-BODY_TOO_LARGE MW-SYS-RATE_LIMITED; do
    printf '%s\n' "$fns" | grep -q -- "$code" || fail "no $code in the envelope functions"
  done
}

test_app_route_limits_each_consumer_with_counters_in_valkey() {
  render || fail "dry run failed"
  obj routes app-v1 | jq -e '.plugins["limit-count"] | .policy == "redis" and .redis_host == "valkey"
    and .redis_password == "$env://VALKEY_PASSWORD" and .rejected_code == 429
    and .key_type == "var" and .key == "mw_consumer" and .count > 0 and .time_window > 0' >/dev/null \
    || fail "limit-count: $(obj routes app-v1 | jq -c '.plugins["limit-count"]')"
  # the per-consumer key is the verified token's subject, set before limit-count (access, 1002)
  obj routes app-v1 | jq -e '.plugins["serverless-pre-function"] | .phase == "access"' >/dev/null || fail "no access-phase consumer key"
  obj routes app-v1 | jq -r '.plugins["serverless-pre-function"].functions[]' | grep -q 'ctx.var.mw_consumer' \
    || fail "consumer key not set from the token"
}

# Ticket 50: limit-count stays fail-open (allow_degradation) when Valkey is down, and every
# request it let through unlimited is logged with one stable error line that smoke and alerts grep.
test_rate_limited_routes_fail_open_and_log_a_stable_degradation_line() {
  render || fail "dry run failed"
  local f n=0 fns
  for f in out/routes/*.json; do
    jq -e '.plugins["limit-count"]' "$f" >/dev/null || continue
    n=$((n + 1))
    jq -e '.plugins["limit-count"] | .allow_degradation == true and .show_limit_quota_header == true' "$f" >/dev/null \
      || fail "$f: limit-count must be fail-open and send its quota headers: $(jq -c '.plugins["limit-count"]' "$f")"
  done
  [ "$n" -gt 0 ] || fail "no rate-limited route"
  fns="$(cat out/global_rules/*.json | jq -r '.. | objects | select(.phase == "header_filter") | .functions[]')"
  printf '%s\n' "$fns" | grep -q 'mw.edge.rate_limit_degraded' || fail "no header_filter logging mw.edge.rate_limit_degraded"
  printf '%s\n' "$fns" | grep -q 'X-RateLimit-Limit' || fail "degradation not detected from the missing quota header"
  grep -q 'mw.edge.rate_limit_degraded' "$REPO_DIR/scripts/smoke.sh" || fail "make smoke does not look for the degradation line"
  grep -q 'mw.edge.rate_limit_degraded' "$REPO_DIR/deploy/apisix/README.md" || fail "degradation alert not documented"
}

test_app_route_refuses_expired_tokens_without_a_long_grace() {
  render || fail "dry run failed"
  # openid-connect accepts a token until exp + iat_slack, 120 s unless set
  obj routes app-v1 | jq -e '.plugins["openid-connect"].iat_slack | . != null and . <= 30' >/dev/null \
    || fail "iat_slack: $(obj routes app-v1 | jq -c '.plugins["openid-connect"].iat_slack')"
}

# Ticket 29: request-validation forwards a validated JSON body re-encoded, so mw-app-gateway could
# not verify the device signature over the bytes the app sent. The submit schema is checked by the
# gateway on those bytes (derived copy in the module, kept in sync by derived-schemas.sh); the edge
# keeps the body cap and must not rewrite the body.
test_app_route_leaves_the_signed_body_untouched() {
  render || fail "dry run failed"
  local r
  r="$(obj routes app-v1)"
  printf '%s' "$r" | jq -e '.plugins | has("request-validation") | not' >/dev/null \
    || fail "request-validation re-encodes the body the device signed"
  printf '%s' "$r" | jq -e '.plugins | (has("body-transformer") or has("proxy-rewrite") and (.["proxy-rewrite"] | has("body"))) | not' >/dev/null \
    || fail "a plugin rewrites the app body"
  printf '%s' "$r" | jq -e '.plugins["client-control"].max_body_size > 0' >/dev/null || fail "no body size cap"
  [ -f "$REPO_DIR/services/mw-app-gateway/src/main/resources/derived-schemas/app-v1-transaction-request.json" ] || fail "mw-app-gateway has no derived submit schema"
}

test_the_edge_never_logs_bodies() {
  render || fail "dry run failed"
  if all_plugins | grep -E -- '-logger$|^[^ ]+ (syslog|kafka-logger|http-logger|file-logger)$'; then fail "a logger plugin is configured"; fi
  if cat out/*/*.json | jq -e '[.. | objects | to_entries[] | select(.key | test("include_(req|resp)_body"))] | length > 0' >/dev/null; then
    fail "body logging turned on"
  fi
  if grep -nE 'request_body|resp_body' "$REPO_DIR/deploy/apisix/config.yaml"; then fail "access log format with bodies"; fi
  return 0
}

# The nginx access log masks MSISDNs (root CLAUDE.md "Masking", M6 gate): the format logs only the
# masked copies of the request URI, referrer and upstream URI (query string dropped), and the
# map chain in http_configuration_snippet (emulated here with Python's re, PCRE-compatible for
# these patterns) turns a path MSISDN into 0171****789.
test_the_access_log_masks_msisdns_and_drops_the_query_string() {
  command -v python3 >/dev/null || fail "python3 is needed for this check"
  OUT="$(python3 - "$REPO_DIR/deploy/apisix/config.yaml" <<'PY' 2>&1
import re, sys
text = open(sys.argv[1]).read()
fmt = re.search(r"^    access_log_format: '(.*)'$", text, re.M)
assert fmt, "no nginx_config.http.access_log_format"
fmt = fmt.group(1)
for raw in ("$request", "$request_uri", "$uri", "$http_referer", "$upstream_uri", "$args", "$query_string", "$document_uri"):
    assert not re.search(re.escape(raw) + r"(?![A-Za-z0-9_])", fmt), "format logs raw " + raw
assert "$arg_" not in fmt, "format logs a query argument"
for want in ("$request_method $mw_log_uri $server_protocol", '"$mw_log_referer"', "$upstream_host$mw_log_upstream_uri", "$status", "$remote_addr", "$apisix_request_id", "$upstream_status", "$request_time"):
    assert want in fmt, "format lacks " + want
snip = re.search(r"^  http_configuration_snippet: \|\n((?:    .*\n)+)", text, re.M)
assert snip, "no http_configuration_snippet"
maps = {}
for line in snip.group(1).splitlines():
    m = re.match(r'\s*map \$(\w+) \$(\w+) \{ "~(.*?)" (\S+);(?: default \$(\w+);)? \}$', line)
    if m: maps[m.group(2)] = m.groups()
def val(var, env):
    if var in env: return env[var]
    src, _, rx, repl, dflt = maps[var]
    s = val(src, env)
    m = re.search(rx, s)
    if not m: return val(dflt, env) if dflt else ""
    return re.sub(r"\$(\d)", lambda g: m.group(int(g.group(1))) or "", repl)
cases = [
    ("/admin/v1/wallets/01712345678/change-requests", "/admin/v1/wallets/0171****678/change-requests"),
    ("/test/core-mock/v1/wallets/01812345678?x=1", "/test/core-mock/v1/wallets/0181****678"),
    ("/admin/v1/transactions?msisdn=%2B8801712345678&limit=50", "/admin/v1/transactions"),
    ("/a/8801812345678/b/%2B8801912345678", "/a/880181****678/b/%2B880191****678"),
    ("/m/01712345678/01812345678/01912345678", "/m/0171****678/0181****678/0191****678"),
    ("/n/017123456789/0171****789/SETTLEMENT", "/n/017123456789/0171****789/SETTLEMENT"),
]
for name, src in (("mw_log_uri", "request_uri"), ("mw_log_referer", "http_referer"), ("mw_log_upstream_uri", "upstream_uri")):
    assert name in maps, "no map for $" + name
    for given, want in cases:
        got = val(name, {src: given})
        assert got == want, "%s: %s -> %s, want %s" % (name, given, got, want)
print("ok")
PY
)" || fail "access log format does not mask"
}

test_apisix_reads_the_valkey_password_from_its_environment() {
  grep -A3 -E '^ *envs:' "$REPO_DIR/deploy/apisix/config.yaml" | grep -q 'VALKEY_PASSWORD' || fail "config.yaml does not pass VALKEY_PASSWORD to nginx"
  grep -A12 -E '^  apisix:' "$REPO_DIR/deploy/docker-compose.yml" | grep -qE 'VALKEY_PASSWORD: [$][{]VALKEY_PASSWORD:[?]' \
    || fail "apisix service does not get VALKEY_PASSWORD from the generated env"
  if grep -rn 'redis_password' "$REPO_DIR/deploy/apisix" | grep -v '\$env://'; then fail "literal redis password"; fi
  return 0
}

# ---- admin console (ticket 40) ----

# The console's login goes through the edge (realm ops pages) with the public host pinned, so its
# tokens carry the public issuer; admin-v1 must accept that one besides the in-stack issuer.
test_admin_console_login_routes_and_issuers() {
  render || fail "dry run failed"
  obj routes admin-ui | jq -e '(.uris | index("/console/*")) and .upstream.nodes["mw-admin-ui:3000"] == 1
    and (.plugins | has("openid-connect") | not) and .plugins["limit-count"].key == "remote_addr"' >/dev/null \
    || fail "admin-ui: $(obj routes admin-ui | jq -c '{uris, plugins: (.plugins | keys)}')"
  obj routes auth-ops-browser | jq -e '(.uris | index("/realms/ops/*")) and .upstream.nodes["keycloak:8080"] == 1
    and .plugins["proxy-rewrite"].headers.set == {"X-Forwarded-Host": "localhost", "X-Forwarded-Proto": "https", "X-Forwarded-Port": "443"}' >/dev/null \
    || fail "auth-ops-browser: $(obj routes auth-ops-browser | jq -c '.plugins["proxy-rewrite"]')"
  obj routes admin-v1 | jq -e '.plugins["openid-connect"].claim_validator.issuer.valid_issuers
    == ["http://keycloak:8080/realms/ops", "https://localhost/realms/ops"]' >/dev/null \
    || fail "admin-v1 issuers: $(obj routes admin-v1 | jq -c '.plugins["openid-connect"].claim_validator')"
}

# Security review (sec-ops): the client's Host never reaches Keycloak on the ops login route
# (password-reset / link poisoning). The route answers only for the configured public host
# (MW_PUBLIC_HOST, default localhost), pins X-Forwarded-Host to it whatever the client sent,
# and drops the forwarding headers Keycloak could otherwise read.
test_ops_login_route_pins_the_public_host_and_never_reflects_the_client_host() {
  MW_PUBLIC_HOST=console.example.test render || fail "dry run failed: $OUT"
  local r=out/routes/auth-ops-browser.json
  jq -e '.host == "console.example.test" and (has("hosts") | not)' "$r" >/dev/null \
    || fail "auth-ops-browser not restricted to the public host: $(jq -c '{host, hosts}' "$r")"
  jq -e '.plugins["proxy-rewrite"].headers.set["X-Forwarded-Host"] == "console.example.test"' "$r" >/dev/null \
    || fail "X-Forwarded-Host not pinned: $(jq -c '.plugins["proxy-rewrite"]' "$r")"
  jq -e '.plugins["proxy-rewrite"].headers.remove | index("Forwarded") and index("X-Forwarded-Prefix")' "$r" >/dev/null \
    || fail "client Forwarded / X-Forwarded-Prefix not removed: $(jq -c '.plugins["proxy-rewrite"]' "$r")"
  if grep -q '[$]host' "$REPO_DIR/deploy/apisix/routes/auth-ops-browser.json"; then fail "auth-ops-browser still reflects the client host"; fi
  if MW_PUBLIC_HOST= render; then fail "applied without MW_PUBLIC_HOST"; fi
  assert_out_contains "MW_PUBLIC_HOST is not set"
}

# The apisix-init one-shot gets the public host from the environment (default localhost, the
# edge certificate's name and the redirect URIs of client mw-admin-ui).
test_apisix_init_gets_the_public_host() {
  grep -A40 -E '^  apisix-init:' "$REPO_DIR/deploy/docker-compose.yml" \
    | grep -qE 'MW_PUBLIC_HOST: [$][{]MW_PUBLIC_HOST:-localhost[}]' || fail "apisix-init lacks MW_PUBLIC_HOST"
}

# Security review (sec-ops): on the public ops login route the token endpoint takes only the
# grants of the browser login (authorization_code, refresh_token); a password, client
# credentials or any other grant is refused at the edge (the e2e password grant has its own
# test-only route), and the other token sub-endpoints (introspection) are not public.
test_ops_login_route_refuses_non_browser_grants_on_the_token_endpoint() {
  render || fail "dry run failed: $OUT"
  local r=out/routes/auth-ops-browser.json lua
  lua="$(jq -r '.plugins["serverless-pre-function"] | select(.phase == "access") | .functions[0]' "$r")"
  [ "$lua" = "$(cat "$REPO_DIR/deploy/apisix/lua/ops-token-grant.lua")" ] \
    || fail "auth-ops-browser lacks lua/ops-token-grant.lua in the access phase: $(jq -c '.plugins | keys' "$r")"
  printf '%s' "$lua" | grep -q 'authorization_code = true' || fail "authorization_code not allowed"
  printf '%s' "$lua" | grep -q 'refresh_token = true' || fail "refresh_token not allowed"
  if printf '%s' "$lua" | grep -qE '(password|client_credentials) = true'; then fail "a non-browser grant is allowed"; fi
}

# Ticket 46 (ZAP baseline): HSTS and nosniff on every edge response, the service header set on
# edge-produced errors, and no APISIX version in the Server header.
test_edge_sets_security_headers_and_hides_its_version() {
  render || fail "dry run failed"
  local fns
  fns="$(cat out/global_rules/*.json | jq -r '.. | objects | select(.phase == "header_filter") | .functions[]')"
  printf '%s\n' "$fns" | grep -q 'Strict-Transport-Security' || fail "no HSTS at the edge"
  printf '%s\n' "$fns" | grep -q 'X-Content-Type-Options' || fail "no nosniff at the edge"
  printf '%s\n' "$fns" | grep -q "default-src 'none'" || fail "no CSP on edge errors"
  # the headers run after the envelope function, which marks edge errors in ctx.mw_edge_error
  cat out/global_rules/*.json | jq -r '.. | objects | select(.phase == "header_filter") | .functions | (map(test("correlationId")) | index(true)) < (map(test("Strict-Transport-Security")) | index(true))' \
    | grep -q true || fail "security headers must run after the envelope function"
  grep -Eq '^ +enable_server_tokens: false' "$REPO_DIR/deploy/apisix/config.yaml" || fail "APISIX version not hidden"
}

test_edge_buffers_the_console_sign_in_redirect_headers() {
  # Ticket 59: the console's "Sign in with Keycloak" answer (303 to Keycloak) carries about 4.4 KB of
  # headers (Location with PKCE, state and nonce, the Auth.js state / pkce / nonce / csrf / callback
  # cookies, CSP); nginx's default 4 KiB proxy_buffer_size answered it 502 "upstream sent too big header".
  local c="$REPO_DIR/deploy/apisix/config.yaml" size
  size="$(sed -nE 's/^ +proxy_buffer_size +([0-9]+)k;.*/\1/p' "$c" | head -1)"
  [ -n "$size" ] || fail "config.yaml sets no proxy_buffer_size in http_configuration_snippet"
  [ "$size" -ge 16 ] || fail "proxy_buffer_size ${size}k is below 16k"
  grep -qE '^ +proxy_buffers +[0-9]+ +16k;' "$c" || fail "proxy_buffers not sized to the 16k buffer"
  grep -qE '^ +proxy_busy_buffers_size +[0-9]+k;' "$c" || fail "proxy_busy_buffers_size not set (nginx refuses one below proxy_buffer_size)"
}
