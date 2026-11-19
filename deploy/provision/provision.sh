#!/bin/sh
# provision: the dev stack's one provisioning one-shot (dev/MVP only; production provisions roles,
# secrets and AppRoles outside the stack). For every service of services.sh, in order:
#   1. Vault KV secret/<svc>: generated values created once and never overwritten, deploy/.env
#      values synced on every run, values copied from another service's secret;
#   2. PostgreSQL: the service's login role(s) and schema (passwords synced from the secret);
#   3. Vault policy + AppRole <svc> reading only that secret (and issuing from its own pki_int
#      role, for a PKI_SERVICES entry); the role-id and a fresh secret-id go to OUT_ROOT/<dir>, the
#      service's own approle volume (Spring configtree).
# Before them: the PKI of mTLS phase 1 (root and intermediate CA once, a role per PKI_SERVICES entry).
# Then the SECRETS_AHEAD entries of services.sh: KV values only, for a service not in the stack yet.
# Each service still gets only its own secret, role and AppRole; only this one-shot holds the
# Vault root token and the PostgreSQL admin password, and it exits when done.
# Compose starts it once postgres, vault and keycloak are healthy, so nothing here polls.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"
. "$HERE/services.sh"

: "${PGHOST:?}" "${PGUSER:?}" "${PGDATABASE:?}" "${PGPASSWORD:?}" "${VAULT_ADDR:?}"
OUT_ROOT="${OUT_ROOT:-/out}"

vault_login
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

pki_setup
for s in $PKI_SERVICES; do pki_role "$s"; done

n=0
for entry in $SERVICES; do
  SVC="${entry%%:*}"
  KV="secret/$SVC"
  OUT="$OUT_ROOT/${entry#*:}"
  "provision_$(printf '%s' "$SVC" | tr '-' '_')"
  approle
  n=$((n + 1))
done
for entry in ${SECRETS_AHEAD:-}; do
  SVC="${entry%%:*}"
  KV="secret/$SVC"
  "provision_${entry#*:}"
done
log "$n services provisioned"
