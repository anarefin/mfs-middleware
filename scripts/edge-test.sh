#!/usr/bin/env bash
# Gateway tests for the APISIX edge policies (spec 8.3, WP09, ticket 13), against the running
# MVP stack (`make up`; honours COMPOSE_PROJECT_NAME). Usage: make edge-test
#
# Requests go to the published HTTPS port like any client. Tokens, the Admin API, Valkey and
# the mw-app-gateway log are reached from inside the project (throwaway curl containers on the
# ops network, docker compose exec/logs), as scripts/smoke.sh does.
#
# Checks (ticket 13 acceptance, plus the 5.3 envelope on every edge error):
#   - no, garbage, badly signed or expired bearer token -> 401 MW-AUTH-001 envelope with a
#     correlationId; unknown path 404 and unreachable upstream 502 also get the envelope
#   - a submit body that breaks the schema -> 400 MW-VAL-001 (checked by mw-app-gateway on the
#     bytes the device signed: the edge must not re-encode it, ticket 29); a valid body reaches it
#     byte for byte; an oversized body -> 413 at the edge
#   - more requests than the per-consumer quota -> 429 MW-SYS-RATE_LIMITED, counter in Valkey,
#     another consumer unaffected
#   - client X-Mw-* headers never reach the upstream; X-Correlation-Id generated when absent
#     (or unsafe, or holding a run of 6+ digits), kept when valid, returned to the client and
#     present in the upstream's log
#   - partner callback route (ticket 22): unsigned / badly signed -> 401 MW-AUTH-001 from
#     mw-adapter-billpay, over 16 KiB -> 413, POST only, unrouted partner 404, ip allow-list and
#     per-source quota configured
#   - C2W bank callback route (ticket 23): the same cases against mw-adapter-bank
#   - checkout (ticket 31): unsigned / badly signed merchant calls -> 401 at the edge; the hosted
#     page API reaches mw-checkout without login; a signed body breaking the schema -> 400 from
#     mw-checkout (no body schema at the edge; needs the test-only merchant, else skipped)
#   - USSD (M3 gate): no / wrong credential -> 401, another MNO's credential -> 403, the MNO's own
#     reaches mw-ussd with a quota per hashed MSISDN, unrouted MNO 404
#   - Valkey down (ticket 50): limit-count fails open and APISIX logs mw.edge.rate_limit_degraded
#   - applying deploy/apisix twice is a no-op (Admin API objects identical but update_time)
# The quota check spends merchant01's quota for the rest of the window (60 s).
set -uo pipefail
. "$(dirname "$0")/lib/modules.sh"

file="$MW_ROOT/deploy/compose/mvp/compose.yml"
[ -f "$file" ] || die "edge-test: deploy/compose/mvp/compose.yml does not exist"
compose_files "$file"
command -v jq >/dev/null || die "edge-test: jq is required"
[ -f "$MW_ROOT/deploy/compose/platform/.env" ] || die "edge-test: no generated credentials; run make up first"
set -a; . "$MW_ROOT/deploy/compose/platform/.env"; set +a

CURL_IMAGE="curlimages/curl:8.17.0@sha256:935d9100e9ba842cdb060de42472c7ca90cfe9a7c96e4dacb55e79e560b3ff40"
dc() { docker compose "${DC_F[@]}" "$@"; }
project="$(dc config --format json | jq -r .name)"
https_port="$(dc port apisix 9443 2>/dev/null | head -1 | sed 's/.*://')"
[ -n "$https_port" ] || die "edge-test: apisix is not running in project $project (make up)"
ops_net="$(docker network ls --format '{{.Name}}' --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.network=ops" | head -1)"
base="https://localhost:$https_port"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/edge-test.XXXXXX")"
# temporary objects, removed on exit (named here: checks run in subshells)
echo_route="mw-edge-test-echo-$$"
kc_client="mw-edge-test-$$"

passed=0; failed=0
ok() { passed=$((passed + 1)); printf 'ok   %s\n' "$*"; }
ko() { failed=$((failed + 1)); printf 'FAIL %s\n' "$*"; }
check() { local name="$1" out; shift; if out="$("$@" 2>&1)"; then ok "$name"; else ko "$name"; printf '%s\n' "$out" | tail -8 | sed 's/^/     | /'; fi; }

