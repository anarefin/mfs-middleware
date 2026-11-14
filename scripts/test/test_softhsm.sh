# deploy/softhsm (ticket 61, spec 18.8 / 22.2, ADR D23): the SoftHSMv2 container that serves the dev
# and test PKCS#11 tokens of mw-crypto and the payShield simulator over a p11-kit RPC socket, one TCP
# port per token. Static checks on the image, the token init script (softhsm2-util is stubbed), the
# compose block and the docs. The live check (`docker compose ... up --wait softhsm`) is manual.

SOFTHSM_DIR="$REPO_DIR/deploy/softhsm"
SOFTHSM_PIN_VARS="SOFTHSM_MWCRYPTO_SO_PIN SOFTHSM_MWCRYPTO_USER_PIN SOFTHSM_PAYSIM_SO_PIN SOFTHSM_PAYSIM_USER_PIN"

# softhsm_stub: a softhsm2-util on PATH keeping its tokens in .tokens/<label> and logging every call;
# --show-slots prints the padded "Label:" lines of the real tool.
softhsm_stub() {
  mkdir -p .bin .tokens
  cat >.bin/softhsm2-util <<'EOF'
#!/bin/sh
root="$(cd "$(dirname "$0")/.." && pwd)"
echo "softhsm2-util $*" >>"$root/.softhsm-calls"
case "$1" in
  --show-slots)
    echo "Available slots:"
    for t in "$root"/.tokens/*; do
      [ -f "$t" ] || continue
      printf 'Slot %s\n    Token info:\n        Label:            %-32s\n' "$(cat "$t")" "$(basename "$t")"
    done
    echo "Slot 99"; echo "    Token info:"; echo "        Label:                                            " ;;
  --init-token)
    label=""; shift
    while [ $# -gt 0 ]; do [ "$1" = --label ] && label="$2"; shift; done
    [ -n "$label" ] || exit 1
    echo "$$" >"$root/.tokens/$label"
    echo "The token has been initialized and is reassigned to slot $$" ;;
esac
EOF
  chmod +x .bin/softhsm2-util
}

TOKEN_ENV="SOFTHSM_MWCRYPTO_LABEL=mw-crypto SOFTHSM_MWCRYPTO_SO_PIN=so-a-t SOFTHSM_MWCRYPTO_USER_PIN=user-a-t
  SOFTHSM_PAYSIM_LABEL=paysim SOFTHSM_PAYSIM_SO_PIN=so-b-t SOFTHSM_PAYSIM_USER_PIN=user-b-t"

run_init() {
  # shellcheck disable=SC2086
  OUT="$(env PATH="$PWD/.bin:$PATH" $TOKEN_ENV "$@" sh "$SOFTHSM_DIR/init-tokens.sh" 2>&1)"
}

test_init_creates_both_tokens_on_a_fresh_volume() {
  softhsm_stub
  assert_ok run_init
  [ -f .tokens/mw-crypto ] || fail "mw-crypto token not created"
  [ -f .tokens/paysim ] || fail "paysim token not created"
  grep -q -- '--init-token --free --label mw-crypto --so-pin so-a-t --pin user-a-t' .softhsm-calls || fail "mw-crypto init call: $(cat .softhsm-calls)"
  grep -q -- '--init-token --free --label paysim --so-pin so-b-t --pin user-b-t' .softhsm-calls || fail "paysim init call"
  assert_out_lacks "so-a-t"; assert_out_lacks "user-a-t"; assert_out_lacks "user-b-t"
}

test_init_keeps_existing_tokens() {
  softhsm_stub
  assert_ok run_init
  local a b
  a="$(cat .tokens/mw-crypto)"; b="$(cat .tokens/paysim)"
  : >.softhsm-calls
  assert_ok run_init
  [ "$(cat .tokens/mw-crypto)" = "$a" ] && [ "$(cat .tokens/paysim)" = "$b" ] || fail "a token was re-initialised"
  if grep -q -- --init-token .softhsm-calls; then fail "second run initialised a token: $(cat .softhsm-calls)"; fi
  assert_out_contains "kept"
}

test_init_adds_only_the_missing_token() {
  softhsm_stub
  echo 7 >.tokens/mw-crypto
  assert_ok run_init
  [ "$(cat .tokens/mw-crypto)" = 7 ] || fail "existing mw-crypto token re-initialised"
  [ -f .tokens/paysim ] || fail "paysim not created"
  [ "$(grep -c -- --init-token .softhsm-calls)" = 1 ] || fail "expected one init: $(cat .softhsm-calls)"
}

test_init_does_not_match_a_label_prefix() {
  softhsm_stub
  echo 7 >.tokens/mw-crypto-old
  assert_ok run_init
  [ -f .tokens/mw-crypto ] || fail "token mw-crypto-old was taken for mw-crypto"
}

test_init_fails_without_a_pin_and_refuses_bad_labels() {
  softhsm_stub
  assert_fails run_init SOFTHSM_PAYSIM_USER_PIN=
  assert_out_contains "SOFTHSM_PAYSIM_USER_PIN"
  [ ! -d .tokens ] || [ -z "$(ls .tokens)" ] || fail "a token was created before the check failed"
  assert_fails run_init SOFTHSM_PAYSIM_LABEL=mw-crypto
  assert_out_contains "differ"
  assert_fails run_init SOFTHSM_PAYSIM_LABEL=abcdefghijklmnopqrstuvwxyz0123456
  assert_out_contains "32"
  assert_fails run_init SOFTHSM_MWCRYPTO_USER_PIN=abc
  assert_out_contains "4"
}

test_image_is_pinned_non_root_and_holds_no_secret() {
  local f="$SOFTHSM_DIR/Dockerfile" bad pkg
  [ -f "$f" ] || fail "no deploy/softhsm/Dockerfile"
  bad="$(grep -E '^FROM ' "$f" | grep -vE '@sha256:[0-9a-f]{64}( AS [a-z]+)?$')"
  [ -z "$bad" ] || fail "FROM not pinned by digest: $bad"
  # pinned SoftHSMv2, OpenSC (pkcs11-tool), p11-kit (remote + proxy) and socat versions
  for pkg in softhsm opensc p11-kit p11-kit-server socat; do
    grep -qE "(^|[[:space:]])$pkg=[0-9][^[:space:]]*" "$f" || fail "$pkg is not installed at a pinned version"
  done
  case "$(grep -E '^USER ' "$f" | tail -1 | awk '{print $2}')" in ''|root|0|0:*) fail "image runs as root" ;; esac
  bad="$(grep -nE '(PIN|SECRET|PASSWORD|TOKEN)[A-Z_]*=' "$f" || true)"
  [ -z "$bad" ] || fail "Dockerfile sets a secret-like value: $bad"
  grep -qE '^HEALTHCHECK .*healthcheck\.sh' "$f" || fail "no image HEALTHCHECK (Testcontainers waits on it)"
  grep -q -- '--list-slots' "$SOFTHSM_DIR/healthcheck.sh" && grep -q 'pkcs11-tool' "$SOFTHSM_DIR/healthcheck.sh" \
    || fail "healthcheck does not list the slots with pkcs11-tool"
}

test_each_token_has_its_own_port_and_the_healthcheck_goes_through_the_socket() {
  local m
  grep -q 'p11-kit remote' "$SOFTHSM_DIR/remote-token.sh" || fail "remote-token.sh does not serve p11-kit remote"
  grep -q 'pkcs11:token=' "$SOFTHSM_DIR/remote-token.sh" || fail "remote-token.sh does not serve a single token"
  grep -qE 'TCP-LISTEN:5657.*SOFTHSM_MWCRYPTO_LABEL' "$SOFTHSM_DIR/serve.sh" || fail "mw-crypto token not on port 5657"
  grep -qE 'TCP-LISTEN:5658.*SOFTHSM_PAYSIM_LABEL' "$SOFTHSM_DIR/serve.sh" || fail "paysim token not on port 5658"
  grep -q 'init-tokens.sh' "$SOFTHSM_DIR/serve.sh" || fail "serve.sh does not initialise the tokens first"
  # the healthcheck dials both ports through the same p11-kit client path the services use
  for m in mw-crypto:5657 paysim:5658; do
    grep -qx "remote: |socat - TCP:127.0.0.1:${m#*:}" "$SOFTHSM_DIR/pkcs11-modules/${m%%:*}.module" \
      || fail "healthcheck module ${m%%:*} does not dial port ${m#*:}"
  done
}

softhsm_service() {
  copy_softhsm_deploy
  env -u COMPOSE_PROJECT_NAME docker compose -f deploy/docker-compose.yml --env-file deploy/dev.env.defaults \
    --profile core config --format json 2>/dev/null | jq -c "$1"
}

copy_softhsm_deploy() {
  [ -d deploy ] && return 0
  cp -R "$REPO_DIR/deploy" deploy
  rm -f deploy/.env
}

test_compose_block_runs_softhsm_hardened_in_minimal_and_core() {
  local s
  s="$(softhsm_service '.services.softhsm')"
  [ -n "$s" ] && [ "$s" != null ] || fail "no softhsm service"
  printf '%s' "$s" | jq -e '(.profiles | sort) == ["core", "minimal"]' >/dev/null || fail "profiles: $(printf '%s' "$s" | jq -c .profiles)"
  printf '%s' "$s" | jq -e '.image == "mfs-mw/softhsm:local" and (.build.context | test("deploy/softhsm$"))' >/dev/null \
    || fail "not built from deploy/softhsm as mfs-mw/softhsm:local"
  printf '%s' "$s" | jq -e '.read_only == true and .cap_drop == ["ALL"] and (.security_opt | index("no-new-privileges:true"))
    and .user == "10001:10001" and .restart == "unless-stopped" and .init == true' >/dev/null \
    || fail "softhsm is not hardened: $(printf '%s' "$s" | jq -c '{read_only, cap_drop, security_opt, user, restart, init}')"
  printf '%s' "$s" | jq -e '(.networks | keys) == ["app"]' >/dev/null || fail "softhsm must join only app"
  printf '%s' "$s" | jq -e '(.deploy.resources.limits.memory | tonumber) == 50331648' >/dev/null || fail "default limit is not 48M"
  printf '%s' "$s" | jq -e '.healthcheck.test | join(" ") | test("healthcheck.sh")' >/dev/null || fail "healthcheck"
  printf '%s' "$s" | jq -e '.environment.SOFTHSM_MWCRYPTO_LABEL == "mw-crypto" and .environment.SOFTHSM_PAYSIM_LABEL == "paysim"
    and .environment.SOFTHSM_MWCRYPTO_USER_PIN != null and .environment.SOFTHSM_PAYSIM_SO_PIN != null' >/dev/null \
    || fail "labels and PINs are not passed in"
}

test_only_softhsm_mounts_the_token_volume() {
  local j
  j="$(softhsm_service '.')"
  printf '%s' "$j" | jq -e '.volumes | has("softhsm-tokens")' >/dev/null || fail "no softhsm-tokens volume"
  printf '%s' "$j" | jq -e '[.services.softhsm.volumes[] | select(.source == "softhsm-tokens" and .target == "/var/lib/softhsm/tokens")] | length == 1' >/dev/null \
    || fail "softhsm does not keep its tokens on softhsm-tokens"
  [ "$(printf '%s' "$j" | jq '[.services | to_entries[] | .value.volumes[]? | select(.source == "softhsm-tokens")] | length')" = 1 ] \
    || fail "the token volume is mounted by another service (keys must stay in softhsm)"
}

test_compose_requires_the_token_pins() {
  copy_softhsm_deploy
  local v
  for v in $SOFTHSM_PIN_VARS; do
    grep -qE "^$v=dev-[a-z0-9-]+$" deploy/dev.env.defaults || fail "$v is not an obviously fake dev- value in dev.env.defaults"
    grep -qE "\\\$\\{$v:\\?" deploy/docker-compose.yml || fail "compose does not require $v"
  done
  grep -qx 'SOFTHSM_MWCRYPTO_LABEL=mw-crypto' deploy/dev.env.defaults || fail "mw-crypto label not in dev.env.defaults"
  grep -qx 'SOFTHSM_PAYSIM_LABEL=paysim' deploy/dev.env.defaults || fail "paysim label not in dev.env.defaults"
}

test_provision_gets_the_mw_crypto_user_pin() {
  [ "$(softhsm_service '.services.provision.environment.SOFTHSM_MWCRYPTO_USER_PIN')" = '"dev-mwcrypto-user-pin"' ] \
    || fail "provision does not get SOFTHSM_MWCRYPTO_USER_PIN"
}

test_docs_record_the_access_shape_and_the_knobs() {
  local r="$SOFTHSM_DIR/README.md" v
  [ -f "$r" ] || fail "no deploy/softhsm/README.md"
  grep -qi 'option a' "$r" && grep -q 'p11-kit' "$r" || fail "README does not record the access choice"
  grep -qi 'testcontainers' "$r" || fail "README does not say how a Testcontainer uses it"
  for v in SOFTHSM_MWCRYPTO_LABEL SOFTHSM_PAYSIM_LABEL $SOFTHSM_PIN_VARS SOFTHSM_MEM MW_PORT_SOFTHSM_MWCRYPTO MW_PORT_SOFTHSM_PAYSIM; do
    grep -q "$v" "$REPO_DIR/README.md" || fail "README.md does not mention $v"
  done
  for v in mw-crypto paysim dev-mwcrypto-user-pin dev-paysim-user-pin; do
    grep -q "$v" "$REPO_DIR/docs/testing-guide.html" || fail "testing guide section 1 lacks $v"
  done
}

# ---- mw-crypto (ticket 62): its HSM ITs build the softhsm image from a copy in its test resources ----

test_mw_crypto_test_copy_matches_deploy_softhsm() {
  # module isolation forbids mw-crypto's tests a path outside the module, so they keep a copy
  # (services/mw-crypto/src/test/resources/softhsm); it must stay identical to deploy/softhsm
  local copy="$REPO_DIR/services/mw-crypto/src/test/resources/softhsm" d
  [ -d "$copy" ] || fail "no services/mw-crypto/src/test/resources/softhsm"
  d="$(diff -r "$SOFTHSM_DIR" "$copy" 2>&1)" \
    || fail "services/mw-crypto/src/test/resources/softhsm drifted from deploy/softhsm (copy the folder again): $d"
}

test_mw_crypto_image_reaches_its_token_through_the_p11_kit_proxy() {
  local f="$REPO_DIR/services/mw-crypto/Dockerfile" server client
  # the p11-kit RPC client must match the server's version (noble's 0.25 fails key generation)
  server="$(grep -oE 'p11-kit=[0-9][^ ]*' "$SOFTHSM_DIR/Dockerfile" | head -1)"
  client="$(grep -oE 'p11-kit=[0-9][^ ]*' "$f" | head -1)"
  [ -n "$server" ] && [ "$server" = "$client" ] || fail "mw-crypto p11-kit ($client) differs from softhsm's ($server)"
  grep -q 'socat=' "$f" || fail "mw-crypto image lacks socat"
  grep -q 'remote: |socat - TCP:softhsm:5657' "$f" || fail "mw-crypto module file does not dial softhsm:5657"
  grep -qF 'rm -f /usr/share/p11-kit/modules/*.module' "$f" \
    || fail "mw-crypto image keeps other p11-kit modules (slot 0 would be ambiguous)"
  [ "$(softhsm_service '.services["mw-crypto"].depends_on.softhsm.condition')" = '"service_healthy"' ] \
    || fail "mw-crypto does not wait for a healthy softhsm"
}
