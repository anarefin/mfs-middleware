#!/usr/bin/env bash
# Smoke test for the running MVP Compose project (spec WP02). Run after `make up`.
# Usage: scripts/smoke.sh        (honours COMPOSE_PROJECT_NAME, default project "mw")
#
# Checks: every platform container healthy and the one-shots exited 0; only APISIX
# publishes a host port; every topic in deploy/kafka/topics.txt exists with RF 1 and the
# planned partition count; Kafka produce/consume; a PostgreSQL connection; a Valkey ping;
# Keycloak in production mode with its realms in PostgreSQL; a Keycloak token for every test user and the partner client (ops users: refused until
# TOTP is enrolled, and the ops browser flow requires OTP); an OpenBao KV read; the
# APISIX /healthz route over HTTPS on the published port; no rate-limiter degradation logged.
set -uo pipefail
. "$(dirname "$0")/lib/modules.sh"

file="$MW_ROOT/deploy/compose/mvp/compose.yml"
[ -f "$file" ] || die "smoke: deploy/compose/mvp/compose.yml does not exist"
command -v jq >/dev/null || die "smoke: jq is required"
"$(dirname "$0")/gen-dev-env.sh"

CURL_IMAGE="curlimages/curl:8.17.0@sha256:935d9100e9ba842cdb060de42472c7ca90cfe9a7c96e4dacb55e79e560b3ff40"
LONG_RUNNING="kafka postgres valkey keycloak openbao etcd apisix greenmail"
ONE_SHOT="kafka-init etcd-init apisix-init"

# Generated credentials (gitignored .env written by gen-dev-env.sh)
set -a; . "$MW_ROOT/deploy/compose/platform/.env"; set +a
# resolve VALUE: a realm file's ${VAR} placeholder -> its generated value (as Keycloak's
# import does); any other value unchanged
resolve() {
  local v
  case "$1" in
    '${'*'}') v="${1#??}"; v="${v%?}"; printf '%s' "${!v-}" ;;
    *) printf '%s' "$1" ;;
  esac
}

dc() { docker compose -f "$file" "$@"; }
project="$(dc config --format json | jq -r .name)"

passed=0; failed=0
ok() { passed=$((passed + 1)); printf 'ok   %s\n' "$*"; }
ko() { failed=$((failed + 1)); printf 'FAIL %s\n' "$*"; }
check() { local name="$1"; shift; if out="$("$@" 2>&1)"; then ok "$name"; else ko "$name"; printf '%s\n' "$out" | tail -5 | sed 's/^/     | /'; fi; }

info "smoke: project $project"
ps_all() { dc ps -a --format json | jq -s 'if .[0] | type == "array" then .[0] else . end'; }

# `make up` (up -d) returns before health checks pass and the one-shots finish; wait
# (up to SMOKE_WAIT seconds) while any container is still starting.
settling='[.[] | select(.State == "created" or (.State == "running" and (.Health == "starting" or .Health == ""))) | .Service]'
waited=0
while pending="$(ps_all | jq -r "$settling | join(\" \")")" && [ -n "$pending" ]; do
  [ "$waited" -lt "${SMOKE_WAIT:-600}" ] || break
  [ $((waited % 30)) -eq 0 ] && info "smoke: waiting for $pending"
  sleep 3; waited=$((waited + 3))
done
ps_json="$(ps_all)"

# ---- containers ----
for s in $LONG_RUNNING; do
  health="$(printf '%s' "$ps_json" | jq -r --arg s "$s" '.[] | select(.Service == $s) | .Health')"
  [ "$health" = healthy ] && ok "container $s healthy" || ko "container $s healthy (health: ${health:-missing})"
done
for s in $ONE_SHOT; do
  st="$(printf '%s' "$ps_json" | jq -r --arg s "$s" '.[] | select(.Service == $s) | "\(.State) \(.ExitCode)"')"
  [ "$st" = "exited 0" ] && ok "one-shot $s exited 0" || ko "one-shot $s exited 0 (state: ${st:-missing})"