# ---- helpers ----
ops_curl() { docker run --rm --network "$ops_net" "$CURL_IMAGE" -s --max-time 20 "$@"; }
admin() { local m="$1" p="$2"; shift 2; ops_curl -X "$m" -H "X-API-KEY: $APISIX_ADMIN_KEY" "$@" "http://apisix:9180/apisix/admin/$p"; }
token() { # token USER [CLIENT]
  ops_curl --data-urlencode grant_type=password --data-urlencode "client_id=${2:-mw-app}" \
    --data-urlencode "username=$1" --data-urlencode "password=$KC_CUSTOMERS_TEST_PASSWORD" \
    http://keycloak:8080/realms/customers/protocol/openid-connect/token | jq -r '.access_token // empty'
}
claims() { printf '%s' "$1" | cut -d. -f2 | tr '_-' '/+' | awk '{ while (length($0) % 4) $0 = $0 "="; print }' | base64 -d 2>/dev/null; }
# call METHOD PATH [curl args...]: response status in $status, headers in $tmp/h, body in $tmp/b
call() {
  local m="$1" p="$2"; shift 2
  status="$(curl -sk --max-time 40 -X "$m" -D "$tmp/h" -o "$tmp/b" -w '%{http_code}' "$@" "$base$p")"
}
resp_header() { tr -d '\r' <"$tmp/h" | awk -v n="$(printf '%s' "$1" | tr 'A-Z' 'a-z')" -F': ' 'tolower($1) == n { print $2 }' | tail -1; }
# envelope STATUS CODE: the last response is STATUS with the 5.3 envelope carrying CODE, its
# correlationId equal to the X-Correlation-Id response header
envelope() {
  [ "$status" = "$1" ] || { echo "HTTP $status, want $1: $(head -c 400 "$tmp/b")"; return 1; }
  jq -e --arg c "$2" '.code == $c and (.message | type == "string" and length > 0)
    and (.correlationId | type == "string" and length > 0) and (.retryable | type == "boolean")
    and (.details | type == "array")' "$tmp/b" >/dev/null \
    || { echo "not the 5.3 envelope with $2: $(head -c 400 "$tmp/b")"; return 1; }
  [ "$(resp_header Content-Type)" = "application/json" ] || { echo "content type: $(resp_header Content-Type)"; return 1; }
  [ "$(jq -r .correlationId "$tmp/b")" = "$(resp_header X-Correlation-Id)" ] \
    || { echo "correlationId $(jq -r .correlationId "$tmp/b") != header $(resp_header X-Correlation-Id)"; return 1; }
}
# gateway_logged CID [WAIT]: mw-app-gateway logged a line with correlationId CID (polls WAIT s)
gateway_logged() {
  local i
  for i in $(seq 0 "${2:-10}"); do
    dc logs --no-log-prefix --since 10m mw-app-gateway 2>/dev/null | grep -q "\"correlationId\":\"$1\"" && return 0
    [ "${2:-10}" -gt 0 ] && sleep 1
  done
  return 1
}
# Random 12-letter suffix (hex with the digits mapped to g-p), never a digit: a caller
# correlation id with a run of 6+ digits is replaced at the edge (and by the services'
# CorrelationIdFilter), and gateway_logged greps the log for the id exactly as it was sent.
uniq_id() { printf 'edge-test-%s-%s' "$1" "$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n' | tr '0-9' 'g-p')"; }

cleanup() {
  local at id
  dc start valkey >/dev/null 2>&1
  admin DELETE "routes/$echo_route" -o /dev/null
  at="$(kc_admin_token)"
  id="$(ops_curl -H "Authorization: Bearer $at" "http://keycloak:8080/admin/realms/customers/clients?clientId=$kc_client" | jq -r '.[0].id // empty')"
  [ -n "$id" ] && ops_curl -o /dev/null -X DELETE -H "Authorization: Bearer $at" "http://keycloak:8080/admin/realms/customers/clients/$id"
  rm -rf "$tmp"
}
trap cleanup EXIT

kc_admin_token() {
  ops_curl --data-urlencode grant_type=password --data-urlencode client_id=admin-cli \
    --data-urlencode "username=$(sed -n 's/^KC_BOOTSTRAP_ADMIN_USERNAME=//p' "$MW_ROOT/deploy/compose/platform/env/keycloak.env")" \
    --data-urlencode "password=$KC_BOOTSTRAP_ADMIN_PASSWORD" \
    http://keycloak:8080/realms/master/protocol/openid-connect/token | jq -r '.access_token // empty'
}

info "edge-test: project $project, $base"
customer="$(token customer01)"; merchant="$(token merchant01)"
[ -n "$customer" ] && [ -n "$merchant" ] || die "edge-test: no Keycloak token for customer01/merchant01"
valid_body='{"txnType":"P2P","creditParty":{"type":"WALLET","id":"01711000002"},"amount":{"valueMinor":100,"currency":"BDT"},"encryptedPin":{"keyId":"k1","ciphertext":"dGVzdA=="}}'

