#!/usr/bin/env bash
# One-shot apisix-init (runs in the APISIX image, which has bash and openssl but no curl):
#   1. creates a self-signed edge certificate for localhost on first run (dev only; kept in
#      the apisix-tls volume so it survives restarts; replaced by a real cert later)
#   2. PUTs it as SSL object "edge-default"
#   3. PUTs every deploy/apisix/routes/<id>.json as route <id>
# Idempotent: PUT with a fixed id overwrites. Env: APISIX_ADMIN (host:port), APISIX_ADMIN_KEY.
set -euo pipefail

admin="${APISIX_ADMIN:-apisix:9180}"
host="${admin%:*}"; port="${admin#*:}"
key="${APISIX_ADMIN_KEY:?APISIX_ADMIN_KEY not set}"
dir="$(cd "$(dirname "$0")" && pwd)"
tls="${APISIX_TLS_DIR:-/usr/local/apisix/conf/cert}"

# put PATH BODY: HTTP PUT to the Admin API over bash /dev/tcp; prints the status code.
put() {
  local path="$1" body="$2" status
  exec 3<>"/dev/tcp/$host/$port"
  printf 'PUT %s HTTP/1.1\r\nHost: %s\r\nX-API-KEY: %s\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s' \
    "$path" "$host" "$key" "$(printf '%s' "$body" | wc -c)" "$body" >&3
  read -r _ status _ <&3
  cat <&3 >/dev/null
  exec 3<&-
  printf '%s' "$status"
}

apply() {
  local path="$1" body="$2" status
  status="$(put "$path" "$body")"
  case "$status" in
    200|201) echo "apisix-init: PUT $path -> $status" ;;
    *) echo "apisix-init: PUT $path failed with '$status'" >&2; return 1 ;;
  esac
}

i=0
until (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null; do
  i=$((i + 1)); [ "$i" -lt 60 ] || { echo "apisix-init: admin API not reachable" >&2; exit 1; }
  sleep 1
done

if [ ! -s "$tls/edge.crt" ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 825 -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
    -keyout "$tls/edge.key" -out "$tls/edge.crt" 2>/dev/null
  echo "apisix-init: generated self-signed edge certificate (dev only)"
fi
json_pem() { awk '{printf "%s\\n", $0}' "$1"; }
apply /apisix/admin/ssls/edge-default \
  "{\"snis\":[\"localhost\"],\"cert\":\"$(json_pem "$tls/edge.crt")\",\"key\":\"$(json_pem "$tls/edge.key")\"}"

for f in "$dir"/routes/*.json; do
  apply "/apisix/admin/routes/$(basename "$f" .json)" "$(cat "$f")"
done
