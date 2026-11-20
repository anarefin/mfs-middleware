# Bank gateway profiles (ticket 117): mw-adapter-bank's bank-profiles-dev.yml and mw-admin-api's
# partner-seeds/dev/bank-<code>.json (and its seed list) are what deploy/partner-routes/bank/generate.mjs
# makes of bank-profiles.csv.

test_bank_profiles_are_generated_from_the_csv() {
  command -v node >/dev/null 2>&1 || { echo "skip: node not installed"; return 0; }
  OUT="$(node "$REPO_DIR/deploy/partner-routes/bank/generate.mjs" --check 2>&1)" \
    || fail "bank profiles are stale: run node deploy/partner-routes/bank/generate.mjs"
}

test_bank_profiles_csv_has_about_twenty_banks_with_rest_and_soap() {
  local csv="$REPO_DIR/deploy/partner-routes/bank/bank-profiles.csv" n
  n="$(tail -n +2 "$csv" | grep -c .)"
  [ "$n" -ge 18 ] || fail "only $n bank profiles"
  grep -q ',REST,' "$csv" || fail "no REST bank profile"
  grep -q ',SOAP,' "$csv" || fail "no SOAP bank profile"
}

# Ticket 117e: every bank of the CSV sends its callbacks through APISIX to mw-adapter-bank (one route,
# one uri per bank, generated) signed with the dev secret both sides share (BANK_GATEWAY_HMAC_SECRET).
test_bank_callback_route_covers_every_bank() {
  local csv="$REPO_DIR/deploy/partner-routes/bank/bank-profiles.csv" route="$REPO_DIR/deploy/apisix/routes/partner-bank-callback.json" code
  [ -f "$route" ] || fail "no deploy/apisix/routes/partner-bank-callback.json"
  jq -e '.methods == ["POST"] and .upstream.nodes == {"mw-adapter-bank:8080": 1}' "$route" >/dev/null || fail "bank callback route upstream"
  for code in $(tail -n +2 "$csv" | cut -d, -f1); do
    jq -e --arg u "/partner/bank-$code/callback/*" '.uris | index($u) != null' "$route" >/dev/null || fail "no callback uri for bank-$code"
  done
}

test_bank_callback_secret_is_shared_with_the_simulator() {
  local p="$REPO_DIR/deploy/provision/services.sh"
  grep -q 'sync_env hmac-secret BANK_GATEWAY_HMAC_SECRET' "$p" || fail "adapter bank secrets not synced"
  grep -q 'sync_env "sim.callback-secrets.bank-$code" BANK_GATEWAY_HMAC_SECRET' "$p" || fail "simulator bank secrets not synced"
  grep -q '^BANK_GATEWAY_HMAC_SECRET=' "$REPO_DIR/deploy/dev.env.defaults" || fail "no BANK_GATEWAY_HMAC_SECRET in dev.env.defaults"
  local want got
  want="$(tail -n +2 "$REPO_DIR/deploy/partner-routes/bank/bank-profiles.csv" | cut -d, -f1 | sort | tr '\n' ' ')"
  got="$(sed -n 's/^BANK_CODES="\(.*\)"$/\1/p' "$p" | tr ' ' '\n' | sort | tr '\n' ' ')"
  [ "$want" = "$got" ] || fail "BANK_CODES of services.sh differ from bank-profiles.csv: $got"
}