# ---- 401 ----
no_token() { call GET /app/v1/transactions/x && envelope 401 MW-AUTH-001; }
check "no bearer token -> 401 MW-AUTH-001 envelope" no_token
garbage_token() { call GET /app/v1/transactions/x -H "Authorization: Bearer not.a.jwt" && envelope 401 MW-AUTH-001; }
check "garbage bearer token -> 401 MW-AUTH-001 envelope" garbage_token
# org portal route (ticket 33): realm portals only
portal_no_token() { call GET /portal/org/v1/bulk/batches && envelope 401 MW-AUTH-001; }
check "portal: no bearer token -> 401 MW-AUTH-001 envelope" portal_no_token
portal_wrong_realm() { call GET /portal/org/v1/bulk/batches -H "Authorization: Bearer $customer" && envelope 401 MW-AUTH-001; }
check "portal: realm customers token -> 401 MW-AUTH-001 envelope" portal_wrong_realm
tampered_token() { # customer01's token with merchant01's signature: bad signature
  call GET /app/v1/transactions/x -H "Authorization: Bearer ${customer%.*}.${merchant##*.}" && envelope 401 MW-AUTH-001
}
check "token with a bad signature -> 401 MW-AUTH-001 envelope" tampered_token

# expired: a token from a temporary public client whose tokens live 1 s
expired_token() {
  local at cid="$kc_client" t exp now slack
  # openid-connect accepts a token until exp + iat_slack (clock skew; plugin default 120 s)
  slack="$(jq -r '.plugins["openid-connect"].iat_slack // 120' "$MW_ROOT/deploy/apisix/routes/app-v1.json")"
  at="$(kc_admin_token)"; [ -n "$at" ] || { echo "no Keycloak admin token"; return 1; }
  ops_curl -o /dev/null -H "Authorization: Bearer $at" -H 'Content-Type: application/json' \
    -d "{\"clientId\":\"$cid\",\"publicClient\":true,\"directAccessGrantsEnabled\":true,\"standardFlowEnabled\":false,\"attributes\":{\"access.token.lifespan\":\"1\"}}" \
    http://keycloak:8080/admin/realms/customers/clients
  t="$(token customer01 "$cid")"; [ -n "$t" ] || { echo "no token from the temporary client"; return 1; }
  exp="$(claims "$t" | jq -r .exp)"
  now="$(date +%s)"; [ "$exp" -le $((now + 2)) ] || { echo "token lives until $exp (now $now)"; return 1; }
  # control: within the grace the edge lets the same token through (mw-app-gateway answers)
  call GET /app/v1/transactions/x -H "Authorization: Bearer $t"
  [ "$status" != 401 ] || { echo "token refused before it expired: $(head -c 300 "$tmp/b")"; return 1; }
  now="$(date +%s)"
  sleep $((exp - now + slack + 2))
  call GET /app/v1/transactions/x -H "Authorization: Bearer $t" && envelope 401 MW-AUTH-001
}
check "expired bearer token -> 401 MW-AUTH-001 envelope" expired_token

# ---- correlation ID, upstream reached ----
# mw-app-gateway answers an app request without X-Device-Id with its own 401 MW-AUTH-003 (device
# gate, ticket 29); APISIX never sends that code, so it proves the request reached the upstream.
gateway_gate() { [ "$status" = 401 ] && [ "$(jq -r .code "$tmp/b" 2>/dev/null)" = MW-AUTH-003 ]; }
cid_generated() {
  local cid
  call GET "/app/v1/transactions/$(uniq_id txn)" -H "Authorization: Bearer $customer"
  gateway_gate || { echo "HTTP $status from mw-app-gateway, want its 401 MW-AUTH-003: $(head -c 300 "$tmp/b")"; return 1; }
  cid="$(resp_header X-Correlation-Id)"
  printf '%s' "$cid" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' || { echo "generated id '$cid' is not a UUID"; return 1; }
  [ "$(jq -r .correlationId "$tmp/b")" = "$cid" ] || { echo "mw-app-gateway saw $(jq -r .correlationId "$tmp/b"), client got $cid"; return 1; }
  gateway_logged "$cid" || { echo "no mw-app-gateway log line with correlationId $cid"; return 1; }
}
check "no X-Correlation-Id -> one generated, forwarded upstream, returned, in the upstream log" cid_generated
cid_kept() {
  local cid; cid="$(uniq_id cid)"
  call GET "/app/v1/transactions/$(uniq_id txn)" -H "Authorization: Bearer $customer" -H "X-Correlation-Id: $cid"
  [ "$(resp_header X-Correlation-Id)" = "$cid" ] && [ "$(jq -r .correlationId "$tmp/b")" = "$cid" ] \
    || { echo "sent $cid, got header $(resp_header X-Correlation-Id) body $(jq -r .correlationId "$tmp/b")"; return 1; }
  gateway_logged "$cid" || { echo "no mw-app-gateway log line with correlationId $cid"; return 1; }
}
check "valid X-Correlation-Id -> kept end to end" cid_kept

