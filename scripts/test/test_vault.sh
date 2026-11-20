# HashiCorp Vault replaces OpenBao (FRS 1.4, ADR D30, ticket 91): the name is gone from everything but
# the decision records, the image is pinned by digest, and the dev unseal plus deploy/provision work
# against a real Vault container (skipped when Docker is not available).

VAULT_IMAGE_RE='hashicorp/vault:2\.2\.2@sha256:[0-9a-f]{64}'

# the pinned Vault image of the compose service `vault`
vault_image() { grep -oE "image: $VAULT_IMAGE_RE" "$REPO_DIR/deploy/docker-compose.yml" | head -1 | sed 's/^image: //'; }

# Decision records keep the history (docs/adr, the spec's decision tables and revision notes, the
# ticket notes); applied Flyway migrations never change (their checksum). Nothing else names OpenBao.
test_no_openbao_left_outside_the_decision_records() {
  local hits
  hits="$(cd "$REPO_DIR" && git ls-files -co --exclude-standard \
    | grep -vE '^(docs/adr/|spec/|\.scratch/|\.claude/)|/db/migration/|^docs/frs-traceability\.md$|^scripts/test/test_vault\.sh$' \
    | while IFS= read -r f; do [ -f "$f" ] && printf '%s\n' "$f"; done \
    | tr '\n' '\0' | xargs -0 grep -IinE 'openbao|(^|[^A-Za-z0-9_])bao([^A-Za-z0-9]|$)|(^|[^A-Za-z0-9_])BAO_' 2>/dev/null | head -20)"
  [ -z "$hits" ] || fail "OpenBao still named:
$hits"
}

test_vault_image_is_pinned_by_digest_in_compose_and_provision() {
  [ -n "$(vault_image)" ] || fail "compose has no pinned hashicorp/vault:2.2.2@sha256 image"
  grep -qE "^FROM $VAULT_IMAGE_RE AS vault$" "$REPO_DIR/deploy/provision/Dockerfile" \
    || fail "deploy/provision/Dockerfile does not copy the CLI from the pinned Vault image"
  [ "$(grep -oE "$VAULT_IMAGE_RE" "$REPO_DIR/deploy/provision/Dockerfile")" = "$(vault_image)" ] \
    || fail "provision and the vault service pin different images"
}

# ---- real Vault (Docker) ----

docker_ok() { command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; }

# vault_fixture: a Vault server started the way compose starts it (deploy/vault/config.hcl, the
# dev-unseal.sh entrypoint, a fresh data volume), healthy once dev-ready exists. Sets V (container),
# VOL (volume), IMG. Cleaned up on exit.
vault_fixture() {
  IMG="$(vault_image)"; V="mwtest-vault-$$-$RANDOM"; VOL="$V-data"
  trap 'docker rm -f "$V" "$V-prov" >/dev/null 2>&1; docker volume rm -f "$VOL" >/dev/null 2>&1' EXIT
  docker volume create "$VOL" >/dev/null
  docker create --name "$V" -v "$VOL:/vault/file" -e VAULT_ADDR=http://127.0.0.1:8200 \
    -e VAULT_DEV_ROOT_TOKEN=dev-root-t -e SKIP_SETCAP=true --entrypoint /bin/sh "$IMG" /vault/dev/dev-unseal.sh >/dev/null
  docker cp "$REPO_DIR/deploy/vault/config.hcl" "$V:/vault/config/config.hcl"
  # /vault/dev does not exist in the image: docker cp creates it from a local directory
  mkdir -p vdev && cp "$REPO_DIR/deploy/vault/dev-unseal.sh" vdev/
  docker cp vdev "$V:/vault/dev"
  docker start "$V" >/dev/null
  local i=0
  until docker exec "$V" sh -c 'vault status >/dev/null && test -f /vault/file/dev-ready' 2>/dev/null; do
    i=$((i + 1)); [ "$i" -lt 60 ] || { OUT="$(docker logs "$V" 2>&1)"; fail "vault not ready"; }
    sleep 1
  done
}

# vx CMD...: a vault command in the server container as the dev tester token
vx() { docker exec -e VAULT_TOKEN=dev-root-t "$V" vault "$@"; }

# run_real_provision: deploy/provision/provision.sh in a container of the same image on the server's
# network namespace, as compose runs it (root token from the init file in the data volume), with psql,
# openssl and curl (Keycloak) stubbed. OUT = its output; ./out = the approle volumes.
run_real_provision() {
  mkdir -p stub
  printf '#!/bin/sh\ncat >/dev/null\n' >stub/psql
  cat >stub/openssl <<'EOF'
#!/bin/sh
out=""; keyout=""; while [ $# -gt 0 ]; do [ "$1" = -out ] && out="$2"; [ "$1" = -keyout ] && keyout="$2"; shift; done
[ -n "$keyout" ] && printf 'PARTNER-KEY-PEM' >"$keyout"
[ -n "$out" ] && printf 'PARTNER-CERT-PEM' >"$out"
exit 0
EOF
  cat >stub/curl <<'EOF'
#!/bin/sh
case "$*" in
  *"/realms/master/protocol/openid-connect/token"*) printf '{"access_token":"kc-at"}' ;;
  *"/admin/realms/partners/clients?clientId=partner-sim"*) printf '[{"id":"cid","clientId":"partner-sim"}]' ;;
  *"/upload-certificate"*) printf '{}' ;;
  *) exit 22 ;;
