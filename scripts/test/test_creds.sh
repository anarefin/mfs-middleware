# scripts/creds.sh / scripts/totp.sh: the tester's credential helpers (docs/testing-guide.html section 1).
# docker is replaced by a stub on PATH, so the tests never need a running stack.

dev_env() {
  mkdir -p deploy
  cat >deploy/.env <<'EOF'
APISIX_ADMIN_KEY=fake-apisix-admin
KC_BOOTSTRAP_ADMIN_PASSWORD=fake-kc-admin
KC_OPS_TEST_PASSWORD=fake-ops-pw
KC_PORTALS_TEST_PASSWORD=fake-portals-pw
KC_OPS_TEST_TOTP_SECRET=12345678901234567890123456789012
KC_PORTALS_TEST_TOTP_SECRET=portals-fake-totp-secret-0000001
MW_E2E_API_KEY=fake-e2e-key
MW_SEED_APP_PIN=97531
CHECKOUT_DEMO_MERCHANT_SECRET=fake-checkout
CHECKOUT_PHARMACY_MERCHANT_SECRET=fake-pharmacy
CHECKOUT_FASHION_MERCHANT_SECRET=fake-fashion
BILLAGG_HMAC_SECRET=fake-billagg
BILLAGG_WS_PASSWORD=fake-billagg-ws
C2WBANK_HMAC_SECRET=fake-c2wbank
USSD_GP_GATEWAY_SECRET=fake-gp
USSD_ROBI_GATEWAY_SECRET=fake-robi
USSD_BL_GATEWAY_SECRET=fake-bl
USSD_TELETALK_GATEWAY_SECRET=fake-teletalk
POSTGRES_PASSWORD=fake-pg
SOFTHSM_MWCRYPTO_LABEL=fake-mwcrypto-label
SOFTHSM_MWCRYPTO_SO_PIN=fake-mwcrypto-so
SOFTHSM_MWCRYPTO_USER_PIN=fake-mwcrypto-user
SOFTHSM_PAYSIM_LABEL=fake-paysim-label
SOFTHSM_PAYSIM_SO_PIN=fake-paysim-so
SOFTHSM_PAYSIM_USER_PIN=fake-paysim-user
EOF
}

# docker_stub up|old|down: a docker on PATH whose compose exec answers like a running OpenBao with the
# fixed tester token (up), a running OpenBao on a volume from before it (old: only dev-init.json), or a
# stopped one (down).
docker_stub() {
  mkdir -p .bin deploy
  touch deploy/docker-compose.yml
  case "$1" in
    up|old)
      cat >.bin/docker <<STUB
#!/bin/sh
echo "docker \$*" >> "$PWD/.tool-calls"
case "\$*" in
  *dev-token*) [ "$1" = up ] && { echo dev-root-token; exit 0; }; exit 1 ;;
  *dev-init.json*) echo '{"root_token":"s.fake-root","unseal_key":"fake-unseal"}' ;;
esac
STUB
      ;;
    *) printf '#!/bin/sh\necho "docker $*" >> "%s/.tool-calls"\nexit 1\n' "$PWD" >.bin/docker ;;
  esac
  chmod +x .bin/docker
  PATH="$PWD/.bin:$PATH"
}

test_totp_matches_the_rfc6238_sha256_vector() {
  # RFC 6238 appendix B: secret "1234...9012" (32 ASCII bytes, used as is), T=59 s -> 46119246 (8 digits)
  dev_env
  MW_TOTP_TIME=59 assert_ok mw_run "$PWD" totp.sh seed-ops-maker
  assert_out_contains "119246"
  assert_out_contains "seed-ops-maker"
}