# ---- what the upstream receives: a temporary route (deleted on exit) whose .../echo answers,
# right before proxying (before_proxy), with the request headers it would forward; any other
# path goes to an upstream that is not listening ----
echo_setup() {
  admin PUT "routes/$echo_route" -o /dev/null -H 'Content-Type: application/json' -d "{
    \"uri\": \"/mw-edge-test/$$/*\",
    \"plugins\": { \"serverless-post-function\": { \"_meta\": { \"filter\": [[\"uri\", \"~~\", \"/echo$\"]] },
      \"phase\": \"before_proxy\", \"functions\": [
      \"return function(conf, ctx) require('apisix.core').response.exit(200, ngx.req.get_headers(0)) end\" ] } },
    \"upstream\": { \"type\": \"roundrobin\", \"nodes\": { \"127.0.0.1:9\": 1 } } }"
  for _ in $(seq 1 20); do call GET "/mw-edge-test/$$/echo"; [ "$status" = 200 ] && return 0; sleep 0.5; done
  echo "echo route answered $status"; return 1
}
check "temporary echo route for the forwarded headers" echo_setup
strip_internal() {
  local hdrs
  call GET "/mw-edge-test/$$/echo" -H 'X-Mw-Reply-To: forged' -H 'x-mw-source: forged' -H 'X-MW-Txn-Id: forged' -H 'X-Other: kept'
  [ "$status" = 200 ] || { echo "echo route answered $status"; return 1; }
  hdrs="$(jq -c . "$tmp/b")"
  printf '%s' "$hdrs" | jq -e 'keys | map(ascii_downcase) | any(startswith("x-mw-")) | not' >/dev/null || { echo "forwarded: $hdrs"; return 1; }
  printf '%s' "$hdrs" | jq -e '.["x-other"] == "kept" and (.["x-correlation-id"] | length > 0)' >/dev/null || { echo "forwarded: $hdrs"; return 1; }
}
check "client X-Mw-* headers never reach the upstream (other headers do)" strip_internal
cid_unsafe_replaced() {
  local fwd
  call GET "/mw-edge-test/$$/echo" -H 'X-Correlation-Id: bad id<script>'
  fwd="$(jq -r '.["x-correlation-id"]' "$tmp/b")"
  printf '%s' "$fwd" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' || { echo "forwarded unsafe id: '$fwd'"; return 1; }
  [ "$(resp_header X-Correlation-Id)" = "$fwd" ] || { echo "client got $(resp_header X-Correlation-Id), upstream $fwd"; return 1; }
}
check "unsafe X-Correlation-Id -> replaced by a generated one before the upstream" cid_unsafe_replaced
cid_digits_replaced() {
  local fwd
  call GET "/mw-edge-test/$$/echo" -H 'X-Correlation-Id: corr-2345678901234'
  fwd="$(jq -r '.["x-correlation-id"]' "$tmp/b")"
  printf '%s' "$fwd" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' || { echo "forwarded id with a digit run: '$fwd'"; return 1; }
  [ "$(resp_header X-Correlation-Id)" = "$fwd" ] || { echo "client got $(resp_header X-Correlation-Id), upstream $fwd"; return 1; }
}
check "X-Correlation-Id with a run of 6+ digits -> replaced by a generated UUID before the upstream" cid_digits_replaced
upstream_down() { call GET "/mw-edge-test/$$/dead" && envelope 502 MW-SYS-UPSTREAM_UNAVAILABLE && jq -e '.retryable' "$tmp/b" >/dev/null; }
check "upstream unreachable -> 502 MW-SYS-UPSTREAM_UNAVAILABLE envelope (retryable)" upstream_down

