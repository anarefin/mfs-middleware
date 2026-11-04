# APISIX edge config (spec 8.3, WP09, ticket 13): static checks on what deploy/apisix/apply.sh
# would send to the Admin API (APISIX_DRY_RUN), and on the request-validation schemas derived
# from the OpenAPI contracts. The live gateway tests are scripts/edge-test.sh (make edge-test).

APPLY="$REPO_DIR/deploy/apisix/apply.sh"

# render [DIR]: dry-run apply.sh over DIR (default: the repo's deploy/apisix) into ./out;
# one file per Admin API object at out/<kind>/<id>.json.
render() {
  local dir="${1:-$REPO_DIR/deploy/apisix}"
  rm -rf out
  OUT="$(APISIX_DRY_RUN="$PWD/out" APISIX_CONFIG_DIR="$dir" bash "$APPLY" 2>&1)"
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

test_lua_and_json_includes_are_inlined_verbatim() {
  mkdir -p fx/routes fx/lua fx/schemas
  printf 'return function(conf, ctx)\n\t-- "quoted" \\ back\\slash & amp\n  return nil\nend\n' >fx/lua/f.lua
  printf '{ "type": "object",\n  "required": ["a"] }\n' >fx/schemas/s.json
  cat >fx/routes/r.json <<'EOF'
{ "uri": "/x", "plugins": {
  "serverless-pre-function": { "phase": "rewrite", "functions": ["@lua:lua/f.lua"] },
  "request-validation": { "body_schema": "@json:schemas/s.json" } } }
EOF
  render "$PWD/fx" || fail "dry run failed"
  jq -e . out/routes/r.json >/dev/null || fail "not JSON: $(cat out/routes/r.json)"
  [ "$(jq -r '.plugins["serverless-pre-function"].functions[0]' out/routes/r.json)" = "$(cat fx/lua/f.lua)" ] \
    || fail "lua not inlined verbatim: $(jq '.plugins["serverless-pre-function"].functions[0]' out/routes/r.json)"
  [ "$(jq -c '.plugins["request-validation"].body_schema' out/routes/r.json)" = '{"type":"object","required":["a"]}' ] \
    || fail "json not inlined: $(cat out/routes/r.json)"
}

test_a_missing_include_fails_the_apply() {
  mkdir -p fx/routes
  printf '{ "uri": "/x", "plugins": { "serverless-pre-function": { "functions": ["@lua:lua/nope.lua"] } } }\n' >fx/routes/r.json
  if render "$PWD/fx"; then fail "dry run passed with a missing include"; fi
  assert_out_contains "nope.lua"
}

# ---- request-validation schemas derived from contracts/ (scripts/apisix-schemas.sh) ----

# fixture_schemas: a root with contracts/ and deploy/apisix/schemas copied from the repo
fixture_schemas() {
  cp -R "$REPO_DIR/contracts/." contracts/
  mkdir -p deploy/apisix
  cp -R "$REPO_DIR/deploy/apisix/schemas" deploy/apisix/schemas
}

schemas() { OUT="$(MW_ROOT="$PWD" "$REPO_DIR/scripts/apisix-schemas.sh" "$@" 2>&1)"; }

test_repo_edge_schemas_match_their_contracts() {
  fixture_schemas
  schemas --check || fail "edge schemas drifted from contracts/"
}

test_app_submit_schema_is_the_openapi_transaction_request_minus_value_echoing_keywords() {
  local s="$REPO_DIR/deploy/apisix/schemas/app-v1-transaction-request.json"
  [ -f "$s" ] || fail "no $s"
  jq -e '."x-mw-derived-from" == "api/mw-app-gateway/app.v1.openapi.yaml#/components/schemas/TransactionRequest"' "$s" >/dev/null \
    || fail "source not recorded"
  jq -e '.required == ["txnType", "creditParty", "amount", "encryptedPin"]
    and .properties.txnType.enum == ["P2P"]
    and .properties.amount.required == ["valueMinor", "currency"]
    and .properties.amount.additionalProperties == false
    and .properties.amount.properties.valueMinor.minimum == 1
    and .properties.creditParty.required == ["id"]
    and .properties.encryptedPin.properties.ciphertext.maxLength == 4096' "$s" >/dev/null \
    || fail "not the TransactionRequest shape: $(jq -c . "$s")"
  # propertyNames (ignored by APISIX's Lua validator) becomes a closed properties list
  jq -e '.properties.attributes.properties | keys == ["reference"]' "$s" >/dev/null || fail "attributes allow-list lost"
  jq -e '.properties.attributes.additionalProperties == false' "$s" >/dev/null || fail "attributes not closed"
  # the Lua validator logs a failed pattern WITH the value (an MSISDN): no pattern, no format
  if jq -e '[.. | objects | select(has("pattern") or has("format") or has("propertyNames") or has("$ref"))] | length > 0' "$s" >/dev/null; then
    fail "pattern/format/propertyNames/\$ref left in $s"
  fi
}

test_a_contract_change_makes_the_check_fail_until_written() {
  fixture_schemas
  sed -i.bak 's/required: \[txnType, creditParty, amount, encryptedPin\]/required: [txnType, creditParty, amount, encryptedPin, locale]/' \
    contracts/api/mw-app-gateway/app.v1.openapi.yaml
  grep -q 'encryptedPin, locale]' contracts/api/mw-app-gateway/app.v1.openapi.yaml || fail "fixture edit did not apply"
  if schemas --check; then fail "check passed on a stale schema"; fi
  assert_out_contains "app-v1-transaction-request.json"
  schemas --write || fail "write failed"
  jq -e '.required | index("locale")' deploy/apisix/schemas/app-v1-transaction-request.json >/dev/null || fail "not rewritten"
  schemas --check || fail "check fails after write"
}

test_an_unsupported_keyword_fails_the_derivation() {
  fixture_schemas
  sed -i.bak 's/maxProperties: 20/maxProperties: 20, patternProperties: { "^x": { type: string } }/' contracts/api/mw-app-gateway/app.v1.openapi.yaml
  grep -q 'patternProperties' contracts/api/mw-app-gateway/app.v1.openapi.yaml || fail "fixture edit did not apply"
  if schemas --check; then fail "unsupported keyword accepted"; fi
  assert_out_contains "patternProperties"
}

test_contracts_lint_runs_the_edge_schema_check() {
  fixture_schemas
  printf '{ "x-mw-derived-from": "api/mw-app-gateway/app.v1.openapi.yaml#/components/schemas/TransactionRequest" }\n' \
    >deploy/apisix/schemas/app-v1-transaction-request.json
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "app-v1-transaction-request.json"
}

# ---- make edge-test (scripts/edge-test.sh): the live tests need a running stack ----

test_edge_test_fails_clearly_without_a_stack() {
  assert_fails mw_make "$PWD" edge-test
  assert_out_contains "deploy/compose/mvp/compose.yml"
  mkdir -p deploy/compose/mvp deploy/compose/platform .bin
  touch deploy/compose/mvp/compose.yml
  assert_fails mw_make "$PWD" edge-test
  assert_out_contains "make up"
  printf 'APISIX_ADMIN_KEY=t\n' >deploy/compose/platform/.env
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

test_app_route_refuses_expired_tokens_without_a_long_grace() {
  render || fail "dry run failed"
  # openid-connect accepts a token until exp + iat_slack, 120 s unless set
  obj routes app-v1 | jq -e '.plugins["openid-connect"].iat_slack | . != null and . <= 30' >/dev/null \
    || fail "iat_slack: $(obj routes app-v1 | jq -c '.plugins["openid-connect"].iat_slack')"
}

test_app_route_validates_the_submit_body_after_auth() {
  render || fail "dry run failed"
  local r
  r="$(obj routes app-v1)"
  [ "$(printf '%s' "$r" | jq -c '.plugins["request-validation"].body_schema')" = \
    "$(jq -c . "$REPO_DIR/deploy/apisix/schemas/app-v1-transaction-request.json")" ] || fail "body_schema is not the derived schema"
  printf '%s' "$r" | jq -e '.plugins["request-validation"] | .rejected_code == 400
    and (._meta.filter | tostring | test("POST")) and (._meta.filter | tostring | test("/app/v1/transactions"))' >/dev/null \
    || fail "validation not limited to POST /app/v1/transactions: $(printf '%s' "$r" | jq -c '.plugins["request-validation"]._meta')"
  # openid-connect has priority 2599: validate only authenticated requests
  printf '%s' "$r" | jq -e '.plugins["request-validation"]._meta.priority < 2599' >/dev/null || fail "validation runs before auth"
  printf '%s' "$r" | jq -e '.plugins["client-control"].max_body_size > 0' >/dev/null || fail "no body size cap"
}

test_the_edge_never_logs_bodies() {
  render || fail "dry run failed"
  if all_plugins | grep -E -- '-logger$|^[^ ]+ (syslog|kafka-logger|http-logger|file-logger)$'; then fail "a logger plugin is configured"; fi
  if cat out/*/*.json | jq -e '[.. | objects | to_entries[] | select(.key | test("include_(req|resp)_body"))] | length > 0' >/dev/null; then
    fail "body logging turned on"
  fi
  if grep -nE 'request_body|resp_body|access_log_format' "$REPO_DIR/deploy/apisix/config.yaml"; then fail "access log format with bodies"; fi
  return 0
}

test_apisix_reads_the_valkey_password_from_its_environment() {
  grep -A3 -E '^ *envs:' "$REPO_DIR/deploy/apisix/config.yaml" | grep -q 'VALKEY_PASSWORD' || fail "config.yaml does not pass VALKEY_PASSWORD to nginx"
  grep -A12 -E '^  apisix:' "$REPO_DIR/deploy/compose/platform/compose.yml" | grep -qE 'VALKEY_PASSWORD: [$][{]VALKEY_PASSWORD:[?]' \
    || fail "apisix service does not get VALKEY_PASSWORD from the generated env"
  if grep -rn 'redis_password' "$REPO_DIR/deploy/apisix" | grep -v '\$env://'; then fail "literal redis password"; fi
  return 0
}