done

# ---- published host ports ----
published="$(printf '%s' "$ps_json" | jq -r '.[] | .Service as $s | (.Publishers // [])[] | select(.PublishedPort != 0) | "\($s):\(.TargetPort)"' | sort -u)"
[ "$published" = "apisix:9443" ] && ok "only apisix:9443 is published" || ko "only apisix:9443 is published (got: $(echo $published))"

# ---- Kafka topics ----
topics_check() {
  local want got missing=0 topic parts _
  want="$("$MW_ROOT/deploy/kafka/apply-topics.sh" --dry-run "$MW_ROOT/deploy/kafka/topics.txt")" || return 1
  got="$(dc exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --exclude-internal \
    | awk -F'\t' '/PartitionCount/ { t=$1; sub(/^Topic: /,"",t); p=$3; sub(/.*: /,"",p); r=$4; sub(/.*: /,"",r); print t, p, r }')" || return 1
  while read -r topic parts _; do
    parts="${parts#partitions=}"
    printf '%s\n' "$got" | grep -qx "$topic $parts 1" || { echo "missing or wrong: $topic (want $parts partitions, RF 1)"; missing=1; }
  done <<<"$want"
  [ "$missing" = 0 ] && echo "$(wc -l <<<"$want" | tr -d ' ') topics match"
}
check "kafka topics match deploy/kafka/topics.txt (partitions, RF 1)" topics_check

kafka_roundtrip() {
  local t="smoke.roundtrip.$$" msg="smoke-$$-$RANDOM" got
  dc exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic "$t" --partitions 1 --replication-factor 1 >/dev/null || return 1
  printf '%s\n' "$msg" | dc exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic "$t" --producer-property acks=all >/dev/null || return 1
  got="$(dc exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic "$t" --from-beginning --max-messages 1 --timeout-ms 30000 2>/dev/null)"
  dc exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic "$t" >/dev/null
  [ "$got" = "$msg" ] || { echo "consumed '$got', want '$msg'"; return 1; }
}
check "kafka produce/consume round trip" kafka_roundtrip

# ---- PostgreSQL, Valkey, OpenBao ----
check "postgres connection (TCP, password auth, PG 18)" \
  dc exec -T postgres sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "show server_version" | grep -q "^18\."'
check "valkey ping (auth required)" \
  dc exec -T valkey sh -c 'valkey-cli ping | grep -qx PONG && env -u REDISCLI_AUTH valkey-cli ping 2>&1 | grep -q NOAUTH'
check "openbao KV read secret/mw/smoke" \
  dc exec -T openbao sh -c 'BAO_TOKEN="$(sed -n "s/.*\"root_token\": *\"\([^\"]*\)\".*/\1/p" /openbao/file/dev-init.json)" bao kv get -field=value secret/mw/smoke | grep -qx ok'