# ---- 400 / 413 ----
schema_400() {
  local cid; cid="$(uniq_id bad)"
  call POST /app/v1/transactions -H "Authorization: Bearer $customer" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(uniq_id key)" -H "X-Correlation-Id: $cid" \
    -d '{"txnType":"P2P","creditParty":{"id":"01711000002"},"encryptedPin":{"keyId":"k1","ciphertext":"x"}}'
  envelope 400 MW-VAL-001 || return 1
  jq -e '.details[0].issue | test("amount")' "$tmp/b" >/dev/null || { echo "no reason in details: $(cat "$tmp/b")"; return 1; }
}
check "body breaking the schema -> 400 MW-VAL-001 (mw-app-gateway's schema check)" schema_400
attribute_400() {
  call POST /app/v1/transactions -H "Authorization: Bearer $customer" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(uniq_id key)" -d "${valid_body%\}},\"attributes\":{\"duplicate\":\"true\"}}"
  envelope 400 MW-VAL-001
}
check "attribute outside the allow-list -> 400 MW-VAL-001" attribute_400
malformed_400() {
  call POST /app/v1/transactions -H "Authorization: Bearer $customer" -H 'Content-Type: application/json' -d '{"txnType":'
  envelope 400 MW-VAL-001
}
check "malformed JSON -> 400 MW-VAL-001" malformed_400
valid_passes() { # no device headers: mw-app-gateway's own 401 MW-AUTH-003 proves the body got through
  local cid; cid="$(uniq_id ok)"
  call POST /app/v1/transactions -H "Authorization: Bearer $customer" -H 'Content-Type: application/json' -H "X-Correlation-Id: $cid" -d "$valid_body"
  gateway_gate \
    || { echo "HTTP $status: $(head -c 300 "$tmp/b")"; return 1; }
  gateway_logged "$cid" || { echo "mw-app-gateway did not log $cid"; return 1; }
}
check "valid body -> passes the edge (mw-app-gateway answers, JSON untouched)" valid_passes
too_large() {
  head -c 70000 /dev/zero | tr '\0' 'a' | awk '{ printf "{\"txnType\":\"%s\"}", $0 }' >"$tmp/big"
  call POST /app/v1/transactions -H "Authorization: Bearer $customer" -H 'Content-Type: application/json' --data-binary "@$tmp/big"
  envelope 413 MW-VAL-BODY_TOO_LARGE
}
check "body over 64 KiB -> 413 MW-VAL-BODY_TOO_LARGE" too_large
not_found() { call GET /no/such/route && envelope 404 MW-VAL-003; }
check "unknown path -> 404 MW-VAL-003 envelope" not_found

# ---- 429 per consumer, counters in Valkey ----
quota() {
  local limit codes sub keys urls=()
  call GET /app/v1/transactions/x -H "Authorization: Bearer $merchant"
  limit="$(resp_header X-RateLimit-Limit)"
  [ -n "$limit" ] || { echo "no X-RateLimit-Limit header (HTTP $status)"; return 1; }
  for _ in $(seq 1 $((limit + 5))); do urls+=(-o /dev/null "$base/app/v1/transactions/x"); done
  codes="$(curl -sk --max-time 60 -w '%{http_code}\n' -H "Authorization: Bearer $merchant" "${urls[@]}")"
  printf '%s\n' "$codes" | grep -qx 429 || { echo "no 429 in $((limit + 5)) requests (limit $limit): $(printf '%s\n' "$codes" | sort | uniq -c | tr '\n' ' ')"; return 1; }
  call GET /app/v1/transactions/x -H "Authorization: Bearer $merchant"
  envelope 429 MW-SYS-RATE_LIMITED || return 1
  jq -e '.retryable == true' "$tmp/b" >/dev/null || { echo "429 not retryable"; return 1; }
  # another consumer still passes
  call GET "/app/v1/transactions/$(uniq_id txn)" -H "Authorization: Bearer $customer"
  gateway_gate || { echo "customer01 got HTTP $status after merchant01 hit the quota"; return 1; }
  sub="$(claims "$merchant" | jq -r .sub)"
  keys="$(dc exec -T valkey valkey-cli --scan --pattern "*sub:$sub*")"
  [ -n "$keys" ] || { echo "no Valkey counter for sub:$sub"; return 1; }
}
check "over the per-consumer quota -> 429 MW-SYS-RATE_LIMITED; counter in Valkey; others unaffected" quota

