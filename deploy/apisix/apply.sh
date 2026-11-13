#!/usr/bin/env bash
# One-shot apisix-init (runs in the APISIX image, which has bash, awk and openssl but no curl):
#   1. creates a self-signed edge certificate for localhost on first run (dev only; kept in
#      the apisix-tls volume so it survives restarts; replaced by a real cert later)
#   2. PUTs it as SSL object "edge-default"
#   3. PUTs every deploy/apisix/global_rules/<id>.json as global rule <id>, every
#      deploy/apisix/consumers/<username>.json as consumer <username> (the MNO USSD gateways),
#      then every deploy/apisix/routes/<id>.json as route <id>; then DELETEs every object listed
#      in deploy/apisix/retired.txt (a file that was removed or renamed)
#   4. test-only objects for the host API suite (tests/api): with MW_E2E_ROUTES=1 PUTs
#      consumers-test/<username>.json and routes-test/<id>.json; without it DELETEs them, so a
#      plain `docker compose up` never leaves a test route behind (scripts/api-test.sh re-runs this with the flag)
# Before a PUT, string values "@lua:<path>" are replaced by the file at <path> (relative to
# deploy/apisix) as a JSON string (serverless functions); "@env:<NAME>" by that environment
# variable as a JSON string (secrets such as the e2e key, never in the repo); "@list:<NAME>" by
# that comma-separated variable as a JSON array of strings (address lists such as an MNO's
# allow-list; entries may hold only hex digits, '.', ':' and '/'). An unset or empty variable
# fails the apply. Body schemas are never checked here: each service validates its own body.
#
# Idempotent: PUT with a fixed id stores the same object again, so a second run leaves the
# configuration as it was (only update_time moves). Objects are deleted only when listed in
# retired.txt (or test-only, above): a removed file must be added there (see README.md).
#
# Env: APISIX_ADMIN (host:port), APISIX_ADMIN_KEY; MW_E2E_ROUTES (1 = apply the test-only
# objects), MW_E2E_API_KEY (their consumer key, required with MW_E2E_ROUTES=1);
# USSD_<MNO>_GATEWAY_SECRET and USSD_<MNO>_ALLOWED_CIDRS (gp, robi, bl, teletalk; required).
# APISIX_DRY_RUN=<dir>: no network, no certificate; writes each body to <dir>/<kind>/<id>.json.
# APISIX_CONFIG_DIR: the folder holding routes/, consumers/, global_rules/, lua/ (default: here).
set -euo pipefail

dir="${APISIX_CONFIG_DIR:-$(cd "$(dirname "$0")" && pwd)}"
dry="${APISIX_DRY_RUN:-}"
admin="${APISIX_ADMIN:-apisix:9180}"
host="${admin%:*}"; port="${admin#*:}"
tls="${APISIX_TLS_DIR:-/usr/local/apisix/conf/cert}"

# render FILE: FILE with every "@lua:<path>" / "@env:<NAME>" / "@list:<NAME>" string replaced.
# Plain awk (mawk in the image, BSD awk on macOS) and no gsub on the replacement text, so
# backslashes and '&' in Lua code survive unchanged.
render() {
  awk -v base="$dir" '
    function slurp(path,   line, s, n, rc) {
      s = ""; n = 0
      while ((rc = (getline line < path)) > 0) { s = s (n++ ? "\n" : "") line }
      if (rc < 0) { printf "apisix-init: %s: include %s not found\n", FILENAME, path > "/dev/stderr"; failed = 1; exit 1 }
      close(path); return s
    }
    function jstr(s,   out, i, c) {
      out = ""
      for (i = 1; i <= length(s); i++) {
        c = substr(s, i, 1)
        if (c == "\\") out = out "\\\\"
        else if (c == "\"") out = out "\\\""
        else if (c == "\n") out = out "\\n"
        else if (c == "\t") out = out "\\t"
        else if (c == "\r") out = out "\\r"
        else out = out c
      }
      return "\"" out "\""
    }
    {
      line = $0; res = ""
      while (match(line, /"@(lua|env|list):[^"]*"/)) {
        tok = substr(line, RSTART + 1, RLENGTH - 2)
        res = res substr(line, 1, RSTART - 1)
        line = substr(line, RSTART + RLENGTH)
        if (substr(tok, 1, 5) == "@lua:") res = res jstr(slurp(base "/" substr(tok, 6)))
        else if (substr(tok, 1, 5) == "@env:") {
          name = substr(tok, 6)
          if (ENVIRON[name] == "") { printf "apisix-init: %s: %s is not set\n", FILENAME, name > "/dev/stderr"; failed = 1; exit 1 }
          res = res jstr(ENVIRON[name])
        }
        else if (substr(tok, 1, 6) == "@list:") {
          name = substr(tok, 7); v = ENVIRON[name]; gsub(/[ \t]/, "", v)
          if (v == "") { printf "apisix-init: %s: %s is not set\n", FILENAME, name > "/dev/stderr"; failed = 1; exit 1 }
          n = split(v, items, ","); arr = ""
          for (i = 1; i <= n; i++) {
            if (items[i] !~ /^[0-9A-Fa-f.:\/]+$/) { printf "apisix-init: %s: %s entry %d is not an address\n", FILENAME, name, i > "/dev/stderr"; failed = 1; exit 1 }
            arr = arr (i > 1 ? "," : "") "\"" items[i] "\""
          }
          res = res "[" arr "]"
        }
        else { printf "apisix-init: %s: unknown include %s\n", FILENAME, tok > "/dev/stderr"; failed = 1; exit 1 }
      }
      print res line
    }
    END { if (failed) exit 1 }
  ' "$1"
}

