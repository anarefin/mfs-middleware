#!/bin/sh
# provision: the dev stack's one provisioning one-shot (dev/MVP only; production provisions roles,
# secrets and AppRoles outside the stack). For every service of services.sh, in order:
#   1. OpenBao KV secret/<svc>: generated values created once and never overwritten, deploy/.env
#      values synced on every run, values copied from another service's secret;
#   2. PostgreSQL: the service's login role(s) and schema (passwords synced from the secret);
#   3. OpenBao policy + AppRole <svc> reading only that secret; the role-id and a fresh secret-id
#      go to OUT_ROOT/<dir>, the service's own approle volume (Spring configtree).
# Each service still gets only its own secret, role and AppRole; only this one-shot holds the
# OpenBao root token and the PostgreSQL admin password, and it exits when done.
# Compose starts it once postgres and openbao are healthy, so nothing here polls.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"
. "$HERE/services.sh"

: "${PGHOST:?}" "${PGUSER:?}" "${PGDATABASE:?}" "${PGPASSWORD:?}" "${BAO_ADDR:?}"
OUT_ROOT="${OUT_ROOT:-/out}"

bao_login
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

n=0
for entry in $SERVICES; do
  SVC="${entry%%:*}"
  KV="secret/$SVC"
  OUT="$OUT_ROOT/${entry#*:}"
  "provision_$(printf '%s' "$SVC" | tr '-' '_')"
  approle
  n=$((n + 1))
done
log "$n services provisioned"
