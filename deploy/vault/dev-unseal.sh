#!/bin/sh
# HashiCorp Vault entrypoint for the dev/MVP stack (ADR D30): starts the server, then initialises it
# on first start and unseals it on every start, so `docker compose up` needs no manual step.
#
# DEV ONLY. The single unseal key and the root token are written to the vault-data volume
# (/vault/file/dev-init.json), next to the data they protect. Production unseals with a KMS/HSM
# or Shamir key holders instead (spec 18.4).
#
# On first start it also enables the engines the services use (KV v2 at secret/,
# transit, PKI, database), writes secret/mw/smoke for the smoke test and creates the fixed,
# documented tester token `dev-root-token` (knob VAULT_DEV_ROOT_TOKEN in dev.env.defaults; root
# policy, no expiry; ADR D9 amendment,
# docs/testing-guide.html 1.6), recorded in /vault/file/dev-token for scripts/creds.sh. Then (every start) it
# touches /vault/file/dev-ready, which the compose healthcheck requires.
set -eu

export VAULT_ADDR="http://127.0.0.1:8200"
INIT_FILE=/vault/file/dev-init.json

# the image's entrypoint chowns /vault/file and drops to the vault user (su-exec)
docker-entrypoint.sh vault server -config=/vault/config/config.hcl &
server=$!
trap 'kill -TERM "$server" 2>/dev/null; wait "$server"' TERM INT

# `vault status` exits 0 unsealed, 2 sealed, 1 unreachable
i=0
until vault status >/dev/null 2>&1 || [ $? -eq 2 ]; do
  i=$((i + 1)); [ "$i" -lt 60 ] || { echo "dev-unseal: server did not start" >&2; exit 1; }
  sleep 1
done

json_field() { sed -n "s/.*\"$1\": *\"\([^\"]*\)\".*/\1/p" "$INIT_FILE" | head -1; }

first_start=0
if ! vault status -format=json 2>/dev/null | grep -q '"initialized": *true'; then
  echo "dev-unseal: initialising (1 key share, dev only)"
  ( umask 077; vault operator init -key-shares=1 -key-threshold=1 -format=json \
      | tr -d '\n' | sed 's/"unseal_keys_b64": *\[ *"\([^"]*\)"[^]]*\]/"unseal_key": "\1"/' >"$INIT_FILE" )
  chown vault:vault "$INIT_FILE"
  first_start=1
fi

if vault status >/dev/null 2>&1; then :; else
  vault operator unseal "$(json_field unseal_key)" >/dev/null
  echo "dev-unseal: unsealed"
fi

if [ "$first_start" = 1 ]; then
  VAULT_TOKEN="$(json_field root_token)"; export VAULT_TOKEN
  i=0
  until vault secrets list >/dev/null 2>&1; do
    i=$((i + 1)); [ "$i" -lt 30 ] || { echo "dev-unseal: not active" >&2; exit 1; }
    sleep 1
  done
  vault secrets enable -path=secret -version=2 kv
  vault secrets enable transit
  vault secrets enable pki
  vault secrets enable database
  vault auth enable approle
  vault kv put secret/mw/smoke value=ok >/dev/null
  dev_token="${VAULT_DEV_ROOT_TOKEN:-dev-root-token}"
  vault token create -id="$dev_token" -policy=root -orphan -display-name=dev-tester >/dev/null
  ( umask 077; printf '%s\n' "$dev_token" >/vault/file/dev-token )
  chown vault:vault /vault/file/dev-token
  echo "dev-unseal: engines enabled (kv v2 at secret/, transit, pki, database, approle), tester token created"
fi

# Ready marker for the compose healthcheck (with `vault status`): unsealed and the engines exist,
# so the provision one-shot (depends_on vault: service_healthy) never polls.
touch /vault/file/dev-ready

wait "$server"