esac
EOF
  chmod +x stub/*
  docker rm -f "$V-prov" >/dev/null 2>&1
  # shellcheck disable=SC2086
  docker create --name "$V-prov" --user 0 --network "container:$V" -v "$VOL:/vault/file:ro" \
    -e PATH=/stub:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    -e VAULT_ADDR=http://127.0.0.1:8200 -e INIT_FILE=/vault/file/dev-init.json -e OUT_ROOT=/out \
    -e PGHOST=pg -e PGUSER=admin -e PGDATABASE=mw -e PGPASSWORD=pg-t \
    $(printf -- '-e %s ' $PROVISION_ENV) --entrypoint /bin/sh "$IMG" /provision/provision.sh >/dev/null
  docker cp "$REPO_DIR/deploy/provision/." "$V-prov:/provision"
  docker cp stub "$V-prov:/stub"
  OUT="$(docker start -a "$V-prov" 2>&1)"; local rc=$?
  rm -rf out; docker cp "$V-prov:/out" out 2>/dev/null
  return $rc
}

PROVISION_ENV="REDIS_PASSWORD=redis-t BILLAGG_HMAC_SECRET=billagg-t BILLAGG_WS_PASSWORD=billagg-ws-t C2WBANK_HMAC_SECRET=c2w-t PUSH_API_KEY=push-t
  CHECKOUT_DEMO_MERCHANT_SECRET=merchant-t CHECKOUT_PHARMACY_MERCHANT_SECRET=pharmacy-t CHECKOUT_FASHION_MERCHANT_SECRET=fashion-t
  KC_MW_ADMIN_API_SECRET=admin-t KC_MW_PORTAL_API_SECRET=portal-t KC_MW_AUDIT_DW_SECRET=audit-dw-t KC_MW_DATA_EXPORT_SECRET=data-export-t KC_MW_AUTH_SECRET=auth-t
  KC_MW_APP_GATEWAY_SECRET=app-gateway-t KC_MW_USSD_SECRET=ussd-t KC_MW_CHECKOUT_SECRET=checkout-t KC_MW_ORCHESTRATOR_SECRET=orchestrator-t KC_MW_SIMULATORS_SECRET=simulators-t SFTP_DEV_PASSWORD=sftp-t
  USSD_GP_GATEWAY_SECRET=gp-t USSD_ROBI_GATEWAY_SECRET=robi-t USSD_BL_GATEWAY_SECRET=bl-t USSD_TELETALK_GATEWAY_SECRET=teletalk-t
  SOFTHSM_MWCRYPTO_USER_PIN=hsm-pin-t SOFTHSM_PAYSIM_USER_PIN=paysim-pin-t
  APISIX_VAULT_TOKEN=apisix-vault-t APISIX_ADMIN_KEY=apisix-admin-t
  KC_URL=http://kc:8080 KC_BOOTSTRAP_ADMIN_USERNAME=kc-admin KC_BOOTSTRAP_ADMIN_PASSWORD=kc-admin-t"

# approle_token <dir>: log in with the role-id / secret-id provision wrote to out/<dir>
approle_token() {
  docker exec "$V" vault write -field=token auth/approle/login \
    role_id="$(cat "out/$1/spring.cloud.vault.app-role.role-id")" secret_id="$(cat "out/$1/spring.cloud.vault.app-role.secret-id")"
}

test_dev_unseal_and_provision_work_against_a_real_vault() {
  docker_ok || { echo "    skipped: Docker not available" >&2; return 0; }
  vault_fixture
  # dev-unseal.sh: engines, the smoke secret and the fixed tester token
  [ "$(vx kv get -field=value secret/mw/smoke)" = ok ] || fail "no secret/mw/smoke"
  vx auth list | grep -q '^approle/' || fail "approle auth not enabled"
  run_real_provision || fail "provision failed"
  assert_out_contains "provision: 16 services provisioned"
  local entry s d
  for entry in $(. "$REPO_DIR/deploy/provision/services.sh"; printf '%s\n' $SERVICES); do
    s="${entry%%:*}"; d="${entry#*:}"
    vx policy read "$s" >/dev/null || fail "$s: no policy"
    vx read "auth/approle/role/$s" >/dev/null || fail "$s: no AppRole"
    [ "$(vx kv get -field=spring.datasource.password "secret/$s" | wc -c | tr -d ' ')" = 40 ] || fail "$s: no 40-char database password"
    [ -s "out/$d/spring.cloud.vault.app-role.secret-id" ] || fail "$s: no secret-id in out/$d"
  done
  for s in $(. "$REPO_DIR/deploy/provision/services.sh"; printf '%s\n' $PKI_SERVICES); do
    vx read "pki_int/roles/$s" >/dev/null || fail "no PKI role $s"
  done
  # per-partner secrets of the adapters (ADR D26/D28) and the simulators' keys
  [ "$(vx kv get -field=hmac-secret secret/mw-adapter-billpay/billagg)" = billagg-t ] || fail "billpay partner secret"
  [ "$(vx kv get -field=ws-password secret/mw-adapter-billpay/billagg)" = billagg-ws-t ] || fail "billpay ws-password"
  [ "$(vx kv get -field=hmac-secret secret/mw-adapter-bank/c2wbank)" = c2w-t ] || fail "bank partner secret"
  [ "$(vx kv get -field=sim.partner-sim.private-key secret/mw-simulators)" = PARTNER-KEY-PEM ] || fail "partner-sim key"
  # the AppRole of mw-crypto reads its own secret and issues its own certificate, nothing else
  local t
  t="$(approle_token crypto)" || fail "mw-crypto AppRole login failed"
  docker exec -e VAULT_TOKEN="$t" "$V" vault kv get -field=mw.crypto.pkcs11.user-pin secret/mw-crypto | grep -qx hsm-pin-t \
    || fail "mw-crypto cannot read its secret"
  docker exec -e VAULT_TOKEN="$t" "$V" vault kv get secret/mw-auth >/dev/null 2>&1 && fail "mw-crypto reads secret/mw-auth"
  docker exec -e VAULT_TOKEN="$t" "$V" vault write -field=certificate pki_int/issue/mw-crypto common_name=mw-crypto ttl=5m \
    | grep -q 'BEGIN CERTIFICATE' || fail "mw-crypto cannot issue its certificate"
  docker exec -e VAULT_TOKEN="$t" "$V" vault write pki_int/issue/mw-auth common_name=mw-auth ttl=5m >/dev/null 2>&1 \
    && fail "mw-crypto issues mw-auth's certificate"
  # idempotent: a second run keeps the CA and the generated secrets
  local ca pw
  ca="$(vx read -field=certificate pki_int/cert/ca)"; pw="$(vx kv get -field=spring.datasource.password secret/mw-orchestrator)"
  run_real_provision || fail "second provision run failed"
  [ "$(vx read -field=certificate pki_int/cert/ca)" = "$ca" ] || fail "intermediate CA replaced"
  [ "$(vx kv get -field=spring.datasource.password secret/mw-orchestrator)" = "$pw" ] || fail "database password regenerated"
}

# Ticket 100a: APISIX's Vault secret manager speaks KV v1 only, so provision mounts apisix/ as KV
# version 1 with the edge credentials, and the fixed APISIX token reads that mount and nothing else.
test_provision_gives_apisix_a_kv_v1_mount_and_a_read_only_token() {
  docker_ok || { echo "    skipped: Docker not available" >&2; return 0; }
  vault_fixture
  run_real_provision || fail "provision failed: $OUT"
  vx secrets list -format=json | jq -e '."apisix/".type == "kv" and ((."apisix/".options.version // "1") == "1")' >/dev/null \
    || fail "apisix/ is not a KV v1 mount: $(vx secrets list -format=json | jq -c '."apisix/"')"
  [ "$(vx read -field=password apisix/ussd-gp)" = gp-t ] || fail "apisix/ussd-gp password not synced"
  [ "$(vx read -field=password apisix/ussd-teletalk)" = teletalk-t ] || fail "apisix/ussd-teletalk password not synced"
  [ "$(vx kv get -field=mw.apisix.admin-key secret/mw-admin-api)" = apisix-admin-t ] || fail "admin key not in secret/mw-admin-api"
  # what APISIX does: GET /v1/apisix/<name> with its token
  docker exec -e VAULT_TOKEN=apisix-vault-t "$V" vault read -field=password apisix/ussd-robi | grep -qx robi-t \
    || fail "the APISIX token cannot read apisix/"
  docker exec -e VAULT_TOKEN=apisix-vault-t "$V" vault kv get secret/mw-admin-api >/dev/null 2>&1 \
    && fail "the APISIX token reads secret/"
  docker exec -e VAULT_TOKEN=apisix-vault-t "$V" vault write apisix/ussd-gp password=x >/dev/null 2>&1 \
    && fail "the APISIX token may write apisix/"
  # idempotent: a second run renews the same token
  run_real_provision || fail "second provision run failed: $OUT"
  docker exec -e VAULT_TOKEN=apisix-vault-t "$V" vault read -field=password apisix/ussd-gp | grep -qx gp-t \
    || fail "the APISIX token is gone after a second run"
}
