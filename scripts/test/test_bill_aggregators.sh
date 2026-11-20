# Bill aggregators (ticket 114): mw-adapter-billpay's aggregators-dev.yml and mw-admin-api's
# partner-seeds/dev/<aggregator>.json (and its seed list) are what
# deploy/partner-routes/aggregators/generate.mjs makes; mw-simulators seeds every aggregator's bills.

test_bill_aggregators_are_generated() {
  command -v node >/dev/null 2>&1 || { echo "skip: node not installed"; return 0; }
  OUT="$(node "$REPO_DIR/deploy/partner-routes/aggregators/generate.mjs" --check 2>&1)" \
    || fail "bill aggregators are stale: run node deploy/partner-routes/aggregators/generate.mjs"
}

test_every_aggregator_has_seeded_bills_and_a_callback_route() {
  local a
  for a in sslcommerz ekpay agg3 agg4; do
    [ -f "$REPO_DIR/services/mw-simulators/src/main/resources/seed/v1/bills/$a.csv" ] || fail "no seeded bills for $a"
    [ -f "$REPO_DIR/deploy/apisix/routes/partner-$a-callback.json" ] || fail "no APISIX callback route for $a"
    grep -q "partner-seeds/dev/$a.json" "$REPO_DIR/services/mw-admin-api/src/main/resources/application-dev.yml" \
      || fail "mw-admin-api does not seed $a"
  done
}