# ---- partner callback route (ticket 22): edge policies, signature checked by the adapter ----
cb_path=/partner/billagg/callback/payments
cb_body='{"transactionId":"UPAY00000000000000000000000000000000","status":"SUCCESS","code":"000","paymentRef":"AGG-1"}'
cb_unsigned() { call POST "$cb_path" -H 'Content-Type: application/json' -d "$cb_body" && envelope 401 MW-AUTH-001; }
check "partner callback without a signature -> 401 MW-AUTH-001 (mw-adapter-billpay)" cb_unsigned
cb_bad_signature() {
  call POST "$cb_path" -H 'Content-Type: application/json' -H "X-Timestamp: $(date +%s)" \
    -H 'X-Signature: bm90IGEgc2lnbmF0dXJl' -d "$cb_body" && envelope 401 MW-AUTH-001
}
check "partner callback with a bad signature -> 401 MW-AUTH-001 (mw-adapter-billpay)" cb_bad_signature
cb_too_large() {
  head -c 20000 /dev/zero | tr '\0' 'a' | awk '{ printf "{\"transactionId\":\"%s\"}", $0 }' >"$tmp/cb-big"
  call POST "$cb_path" -H 'Content-Type: application/json' --data-binary "@$tmp/cb-big" && envelope 413 MW-VAL-BODY_TOO_LARGE
}
check "partner callback body over 16 KiB -> 413 at the edge" cb_too_large
cb_get() { call GET "$cb_path" && envelope 404 MW-VAL-003; }
check "partner callback route takes POST only (GET -> 404 envelope)" cb_get
cb_unrouted() { call POST /partner/nope/callback/payments -H 'Content-Type: application/json' -d "$cb_body" && envelope 404 MW-VAL-003; }
check "callback of a partner without a route -> 404 at the edge" cb_unrouted
cb_policies() {
  local r; r="$(admin GET routes/partner-billagg-callback | jq -c '.value')"
  printf '%s' "$r" | jq -e '.plugins["ip-restriction"].whitelist | length > 0 and (index("0.0.0.0/0") | not)' >/dev/null \
    || { echo "ip-restriction: $(printf '%s' "$r" | jq -c '.plugins["ip-restriction"]')"; return 1; }
  printf '%s' "$r" | jq -e '.plugins["limit-count"] | .key == "remote_addr" and .policy == "redis" and .redis_password == "$env://VALKEY_PASSWORD"' >/dev/null \
    || { echo "limit-count: $(printf '%s' "$r" | jq -c '.plugins["limit-count"]')"; return 1; }
  printf '%s' "$r" | jq -e '.upstream.nodes | has("mw-adapter-billpay:8080")' >/dev/null || { echo "upstream: $(printf '%s' "$r" | jq -c .upstream)"; return 1; }
}
check "partner callback route: ip allow-list, quota per source address in Valkey, upstream mw-adapter-billpay" cb_policies

# ---- C2W bank callback route (ticket 23): same edge policies, upstream mw-adapter-bank ----
bank_path=/partner/c2wbank/callback/debits
bank_body='{"transactionId":"UPAY00000000000000000000000000000000","status":"SUCCESS","code":"000","debitRef":"C2W-1"}'
bank_unsigned() { call POST "$bank_path" -H 'Content-Type: application/json' -d "$bank_body" && envelope 401 MW-AUTH-001; }
check "bank callback without a signature -> 401 MW-AUTH-001 (mw-adapter-bank)" bank_unsigned
bank_bad_signature() {
  call POST "$bank_path" -H 'Content-Type: application/json' -H "X-Timestamp: $(date +%s)" \
    -H 'X-Signature: bm90IGEgc2lnbmF0dXJl' -d "$bank_body" && envelope 401 MW-AUTH-001
}
check "bank callback with a bad signature -> 401 MW-AUTH-001 (mw-adapter-bank)" bank_bad_signature
bank_too_large() {
  head -c 20000 /dev/zero | tr '\0' 'a' | awk '{ printf "{\"transactionId\":\"%s\"}", $0 }' >"$tmp/bank-big"
  call POST "$bank_path" -H 'Content-Type: application/json' --data-binary "@$tmp/bank-big" && envelope 413 MW-VAL-BODY_TOO_LARGE
}
check "bank callback body over 16 KiB -> 413 at the edge" bank_too_large
bank_get() { call GET "$bank_path" && envelope 404 MW-VAL-003; }
check "bank callback route takes POST only (GET -> 404 envelope)" bank_get
bank_policies() {
  local r; r="$(admin GET routes/partner-c2wbank-callback | jq -c '.value')"
  printf '%s' "$r" | jq -e '.plugins["ip-restriction"].whitelist | length > 0 and (index("0.0.0.0/0") | not)' >/dev/null \
    || { echo "ip-restriction: $(printf '%s' "$r" | jq -c '.plugins["ip-restriction"]')"; return 1; }
  printf '%s' "$r" | jq -e '.plugins["limit-count"] | .key == "remote_addr" and .policy == "redis" and .redis_password == "$env://VALKEY_PASSWORD" and .group == "partner-c2wbank-callback"' >/dev/null \
    || { echo "limit-count: $(printf '%s' "$r" | jq -c '.plugins["limit-count"]')"; return 1; }
  printf '%s' "$r" | jq -e '.upstream.nodes | has("mw-adapter-bank:8080")' >/dev/null || { echo "upstream: $(printf '%s' "$r" | jq -c .upstream)"; return 1; }
}
check "bank callback route: ip allow-list, quota per source address in Valkey, upstream mw-adapter-bank" bank_policies