# Ticket 73: the portals realm demands TOTP; its test users share the pre-set KC_PORTALS_TEST_TOTP_SECRET.
test_totp_prints_portal_codes_from_the_portals_secret() {
  dev_env
  MW_TOTP_TIME=59 assert_ok mw_run "$PWD" totp.sh seed-ops-secadmin2
  assert_out_contains "seed-ops-secadmin2"
  MW_TOTP_TIME=59 assert_ok mw_run "$PWD" totp.sh seed-org-maker
  local portal="$OUT"
  assert_out_contains "seed-org-maker"
  MW_TOTP_TIME=59 assert_ok mw_run "$PWD" totp.sh org-checker01
  MW_TOTP_TIME=59 assert_ok mw_run "$PWD" totp.sh seed-ops-maker
  [ "${portal#*: }" != "${OUT#*: }" ] || fail "portal code made from the ops secret"
  # the code of the portals secret at T=59 (HmacSHA256 keyed with its UTF-8 bytes, 6 digits)
  local want
  want="$(node -e 'const c=require("node:crypto");const b=Buffer.alloc(8);b.writeBigUInt64BE(1n);const h=c.createHmac("sha256",Buffer.from("portals-fake-totp-secret-0000001","utf8")).update(b).digest();const o=h[h.length-1]&15;console.log(String((((h[o]&127)<<24)|(h[o+1]<<16)|(h[o+2]<<8)|h[o+3])%1e6).padStart(6,"0"))')"
  case "$portal" in *"seed-org-maker: $want "*) ;; *) fail "want $want: $portal" ;; esac
}

test_totp_changes_every_30_seconds() {
  dev_env
  MW_TOTP_TIME=59 mw_run "$PWD" totp.sh; a="$OUT"
  MW_TOTP_TIME=61 mw_run "$PWD" totp.sh; b="$OUT"
  [ "$a" != "$b" ] || fail "same code across a 30 s step"
}

# Every ops user has the pre-set secret (no authenticator app): ops-*01 get the seed personas' code.
test_totp_prints_the_ops_01_users_code_from_the_ops_secret() {
  dev_env
  for user in ops-viewer01 ops-maker01 ops-checker01 ops-admin01 ops-compliance01 ops-auditor01; do
    MW_TOTP_TIME=59 assert_ok mw_run "$PWD" totp.sh "$user"
    assert_out_contains "$user: "
    assert_out_contains "119246"
  done
}

test_totp_refuses_an_unknown_persona() {
  dev_env
  assert_fails mw_run "$PWD" totp.sh nobody01
  assert_out_contains "no pre-set secret"
}

test_totp_without_dev_env_says_how_to_create_it() {
  assert_fails mw_run "$PWD" totp.sh
  assert_out_contains "cp dev.env.defaults .env"
}

test_creds_without_dev_env_says_how_to_create_it() {
  docker_stub down
  assert_fails mw_run "$PWD" creds.sh
  assert_out_contains "cp dev.env.defaults .env"
}

test_creds_prints_every_login_and_key() {
  dev_env; docker_stub up
  assert_ok mw_run "$PWD" creds.sh
  for v in fake-apisix-admin fake-kc-admin fake-ops-pw fake-portals-pw fake-e2e-key "scripts/totp.sh seed-org-maker" \
           fake-checkout fake-pharmacy fake-fashion fake-billagg fake-billagg-ws fake-c2wbank fake-gp fake-robi fake-bl fake-teletalk dev-root-token \
           https://localhost/tester https://localhost/console seed-ops-maker customer01 org-maker01 kc-admin \
           "scripts/totp.sh seed-ops-maker" "scripts/totp.sh ops-maker01" fake-mwcrypto-label fake-mwcrypto-so fake-mwcrypto-user \
           fake-paysim-label fake-paysim-so fake-paysim-user "app PIN (mw-auth login): 97531" "scripts/lib/mw-auth-token.mjs"; do
    assert_out_contains "$v"
  done
  # the test console's own credential pages come first; the transaction PIN is the app PIN (ticket 72)
  assert_out_contains "https://localhost/tester/credentials"
  assert_out_contains "https://localhost/tester/seed-data"
  assert_out_lacks "Enrol an authenticator"
  assert_out_contains "transaction PIN (every channel, checked by mw-auth since ticket 72): the app PIN 97531"
  assert_out_lacks "1234"
  # ticket 73: no customers realm any more
  assert_out_lacks "realm customers"
  assert_out_lacks "KC_CUSTOMERS"
}

test_creds_works_when_the_stack_is_down() {
  dev_env; docker_stub down
  assert_ok mw_run "$PWD" creds.sh
  assert_out_contains "fake-ops-pw"
  assert_out_contains "start the stack"
  assert_out_lacks "s.fake-root"
}

test_creds_falls_back_to_the_init_root_token_on_an_old_openbao_volume() {
  dev_env; docker_stub old
  assert_ok mw_run "$PWD" creds.sh
  assert_out_contains "s.fake-root"
  assert_out_lacks "dev-root-token"
}
