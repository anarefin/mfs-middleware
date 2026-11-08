# make creds / make totp: the tester's credential helpers (docs/testing-guide.html section 1).
# docker is replaced by a stub on PATH, so the tests never need a running stack.

dev_env() {
  mkdir -p deploy
  cat >deploy/.env <<'EOF'
APISIX_ADMIN_KEY=fake-apisix-admin
KC_BOOTSTRAP_ADMIN_PASSWORD=fake-kc-admin
KC_OPS_TEST_PASSWORD=fake-ops-pw
KC_CUSTOMERS_TEST_PASSWORD=fake-customers-pw
KC_PORTALS_TEST_PASSWORD=fake-portals-pw
KC_OPS_TEST_TOTP_SECRET=12345678901234567890123456789012
MW_E2E_API_KEY=fake-e2e-key
CHECKOUT_DEMO_MERCHANT_SECRET=fake-checkout
BILLAGG_HMAC_SECRET=fake-billagg
C2WBANK_HMAC_SECRET=fake-c2wbank
USSD_GP_GATEWAY_SECRET=fake-gp
USSD_ROBI_GATEWAY_SECRET=fake-robi
USSD_BL_GATEWAY_SECRET=fake-bl
USSD_TELETALK_GATEWAY_SECRET=fake-teletalk
POSTGRES_PASSWORD=fake-pg
EOF
}

# docker_stub up|down: a docker on PATH whose compose exec answers like a running (up) or stopped
# (down) OpenBao.
docker_stub() {
  mkdir -p .bin deploy
  touch deploy/docker-compose.yml
  if [ "$1" = up ]; then
    printf '#!/bin/sh\necho "docker $*" >> "%s/.tool-calls"\necho %s\n' "$PWD" \
      "'{\"root_token\":\"s.fake-root\",\"unseal_key\":\"fake-unseal\"}'" >.bin/docker
  else
    printf '#!/bin/sh\necho "docker $*" >> "%s/.tool-calls"\nexit 1\n' "$PWD" >.bin/docker
  fi
  chmod +x .bin/docker
  PATH="$PWD/.bin:$PATH"
}

test_totp_matches_the_rfc6238_sha256_vector() {
  # RFC 6238 appendix B: secret "1234...9012" (32 ASCII bytes, used as is), T=59 s -> 46119246 (8 digits)
  dev_env
  MW_TOTP_TIME=59 assert_ok mw_make "$PWD" totp PERSONA=seed-ops-maker
  assert_out_contains "119246"
  assert_out_contains "seed-ops-maker"
}

test_totp_changes_every_30_seconds() {
  dev_env
  MW_TOTP_TIME=59 mw_make "$PWD" totp; a="$OUT"
  MW_TOTP_TIME=61 mw_make "$PWD" totp; b="$OUT"
  [ "$a" != "$b" ] || fail "same code across a 30 s step"
}

test_totp_refuses_a_persona_without_a_preset_secret() {
  dev_env
  assert_fails mw_make "$PWD" totp PERSONA=ops-maker01
  assert_out_contains "authenticator app"
}

test_totp_without_dev_env_says_how_to_create_it() {
  assert_fails mw_make "$PWD" totp
  assert_out_contains "make up"
}

test_creds_without_dev_env_says_how_to_create_it() {
  docker_stub down
  assert_fails mw_make "$PWD" creds
  assert_out_contains "make up"
}

test_creds_prints_every_login_and_key() {
  dev_env; docker_stub up
  assert_ok mw_make "$PWD" creds
  for v in fake-apisix-admin fake-kc-admin fake-ops-pw fake-customers-pw fake-portals-pw fake-e2e-key \
           fake-checkout fake-billagg fake-c2wbank fake-gp fake-robi fake-bl fake-teletalk s.fake-root \
           https://localhost/tester https://localhost/console seed-ops-maker customer01 org-maker01 kc-admin \
           "make totp"; do
    assert_out_contains "$v"
  done
}

test_creds_works_when_the_stack_is_down() {
  dev_env; docker_stub down
  assert_ok mw_make "$PWD" creds
  assert_out_contains "fake-ops-pw"
  assert_out_contains "start the stack"
  assert_out_lacks "s.fake-root"
}
