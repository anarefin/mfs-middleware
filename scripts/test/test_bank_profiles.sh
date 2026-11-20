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
