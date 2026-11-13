# tests/api/run_customer.mjs: each scripts/api-test.sh run pays from a seeded run customer (mw-seeder pool) that
# has been used fewer than RUNS_PER_DAY times this UTC day, so repeated runs never reach core-mock's
# daily debit limit of one wallet (15,000 BDT; one run spends about 4,000 BDT of it).

PICK="$REPO_DIR/tests/api/run_customer.mjs"

rc_manifest() {
  local i sep="" personas=""
  for i in 01 02 03; do
    personas="$personas$sep{\"persona\":\"run-customer\",\"realm\":\"customers\",\"username\":\"seed-run-customer$i\",\"roles\":[\"customer\"],\"attributes\":{\"msisdn\":\"01714${i}0001\"},\"loginClient\":\"mw-app\",\"passwordEnv\":\"KC_CUSTOMERS_TEST_PASSWORD\"}"
    sep=","
  done
  printf '{"schemaVersion":1,"personas":[{"persona":"customer","realm":"customers","username":"seed-customer01","roles":["customer"],"attributes":{"msisdn":"01712000001"}},%s]}\n' "$personas" >manifest.json
}

test_the_first_run_of_a_day_takes_the_first_run_customer_and_records_it() {
  rc_manifest
  OUT="$(node "$PICK" manifest.json ledger.tsv 2026-11-06)" || fail "picker failed"
  [ "$OUT" = "seed-run-customer01 01714010001" ] || fail "picked '$OUT'"
  [ "$(cat ledger.tsv)" = "$(printf '2026-11-06\tseed-run-customer01')" ] || fail "ledger: $(cat ledger.tsv)"
}

test_repeated_runs_on_one_day_spread_over_the_pool_and_never_exceed_three_per_customer() {
  rc_manifest
  local i picks=""
  for i in 1 2 3 4 5 6 7 8 9; do
    picks="$picks $(node "$PICK" manifest.json ledger.tsv 2026-11-06 | cut -d' ' -f1)"
  done
  for i in 01 02 03; do
    [ "$(printf '%s\n' $picks | grep -c "seed-run-customer$i")" = 3 ] || fail "uneven picks:$picks"
  done
  # a 4th run per customer would pass 12,000 BDT: refused, with the fix
  if OUT="$(node "$PICK" manifest.json ledger.tsv 2026-11-06 2>&1)"; then fail "a 10th run on 3 customers was allowed"; fi
  assert_out_contains "--profile seed"
}

test_a_new_utc_day_starts_the_count_again() {
  rc_manifest
  local i
  for i in 1 2 3 4 5 6 7 8 9; do node "$PICK" manifest.json ledger.tsv 2026-11-06 >/dev/null; done
  OUT="$(node "$PICK" manifest.json ledger.tsv 2026-11-07)" || fail "the next day was refused"
  [ "$OUT" = "seed-run-customer01 01714010001" ] || fail "picked '$OUT'"
}

# lib_check USER MSISDN JS: runs JS (assertions with `ok(cond, what)`) with MW = lib.js loaded under a
# stub Postman sandbox whose run customer is USER / MSISDN.
lib_check() {
  node -e '
    const fs = require("fs");
    const vars = { runCustomerUser: process.argv[2], runCustomerMsisdn: process.argv[3] };
    const pm = { variables: { get: k => vars[k] }, collectionVariables: { get: () => null, set() {} } };
    const MW = new Function("pm", "require", "lib", "return eval(lib)")(pm, () => ({}), fs.readFileSync(process.argv[1], "utf8"));
    const ok = (c, what) => { if (!c) { console.error("not " + what); process.exit(1); } };
    eval(process.argv[4]);' "$REPO_DIR/tests/api/lib.js" "$@"
}

test_lib_plays_customer01_as_the_run_customer_in_every_number_form_and_keeps_the_mask() {
  lib_check seed-run-customer05 01714050001 '
    const src = "S.p2p(\"customer01\", \"01711000002\"); [\"01711000001\", \"+8801711000001\", `to 01711000001?`, \"0171****001\"]";
    const out = MW.forRun(src);
    ok(out === "S.p2p(\"customer01\", \"01711000002\"); [\"01714050001\", \"+8801714050001\", `to 01714050001?`, \"0171****001\"]", "substituted: " + out);
    ok(MW.loginUser("customers", "customer01") === "seed-run-customer05", "customer01 logs in as the run customer");
    ok(MW.loginUser("customers", "customer02") === "customer02", "customer02 stays");
    ok(MW.loginUser("ops", "customer01") === "customer01", "other realms stay");
  ' || fail "lib.js does not play customer01 as the run customer"
}

test_lib_without_a_run_customer_changes_nothing() {
  lib_check '' '' '
    ok(MW.forRun("x(\"01711000001\")") === "x(\"01711000001\")", "unchanged source");
    ok(MW.loginUser("customers", "customer01") === "customer01", "unchanged user");
  ' || fail "lib.js changed a run without a run customer"
}

test_every_scenario_script_of_the_collection_runs_through_forRun() {
  node -e '
    const c = require(process.argv[1]);
    const bad = c.item.flatMap(f => f.item).filter(i => !i.event[0].script.exec.join("\n").includes("eval(MW.forRun("));
    if (bad.length) { console.error(bad.map(i => i.name).join("\n")); process.exit(1); }' \
    "$REPO_DIR/tests/api/mfs-mw.postman_collection.json" || fail "scripts outside MW.forRun (node tests/api/build_collection.mjs)"
}

test_run_sh_picks_a_run_customer_and_passes_it_to_newman() {
  grep -q 'run_customer.mjs' "$REPO_DIR/tests/api/run.sh" || fail "run.sh does not pick a run customer"
  grep -q 'runCustomerUser=' "$REPO_DIR/tests/api/run.sh" || fail "run.sh does not pass runCustomerUser"
  grep -q 'runCustomerMsisdn=' "$REPO_DIR/tests/api/run.sh" || fail "run.sh does not pass runCustomerMsisdn"
}

test_a_manifest_without_run_customers_is_refused_with_the_fix() {
  printf '{"schemaVersion":1,"personas":[]}\n' >manifest.json
  if OUT="$(node "$PICK" manifest.json ledger.tsv 2026-11-06 2>&1)"; then fail "picked from an empty pool"; fi
  assert_out_contains "--profile seed"
}

test_every_run_customer_masks_like_customer01_and_lies_outside_the_core_mock_seed() {
  # the scenarios expect 0171****001 (customer01 masked); mw-seeder's pool must keep that shape
  local yml="$REPO_DIR/services/mw-seeder/src/main/resources/application.yml" ids
  ids="$(grep -oE 'seed-run-customer[0-9]+, roles: \[customer\], attributes: \{ msisdn: "[0-9]+"' "$yml" | grep -oE '"[0-9]+"' | tr -d '"')"
  [ "$(printf '%s\n' $ids | wc -l | tr -d ' ')" -ge 24 ] || fail "fewer than 24 run customers seeded: $ids"
  for i in $ids; do
    case "$i" in 0171????001) ;; *) fail "$i does not mask as 0171****001" ;; esac
    case "$i" in 0171100????|01712000*) fail "$i collides with a seeded range" ;; esac
    grep -q "wallet-id: \"$i\", type: CUSTOMER" "$yml" || fail "no CUSTOMER wallet seeded for $i"
    grep -q "('$i', 'customer01@example.test')" "$REPO_DIR/services/mw-notify/src/main/resources/db/dev/R__dev_contacts.sql" \
      || fail "no dev email contact for $i (the email receipt scenarios)"
  done
}