# put PATH BODY [METHOD]: HTTP PUT (or METHOD) to the Admin API over bash /dev/tcp; prints the
# status code on the first line, then the response (headers and body, for the error message).
put() {
  local path="$1" body="$2" method="${3:-PUT}" status
  exec 3<>"/dev/tcp/$host/$port"
  printf '%s %s HTTP/1.1\r\nHost: %s\r\nX-API-KEY: %s\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s' \
    "$method" "$path" "$host" "$key" "$(printf '%s' "$body" | wc -c)" "$body" >&3
  read -r _ status _ <&3
  printf '%s\n' "$status"
  cat <&3
  exec 3<&-
}

apply() {
  local path="$1" body="$2" status try resp
  if [ -n "$dry" ]; then
    mkdir -p "$dry/$(dirname "${path#/apisix/admin/}")"
    printf '%s\n' "$body" >"$dry/${path#/apisix/admin/}.json"
    echo "apisix-init: (dry run) PUT $path"
    return 0
  fi
  # 5xx right after an APISIX or etcd restart (etcd connection still re-authenticating)
  # is retried for up to a minute; anything else fails at once
  for try in $(seq 1 30); do
    resp="$(put "$path" "$body")"
    status="${resp%%$'\n'*}"
    case "$status" in
      200|201) echo "apisix-init: PUT $path -> $status"; return 0 ;;
      5*) sleep 2 ;;
      *) break ;;
    esac
  done
  # the Admin API's reason is a JSON {"error_msg": ...} line; it never echoes the key
  echo "apisix-init: PUT $path failed with '$status': $(printf '%s' "$resp" | grep -a 'error_msg' | head -1)" >&2
  return 1
}

# remove PATH: DELETE an object if it exists (404 is fine).
remove() {
  local path="$1" resp status
  if [ -n "$dry" ]; then
    echo "apisix-init: (dry run) DELETE $path"
    return 0
  fi
  resp="$(put "$path" "" DELETE)"
  status="${resp%%$'\n'*}"
  case "$status" in
    200|404) echo "apisix-init: DELETE $path -> $status" ;;
    *) echo "apisix-init: DELETE $path failed with '$status': $(printf '%s' "$resp" | grep -a 'error_msg' | head -1)" >&2; return 1 ;;
  esac
}

if [ -z "$dry" ]; then
  key="${APISIX_ADMIN_KEY:?APISIX_ADMIN_KEY not set}"
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
fi

# global rules first: they hold the correlation ID and error envelope every route relies on;
# consumers before the routes that name them
for kind in global_rules consumers routes; do
  for f in "$dir/$kind"/*.json; do
    [ -f "$f" ] || continue
    body="$(render "$f")"
    apply "/apisix/admin/$kind/$(basename "$f" .json)" "$body"
  done
done

# retired objects: "<kind>/<id>" per line (# comments), deleted on every run (404 is fine)
if [ -f "$dir/retired.txt" ]; then
  while read -r obj _; do
    case "$obj" in ''|'#'*) continue ;; esac
    remove "/apisix/admin/$obj"
  done <"$dir/retired.txt"
fi

# test-only objects (host API suite): consumers first, then the routes that name them
if [ "${MW_E2E_ROUTES:-0}" = 1 ]; then
  [ -n "${MW_E2E_API_KEY:-}" ] || { echo "apisix-init: MW_E2E_ROUTES=1 needs MW_E2E_API_KEY (deploy/dev.env.defaults)" >&2; exit 1; }
  for kind in consumers routes; do
    for f in "$dir/$kind-test"/*.json; do
      [ -f "$f" ] || continue
      body="$(render "$f")"
      apply "/apisix/admin/$kind/$(basename "$f" .json)" "$body"
    done
  done
else
  for kind in routes consumers; do
    for f in "$dir/$kind-test"/*.json; do
      [ -f "$f" ] || continue
      remove "/apisix/admin/$kind/$(basename "$f" .json)"
    done
  done
fi
