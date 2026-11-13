#!/bin/sh
# OpenBao entrypoint for the dev/MVP stack: starts the server, then initialises it on
# first start and unseals it on every start, so `docker compose up` needs no manual step.
#
# DEV ONLY. The single unseal key and the root token are written to the openbao-data
# volume (/openbao/file/dev-init.json), next to the data they protect. Production
# unseals with a KMS/HSM or Shamir key holders instead (spec 18.4).
#
# On first start it also enables the engines the services use (KV v2 at secret/,
# transit, PKI, database), writes secret/mw/smoke for the smoke test and creates the fixed,
# documented tester token `dev-root-token` (root policy, no expiry; ADR D9 amendment,
# docs/testing-guide.html 1.6), recorded in /openbao/file/dev-token for scripts/creds.sh. Then (every start) it
# touches /openbao/file/dev-ready, which the compose healthcheck requires.
set -eu

export BAO_ADDR="http://127.0.0.1:8200"
INIT_FILE=/openbao/file/dev-init.json

docker-entrypoint.sh server -config=/openbao/config/config.hcl &
server=$!
trap 'kill -TERM "$server" 2>/dev/null; wait "$server"' TERM INT

# `bao status` exits 0 unsealed, 2 sealed, 1 unreachable
i=0
until bao status >/dev/null 2>&1 || [ $? -eq 2 ]; do
  i=$((i + 1)); [ "$i" -lt 60 ] || { echo "dev-unseal: server did not start" >&2; exit 1; }
  sleep 1
done

json_field() { sed -n "s/.*\"$1\": *\"\([^\"]*\)\".*/\1/p" "$INIT_FILE" | head -1; }

first_start=0
if ! bao status -format=json 2>/dev/null | grep -q '"initialized": *true'; then
  echo "dev-unseal: initialising (1 key share, dev only)"
  ( umask 077; bao operator init -key-shares=1 -key-threshold=1 -format=json \
      | tr -d '\n' | sed 's/"unseal_keys_b64": *\[ *"\([^"]*\)"[^]]*\]/"unseal_key": "\1"/' >"$INIT_FILE" )
  chown openbao:openbao "$INIT_FILE"
  first_start=1
fi

if bao status >/dev/null 2>&1; then :; else
  bao operator unseal "$(json_field unseal_key)" >/dev/null
  echo "dev-unseal: unsealed"
fi

if [ "$first_start" = 1 ]; then
  BAO_TOKEN="$(json_field root_token)"; export BAO_TOKEN
  i=0
  until bao secrets list >/dev/null 2>&1; do
    i=$((i + 1)); [ "$i" -lt 30 ] || { echo "dev-unseal: not active" >&2; exit 1; }
    sleep 1
  done
  bao secrets enable -path=secret -version=2 kv
  bao secrets enable transit
  bao secrets enable pki
  bao secrets enable database
  bao auth enable approle
  bao kv put secret/mw/smoke value=ok >/dev/null
  bao token create -id=dev-root-token -policy=root -orphan -display-name=dev-tester >/dev/null
  ( umask 077; printf 'dev-root-token\n' >/openbao/file/dev-token )
  chown openbao:openbao /openbao/file/dev-token
  echo "dev-unseal: engines enabled (kv v2 at secret/, transit, pki, database, approle), tester token created"
fi

# Ready marker for the compose healthcheck (with `bao status`): unsealed and the engines exist,
# so the provision one-shot (depends_on openbao: service_healthy) never polls.
touch /openbao/file/dev-ready

wait "$server"
