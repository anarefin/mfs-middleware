#!/usr/bin/env bash
# Smoke test for the running MVP Compose project (spec WP02). Run after `make up`.
# Usage: scripts/smoke.sh        (honours COMPOSE_PROJECT_NAME, default project "mw")
#
# Checks: every platform container healthy and the one-shots exited 0; only APISIX
# publishes a host port; every topic in deploy/kafka/topics.txt exists with RF 1 and the
# planned partition count; Kafka produce/consume; a PostgreSQL connection; a Valkey ping;
# a Keycloak token for every test user and the partner client; an OpenBao KV read; the
# APISIX /healthz route over HTTPS on the published port.
set -uo pipefail
. "$(dirname "$0")/lib/modules.sh"

file="$MW_ROOT/deploy/compose/mvp/compose.yml"
[ -f "$file" ] || die "smoke: deploy/compose/mvp/compose.yml does not exist"
command -v jq >/dev/null || die "smoke: jq is required"

CURL_IMAGE="curlimages/curl:8.17.0@sha256:935d9100e9ba842cdb060de42472c7ca90cfe9a7c96e4dacb55e79e560b3ff40"
LONG_RUNNING="kafka postgres valkey keycloak openbao etcd apisix greenmail"
ONE_SHOT="kafka-init apisix-init"

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
for realm_file in "$MW_ROOT"/deploy/compose/platform/keycloak/realms/*-realm.json; do
  realm="$(jq -r .realm "$realm_file")"
  client="$(jq -r '[.clients[] | select(.publicClient and .directAccessGrantsEnabled)][0].clientId // empty' "$realm_file")"
  while read -r user pw; do
    [ -n "$user" ] || continue
    check "keycloak token $realm/$user" token "$realm" grant_type=password "client_id=$client" "username=$user" "password=$pw"
  done < <(jq -r '.users[] | select(.credentials) | "\(.username) \(.credentials[0].value)"' "$realm_file")
  while read -r cid secret; do
    [ -n "$cid" ] || continue
    check "keycloak token $realm/$cid (client credentials)" token "$realm" grant_type=client_credentials "client_id=$cid" "client_secret=$secret"
  done < <(jq -r '.clients[] | select(.serviceAccountsEnabled) | "\(.clientId) \(.secret)"' "$realm_file")
done

# ---- APISIX health route over the published HTTPS port ----
https_port="$(dc port apisix 9443 | head -1 | sed 's/.*://')"
check "apisix https://localhost:$https_port/healthz -> 200" \
  curl -skf --max-time 10 "https://localhost:$https_port/healthz"

printf '\nsmoke: %d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