# ---- checkout merchant API (ticket 31): hmac-auth at the edge; hosted page API without login ----
co_body='{"orderId":"EDGE-1","amount":{"valueMinor":1000,"currency":"BDT"},"successUrl":"https://shop.example.test/ok","failUrl":"https://shop.example.test/fail","cancelUrl":"https://shop.example.test/cancel","ipnUrl":"https://shop.example.test/ipn"}'
co_unsigned() { call POST /checkout/v1/sessions -H 'Content-Type: application/json' -d "$co_body" && envelope 401 MW-AUTH-001; }
check "checkout session without an HMAC signature -> 401 MW-AUTH-001 at the edge" co_unsigned
co_bad_signature() {
  local d; d="$(LC_ALL=C TZ=GMT date '+%a, %d %b %Y %H:%M:%S GMT')"
  call POST /checkout/v1/sessions -H 'Content-Type: application/json' -H "Date: $d" \
    -H 'Authorization: Signature keyId="merchant-demo",algorithm="hmac-sha256",headers="@request-target date",signature="bm90IGEgc2lnbmF0dXJl"' \
    -d "$co_body" && envelope 401 MW-AUTH-001
}
check "checkout session with a bad HMAC signature -> 401 MW-AUTH-001 at the edge" co_bad_signature
co_get_unsigned() { call GET "/checkout/v1/sessions/$(uniq_id s)" && envelope 401 MW-AUTH-001; }
check "checkout session read without an HMAC signature -> 401 MW-AUTH-001 at the edge" co_get_unsigned
co_page_unknown() { call GET "/checkout/pay/v1/$(uniq_id tok)" && envelope 404 MW-VAL-003; }
check "hosted page API reaches mw-checkout without login (unknown token -> 404 MW-VAL-003)" co_page_unknown
# co_signed BODY: POST /checkout/v1/sessions signed as the dev merchant (README: hmac-auth)
co_signed() {
  local d digest sig
  d="$(LC_ALL=C TZ=GMT date '+%a, %d %b %Y %H:%M:%S GMT')"
  digest="SHA-256=$(printf '%s' "$1" | openssl dgst -sha256 -binary | base64)"
  sig="$(printf 'merchant-demo\nPOST /checkout/v1/sessions\ndate: %s\ndigest: %s\n' "$d" "$digest" \
    | openssl dgst -sha256 -hmac "$CHECKOUT_DEMO_MERCHANT_SECRET" -binary | base64)"
  call POST /checkout/v1/sessions -H 'Content-Type: application/json' -H "Date: $d" -H "Digest: $digest" \
    -H "Authorization: Signature keyId=\"merchant-demo\",algorithm=\"hmac-sha256\",headers=\"@request-target date digest\",signature=\"$sig\"" \
    --data-binary "$1"
}
co_schema_400() { # the edge has no body schema any more: mw-checkout refuses it (duplicate key)
  if [ "$(admin GET consumers/merchant_demo -o /dev/null -w '%{http_code}')" != 200 ]; then
    echo "skipped: consumer merchant_demo exists only after make e2e (MW_E2E_ROUTES=1)"; return 0
  fi
  co_signed "${co_body%\}},\"amount\":{\"valueMinor\":1,\"currency\":\"BDT\"}}" && envelope 400 MW-VAL-001 || return 1
  co_signed "${co_body/BDT/USD}" && envelope 400 MW-VAL-001
}
check "signed checkout body breaking the schema -> 400 MW-VAL-001 (mw-checkout's schema check)" co_schema_400

