#!/bin/sh
# One-shot etcd-init: turns on etcd authentication for the APISIX config store.
#   root user (role root) and an apisix user whose role may only read and write the
#   /apisix prefix; then auth enable. Idempotent: on later runs it logs in as root and
#   re-applies the users, grants and passwords from the env. A third user, healthcheck,
#   has no role and only serves the etcd container's health check.
# Runs in the curl image and talks to the etcd v3 JSON gateway (the etcd image is
# distroless: no shell to script etcdctl).
# Env: ETCD_ENDPOINT (default http://etcd:2379), ETCD_ROOT_PASSWORD, ETCD_APISIX_PASSWORD,
#      ETCD_HEALTH_PASSWORD
set -eu

ep="${ETCD_ENDPOINT:-http://etcd:2379}"
: "${ETCD_ROOT_PASSWORD:?ETCD_ROOT_PASSWORD not set}"
: "${ETCD_APISIX_PASSWORD:?ETCD_APISIX_PASSWORD not set}"
: "${ETCD_HEALTH_PASSWORD:?ETCD_HEALTH_PASSWORD not set}"
KEY_B64="L2FwaXNpeA=="        # "/apisix"
RANGE_END_B64="L2FwaXNpeQ=="  # "/apisiy": end of the /apisix prefix range
token=""

# call PATH JSON: POST to the gateway; succeeds on 200 or on an "already" error.
call() {
  out="$(curl -s -w '\n%{http_code}' -X POST ${token:+-H "Authorization: $token"} \
    -H 'Content-Type: application/json' -d "$2" "$ep$1")"
  code="$(printf '%s' "$out" | tail -n1)"; body="$(printf '%s' "$out" | sed '$d')"
  if [ "$code" = 200 ]; then echo "etcd-init: $1 ok"; return 0; fi
  case "$body" in *"already exists"*|*"already granted"*) echo "etcd-init: $1 already done"; return 0 ;; esac
  echo "etcd-init: $1 failed ($code): $body" >&2
  return 1
}

i=0
until curl -sf "$ep/version" >/dev/null; do
  i=$((i + 1)); [ "$i" -lt 60 ] || { echo "etcd-init: etcd not reachable" >&2; exit 1; }
  sleep 1
done

auth="$(curl -s -X POST -d "{\"name\":\"root\",\"password\":\"$ETCD_ROOT_PASSWORD\"}" "$ep/v3/auth/authenticate")"
case "$auth" in
  *'"token"'*) token="$(printf '%s' "$auth" | sed 's/.*"token":"\([^"]*\)".*/\1/')"; echo "etcd-init: auth already enabled, logged in as root" ;;
  *"not enabled"*) echo "etcd-init: auth not enabled yet, setting it up" ;;
  *) echo "etcd-init: root login failed: $auth" >&2
     echo "etcd-init: the etcd-data volume was set up with other secrets; run 'make down DOWN_ARGS=-v'" >&2
     exit 1 ;;
esac

call /v3/auth/role/add '{"name":"root"}'
call /v3/auth/user/add "{\"name\":\"root\",\"password\":\"$ETCD_ROOT_PASSWORD\"}"
call /v3/auth/user/grant '{"user":"root","role":"root"}'
call /v3/auth/role/add '{"name":"apisix"}'
call /v3/auth/role/grant "{\"name\":\"apisix\",\"perm\":{\"permType\":\"READWRITE\",\"key\":\"$KEY_B64\",\"range_end\":\"$RANGE_END_B64\"}}"
call /v3/auth/user/add "{\"name\":\"apisix\",\"password\":\"$ETCD_APISIX_PASSWORD\"}"
call /v3/auth/user/changepw "{\"name\":\"apisix\",\"password\":\"$ETCD_APISIX_PASSWORD\"}"
call /v3/auth/user/grant '{"user":"apisix","role":"apisix"}'
# healthcheck: no role at all, used only by the etcd container's health check
call /v3/auth/user/add "{\"name\":\"healthcheck\",\"password\":\"$ETCD_HEALTH_PASSWORD\"}"
call /v3/auth/user/changepw "{\"name\":\"healthcheck\",\"password\":\"$ETCD_HEALTH_PASSWORD\"}"
[ -n "$token" ] || call /v3/auth/enable '{}'
echo "etcd-init: auth enabled; apisix may only use the /apisix prefix"
