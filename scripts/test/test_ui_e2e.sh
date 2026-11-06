# make ui-e2e (ticket 59): the admin UI Playwright suite against the running, seeded stack.
# pnpm is a stub on PATH that records its arguments and the E2E_* / credential env it was given.

ui_fixture() {
  mkdir -p ui/mw-admin-ui deploy .bin
  printf '{"name":"mw-admin-ui"}\n' >ui/mw-admin-ui/package.json
  printf 'lockfileVersion: 9\n' >ui/mw-admin-ui/pnpm-lock.yaml
  cat >deploy/.env <<'EOF'
KC_OPS_TEST_PASSWORD=fake-ops-pw
KC_OPS_TEST_TOTP_SECRET=fake-totp
USSD_ROBI_GATEWAY_SECRET=fake-ussd
KC_DB_PASSWORD=fake-db-pw
EOF
  cat >.bin/pnpm <<EOF
#!/bin/sh
echo "pnpm \$* | E2E_BASE_URL=\$E2E_BASE_URL E2E_SEED_MANIFEST=\$E2E_SEED_MANIFEST E2E_COMPOSE_PROJECT=\$E2E_COMPOSE_PROJECT KC_OPS_TEST_PASSWORD=\$KC_OPS_TEST_PASSWORD KC_OPS_TEST_TOTP_SECRET=\$KC_OPS_TEST_TOTP_SECRET USSD_ROBI_GATEWAY_SECRET=\$USSD_ROBI_GATEWAY_SECRET KC_DB_PASSWORD=\${KC_DB_PASSWORD:-unset} | cwd=\$(pwd)" >> "$PWD/.tool-calls"
[ "\$1" = install ] && mkdir -p node_modules && touch node_modules/.modules.yaml
[ -f "$PWD/.pnpm-fail" ] && [ "\$1" = test:e2e ] && exit 1
exit 0
EOF
  chmod +x .bin/pnpm
  PATH="$PWD/.bin:$PATH"
}

test_ui_e2e_installs_once_then_runs_the_suite_against_the_stack() {
  ui_fixture
  assert_ok mw_make "$PWD" ui-e2e
  grep -q "^pnpm install --frozen-lockfile .*cwd=$PWD/ui/mw-admin-ui$" .tool-calls || fail "no frozen install: $(cat .tool-calls)"
  grep -q "^pnpm exec playwright install chromium " .tool-calls || fail "browser not installed: $(cat .tool-calls)"
  grep -q "^pnpm test:e2e |" .tool-calls || fail "suite not run: $(cat .tool-calls)"
  # the order: install, browser, suite
  [ "$(cut -d' ' -f2 .tool-calls | tr '\n' ' ')" = "install exec test:e2e " ] || fail "order: $(cat .tool-calls)"
  rm .tool-calls
  # node_modules newer than the lockfile: no second install
  assert_ok mw_make "$PWD" ui-e2e
  grep -q "^pnpm install" .tool-calls && fail "installed again: $(cat .tool-calls)"
  return 0
}

test_ui_e2e_reinstalls_when_the_lockfile_changed() {
  ui_fixture
  assert_ok mw_make "$PWD" ui-e2e
  rm .tool-calls
  sleep 1; touch ui/mw-admin-ui/pnpm-lock.yaml
  assert_ok mw_make "$PWD" ui-e2e
  grep -q "^pnpm install --frozen-lockfile" .tool-calls || fail "lockfile change not installed: $(cat .tool-calls)"
}

test_ui_e2e_passes_the_seed_manifest_and_only_the_credentials_it_needs() {
  ui_fixture
  assert_ok mw_make "$PWD" ui-e2e
  line="$(grep '^pnpm test:e2e' .tool-calls)"
  case "$line" in *"E2E_BASE_URL=https://localhost "*) ;; *) fail "base URL: $line";; esac
  case "$line" in *"E2E_SEED_MANIFEST=$PWD/build/seed/manifest.json "*) ;; *) fail "manifest: $line";; esac
  case "$line" in *"E2E_COMPOSE_PROJECT=mfs-mw "*) ;; *) fail "project: $line";; esac
  case "$line" in *"KC_OPS_TEST_PASSWORD=fake-ops-pw KC_OPS_TEST_TOTP_SECRET=fake-totp USSD_ROBI_GATEWAY_SECRET=fake-ussd "*) ;; *) fail "credentials: $line";; esac
  case "$line" in *"KC_DB_PASSWORD=unset "*) ;; *) fail "exported a credential it does not need: $line";; esac
}

test_ui_e2e_follows_MW_HTTPS_PORT_and_GREP() {
  ui_fixture
  assert_ok mw_make "$PWD" ui-e2e GREP=@approvals MW_HTTPS_PORT=18443
  grep -q "^pnpm test:e2e --grep @approvals | E2E_BASE_URL=https://localhost:18443 " .tool-calls || fail "GREP / port: $(cat .tool-calls)"
}

test_ui_e2e_without_deploy_env_names_the_fix() {
  ui_fixture
  rm deploy/.env
  assert_fails mw_make "$PWD" ui-e2e
  assert_out_contains "make up && make seed"
  grep -q "test:e2e" .tool-calls 2>/dev/null && fail "ran the suite without credentials"
  return 0
}

test_ui_e2e_fails_when_the_suite_fails() {
  ui_fixture
  touch .pnpm-fail
  assert_fails mw_make "$PWD" ui-e2e
}

test_ui_e2e_without_pnpm_says_what_to_install() {
  ui_fixture
  rm .bin/pnpm
  PATH="$PWD/.bin:/usr/bin:/bin"
  assert_fails mw_make "$PWD" ui-e2e
  assert_out_contains "pnpm"
}