# ---- MNO USSD gateways (spec 8.2, M3 gate): one basic-auth consumer per MNO, bound to its path ----
ussd_step() { # ussd_step MNO CREDENTIAL_MNO|none MSISDN TYPE
  local auth=() v
  v="USSD_$(printf '%s' "$2" | tr 'a-z' 'A-Z')_GATEWAY_SECRET"
  [ "$2" = none ] || auth=(-u "ussd-$2:${!v}")
  call POST "/ussd/$1" ${auth[@]+"${auth[@]}"} -H 'Content-Type: application/json' \
    -d "{\"sessionId\":\"$(uniq_id ussd)\",\"msisdn\":\"$3\",\"type\":\"$4\",\"serviceCode\":\"*268#\",\"input\":\"\"}"
}
ussd_no_credential() { ussd_step robi none 01711000001 BEGIN && envelope 401 MW-AUTH-001; }
check "USSD step without a credential -> 401 MW-AUTH-001 at the edge" ussd_no_credential
ussd_wrong_mno() { ussd_step robi gp 01711000001 BEGIN && envelope 403 MW-AUTH-002; }
check "USSD step on /ussd/robi with the GP credential -> 403 MW-AUTH-002 at the edge" ussd_wrong_mno
ussd_bad_password() {
  call POST /ussd/robi -u 'ussd-robi:not-the-secret' -H 'Content-Type: application/json' \
    -d '{"sessionId":"x","msisdn":"01711000001","type":"BEGIN","input":""}' && envelope 401 MW-AUTH-001
}
check "USSD step with a wrong password -> 401 MW-AUTH-001 at the edge" ussd_bad_password
ussd_passes() { # robi is HTTP in dev: mw-ussd answers the dial-in screen
  local keys
  ussd_step robi robi 01711000001 BEGIN
  [ "$status" = 200 ] && jq -e '.flag | length > 0' "$tmp/b" >/dev/null || { echo "HTTP $status: $(head -c 300 "$tmp/b")"; return 1; }
  [ -n "$(resp_header X-RateLimit-Limit)" ] || { echo "no quota header"; return 1; }
  keys="$(dc exec -T valkey valkey-cli --scan --pattern '*robi:*')"
  [ -n "$keys" ] || { echo "no per-MSISDN counter in Valkey"; return 1; }
  if printf '%s' "$keys" | grep -q 1711000001; then echo "MSISDN in a Valkey key: $keys"; return 1; fi
}
check "USSD step with the MNO's own credential -> mw-ussd answers; quota per hashed MSISDN" ussd_passes
ussd_unknown_mno() { ussd_step nope robi 01711000001 BEGIN && envelope 404 MW-VAL-003; }
check "USSD step for an MNO without a route -> 404 at the edge" ussd_unknown_mno

# ---- rate limiter fails open with an alert line when Valkey is down (ticket 50) ----
# Stops Valkey for a few seconds (the services fall back: the orchestrator's idempotency lock to
# the PostgreSQL primary key); cleanup starts it again if this check dies half-way.
limiter_degraded() {
  local cid i
  dc stop valkey >/dev/null 2>&1 || { echo "could not stop valkey"; return 1; }
  cid="$(uniq_id degraded)"
  call GET "/app/v1/transactions/$(uniq_id txn)" -H "Authorization: Bearer $customer" -H "X-Correlation-Id: $cid"
  dc start valkey >/dev/null 2>&1
  gateway_gate || { echo "with Valkey down got HTTP $status, want the gateway's 401 MW-AUTH-003 (fail-open)"; return 1; }
  [ -z "$(resp_header X-RateLimit-Limit)" ] || { echo "quota header present although Valkey was down"; return 1; }
  for i in $(seq 0 10); do
    dc logs --no-log-prefix --since 2m apisix 2>&1 | grep 'mw.edge.rate_limit_degraded' | grep -q 'route=app-v1' && break
    [ "$i" = 10 ] && { echo "no mw.edge.rate_limit_degraded route=app-v1 line in the APISIX log"; return 1; }
    sleep 1
  done
  for i in $(seq 0 30); do
    dc exec -T valkey valkey-cli ping 2>/dev/null | grep -qx PONG && return 0
    sleep 1
  done
  echo "valkey did not come back"; return 1
}
check "Valkey down -> request passes unlimited and APISIX logs mw.edge.rate_limit_degraded" limiter_degraded

# ---- idempotent apply ----
dump() { for k in global_rules routes ssls; do admin GET "$k"; done | jq -S -s '[.[].list[]? | .value | del(.update_time)] | sort_by(.id)'; }
idempotent() {
  local before after
  dc run --rm --no-deps apisix-init >"$tmp/apply1" 2>&1 || { tail -5 "$tmp/apply1"; return 1; }
  before="$(dump)"
  dc run --rm --no-deps apisix-init >"$tmp/apply2" 2>&1 || { tail -5 "$tmp/apply2"; return 1; }
  after="$(dump)"
  [ "$(printf '%s' "$before" | jq length)" -gt 0 ] || { echo "nothing applied"; return 1; }
  [ "$before" = "$after" ] || { diff <(printf '%s\n' "$before") <(printf '%s\n' "$after") | head -20; return 1; }
}
check "applying deploy/apisix twice is a no-op (objects identical but update_time)" idempotent

printf '\nedge-test: %d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