# ---- Keycloak tokens (from a throwaway curl container on the ops network) ----
ops_net="$(docker network ls --format '{{.Name}}' --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.network=ops" | head -1)"
token() { # token REALM FORM...
  local realm="$1"; shift
  local args=() f
  for f in "$@"; do args+=(--data-urlencode "$f"); done
  docker run --rm --network "$ops_net" "$CURL_IMAGE" -sf "${args[@]}" \
    "http://keycloak:8080/realms/$realm/protocol/openid-connect/token" | jq -e '.access_token | length > 100' >/dev/null
}
# totp_pending REALM FORM...: the right password is accepted but no token is issued
# until the user has enrolled TOTP (CONFIGURE_TOTP required action, spec 18.2); a wrong
# password would answer "Invalid user credentials" instead
totp_pending() {
  local realm="$1"; shift
  local args=() f out
  for f in "$@"; do args+=(--data-urlencode "$f"); done
  out="$(docker run --rm --network "$ops_net" "$CURL_IMAGE" -s "${args[@]}" \
    "http://keycloak:8080/realms/$realm/protocol/openid-connect/token")"
  printf '%s' "$out" | jq -e '.access_token == null and .error_description == "Account is not fully set up"' >/dev/null \
    || { echo "got: $out"; return 1; }
}
for realm_file in "$MW_ROOT"/deploy/compose/platform/keycloak/realms/*-realm.json; do
  realm="$(jq -r .realm "$realm_file")"
  client="$(jq -r '[.clients[] | select(.publicClient and .directAccessGrantsEnabled)][0].clientId // empty' "$realm_file")"
  while read -r user pw mfa; do
    [ -n "$user" ] || continue
    if [ "$mfa" = mfa ]; then
      check "keycloak $realm/$user: no token before TOTP enrolment (MFA)" totp_pending "$realm" grant_type=password "client_id=$client" "username=$user" "password=$(resolve "$pw")"
    else
      check "keycloak token $realm/$user" token "$realm" grant_type=password "client_id=$client" "username=$user" "password=$(resolve "$pw")"
    fi
  done < <(jq -r '.users[] | select(.credentials) | "\(.username) \(.credentials[0].value) \(if (.requiredActions // []) | index("CONFIGURE_TOTP") then "mfa" else "-" end)"' "$realm_file")
  while read -r cid secret; do
    [ -n "$cid" ] || continue
    check "keycloak token $realm/$cid (client credentials)" token "$realm" grant_type=client_credentials "client_id=$cid" "client_secret=$(resolve "$secret")"
  done < <(jq -r '.clients[] | select(.serviceAccountsEnabled) | "\(.clientId) \(.secret)"' "$realm_file")
done

# ops realm browser flow as imported: password form and OTP form both REQUIRED (18.2)
ops_browser_flow_requires_otp() {
  local at flow ex
  at="$(docker run --rm --network "$ops_net" "$CURL_IMAGE" -sf --data-urlencode grant_type=password \
    --data-urlencode client_id=admin-cli --data-urlencode "username=$(sed -n 's/^KC_BOOTSTRAP_ADMIN_USERNAME=//p' "$MW_ROOT/deploy/compose/platform/env/keycloak.env")" \
    --data-urlencode "password=$KC_BOOTSTRAP_ADMIN_PASSWORD" \
    http://keycloak:8080/realms/master/protocol/openid-connect/token | jq -r .access_token)" || return 1
  flow="$(docker run --rm --network "$ops_net" "$CURL_IMAGE" -sf -H "Authorization: Bearer $at" \
    http://keycloak:8080/admin/realms/ops | jq -r .browserFlow)" || return 1
  [ "$flow" != browser ] || { echo "ops realm uses the built-in browser flow"; return 1; }
  ex="$(docker run --rm --network "$ops_net" "$CURL_IMAGE" -sf -G -H "Authorization: Bearer $at" \
    "http://keycloak:8080/admin/realms/ops/authentication/flows/$(jq -rn --arg f "$flow" '$f | @uri')/executions")" || return 1
  printf '%s' "$ex" | jq -e 'any(.[]; .providerId == "auth-otp-form" and .requirement == "REQUIRED")
    and any(.[]; .providerId == "auth-username-password-form" and .requirement == "REQUIRED")
    and all(.[]; .requirement != "CONDITIONAL")' >/dev/null || { echo "executions: $ex"; return 1; }
}
check "keycloak ops realm browser flow requires OTP" ops_browser_flow_requires_otp

# Keycloak in production mode, its realms stored in its own PostgreSQL database
kc_prod_mode() { local logs; logs="$(dc logs keycloak 2>&1)"; grep -q 'Profile prod activated' <<<"$logs"; }
check "keycloak runs in production mode" kc_prod_mode
kc_realms_in_postgres() {
  local realms
  realms="$(dc exec -T postgres sh -c 'PGPASSWORD="$KC_DB_PASSWORD" psql -h 127.0.0.1 -U keycloak -d keycloak -tAc "select name from realm order by name"')" || return 1
  for r in customers ops partners portals services; do printf '%s\n' "$realms" | grep -qx "$r" || { echo "realm $r not in the keycloak database: $realms"; return 1; }; done
}
check "keycloak realms stored in PostgreSQL (keycloak database, keycloak role)" kc_realms_in_postgres

# ---- APISIX health route over the published HTTPS port ----
https_port="$(dc port apisix 9443 | head -1 | sed 's/.*://')"
check "apisix https://localhost:$https_port/healthz -> 200" \
  curl -skf --max-time 10 "https://localhost:$https_port/healthz"

# ---- rate-limiter degradation alert (ticket 50) ----
# limit-count fails open when Valkey is down; deploy/apisix/lua/edge-limit-degraded.lua logs
# every request it let through unlimited as "mw.edge.rate_limit_degraded" (deploy/apisix/README.md).
# Last SMOKE_LIMITER_WINDOW (default 5m), so the brief outage make edge-test causes ages out.
limiter_not_degraded() {
  local lines
  lines="$(dc logs --no-log-prefix --since "${SMOKE_LIMITER_WINDOW:-5m}" apisix 2>&1 | grep 'mw.edge.rate_limit_degraded')"
  [ -z "$lines" ] || { echo "$(printf '%s\n' "$lines" | wc -l | tr -d ' ') request(s) passed without rate limiting (Valkey unreachable?); last:"; printf '%s\n' "$lines" | tail -3; return 1; }
}
check "apisix rate limiter not degraded (no mw.edge.rate_limit_degraded in the APISIX log, last ${SMOKE_LIMITER_WINDOW:-5m})" limiter_not_degraded

# ---- APISIX Admin API and etcd isolation ----
net_of() { docker network ls --format '{{.Name}}' --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.network=$1" | head -1; }
app_net="$(net_of app)"
# admin_status NETWORK: HTTP status of an authenticated Admin API call made from a
# throwaway container on NETWORK (000 when unreachable)
admin_status() {
  docker run --rm --network "$1" "$CURL_IMAGE" -s -o /dev/null -w '%{http_code}' --max-time 5 \
    -H "X-API-KEY: $APISIX_ADMIN_KEY" http://apisix:9180/apisix/admin/routes
}
expect_status() { local want="$1" got; shift; got="$("$@")"; [ "$got" = "$want" ] || { echo "got HTTP $got, want $want"; return 1; }; }
check "apisix admin API answers callers on the ops subnet (200)" expect_status 200 admin_status "$ops_net"
check "apisix admin API refuses callers on the app network (403)" expect_status 403 admin_status "$app_net"
host_admin() { ! curl -s -o /dev/null --max-time 3 http://127.0.0.1:9180/apisix/admin/routes; }
check "apisix admin API not reachable from the host (127.0.0.1:9180)" host_admin
no_etcd_from_ops() { ! docker run --rm --network "$ops_net" "$CURL_IMAGE" -s --max-time 5 http://etcd:2379/version; }
check "etcd not reachable from the ops network" no_etcd_from_ops
unauth_read() { local out; out="$(dc exec -T -e ETCDCTL_USER= etcd /usr/local/bin/etcdctl get /apisix --prefix --keys-only 2>&1)" && { echo "unauthenticated read succeeded: $out"; return 1; }; echo "$out" | grep -qiE 'user name is empty|permission denied|authentication'; }
check "etcd rejects unauthenticated reads" unauth_read
apisix_read() { dc exec -T -e "ETCDCTL_USER=apisix:$ETCD_APISIX_PASSWORD" etcd /usr/local/bin/etcdctl get /apisix/routes --prefix --keys-only | grep -q '^/apisix/routes/'; }
check "etcd apisix user reads /apisix" apisix_read
apisix_outside() { local out; out="$(dc exec -T -e "ETCDCTL_USER=apisix:$ETCD_APISIX_PASSWORD" etcd /usr/local/bin/etcdctl get /other 2>&1)" && return 1; echo "$out" | grep -q "permission denied"; }
check "etcd apisix user is denied outside /apisix" apisix_outside

printf '\nsmoke: %d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
