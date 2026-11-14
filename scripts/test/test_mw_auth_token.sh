# scripts/lib/mw-auth-token.mjs (ticket 69): the mw-auth login helper of scripts/edge-test.sh signs
# requests and encrypts the PIN exactly as the apps do (spec 9.1, 18.7). Offline: no stack.

test_the_login_helper_signs_and_encrypts_the_pin_as_an_app() {
  command -v node >/dev/null || fail "node is required"
  OUT="$(node "$REPO_DIR/scripts/lib/mw-auth-token.mjs" --self-test 2>&1)" || fail "self-test failed: $OUT"
  assert_out_contains "mw-auth-token self-test: ok"
}

test_the_login_helper_refuses_a_wrong_argument() {
  if OUT="$(node "$REPO_DIR/scripts/lib/mw-auth-token.mjs" not-an-msisdn 2>&1)"; then fail "accepted a wrong argument"; fi
  assert_out_contains "usage:"
}

test_edge_test_gets_its_app_tokens_from_mw_auth() {
  grep -q 'scripts/lib/mw-auth-token.mjs' "$REPO_DIR/scripts/edge-test.sh" || fail "edge-test.sh does not use the mw-auth login helper"
  # ticket 73: the Keycloak customers realm is gone, so nothing may log in there any more
  if grep -qE 'realms/customers|kc_customer_token|KC_CUSTOMERS' "$REPO_DIR/scripts/edge-test.sh"; then
    fail "edge-test.sh still uses the Keycloak customers realm"
  fi
  return 0
}
