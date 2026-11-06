# scripts/check-api-traceability.sh (ticket 58): the Newman collection's requests against
# tests/api/TRACEABILITY.md, the rule tests/e2e's TraceabilityTest enforced for the Cucumber suite.

CHECK="$REPO_DIR/scripts/check-api-traceability.sh"

# plant_suite: a collection that claims every required coverage item once, and its table.
# One request per item, tagged as the item demands; plus one supporting request (criteria -).
plant_suite() {
  python3 - "$PWD/collection.json" "$PWD/TRACEABILITY.md" <<'EOF'
import json, sys
flows = {"P2P app": "p2p", "CASH_IN app": "cash", "CASH_OUT app": "cash", "TOPUP app": "topup",
         "BILL_PAY app": "billpay", "ADD_MONEY_BANK app": "addmoney", "MERCHANT_PAY app": "merchant",
         "MERCHANT_PAY checkout": "checkout"}
rows, items = [], []
def add(tags, text, criteria, covers):
    name = "[" + " ".join(tags) + "] " + text
    items.append({"name": name, "request": {"method": "GET", "url": "{{baseUrl}}/healthz"}})
    rows.append("| 01 | %s | %s | %s |" % (name, criteria, "; ".join(covers)))
for flow, tag in flows.items():
    for outcome in ["success", "failure", "tsq-commit", "tsq-release", "idempotent"]:
        tags = [tag] + (["tsq"] if outcome.startswith("tsq") else [])
        add(tags, "%s %s" % (flow, outcome), "2" if outcome == "idempotent" else "1", ["%s %s" % (flow, outcome)])
for f in ["P2P", "CASH_OUT", "TOPUP", "BILL_PAY"]:
    add(["ussd"], "%s over ussd" % f, "1", ["%s ussd success" % f])
for i in ["search-txnId", "search-msisdn", "timeline", "audit", "reversal-approved"]:
    add(["admin", "operator"], "ops " + i, "4", ["ops " + i])
add(["ussd"], "ussd over http", "5", ["ussd http"])
add(["ussd", "smpp"], "ussd over smpp", "5", ["ussd smpp"])
add(["bulk"], "a supporting scenario", "-", [])
health = {"name": "00 Health", "item": [{"name": "[health] edge answers", "request": {"method": "GET", "url": "x"}}]}
rows.append("| 00 | [health] edge answers | - | |")
json.dump({"info": {"name": "t"}, "item": [health, {"name": "01 Flows", "item": items}]}, open(sys.argv[1], "w"))
open(sys.argv[2], "w").write("# t\n\n| Folder | Request | Criteria | Covers |\n| --- | --- | --- | --- |\n" + "\n".join(rows) + "\n")
EOF
}

run_check() { OUT="$("$CHECK" "$PWD/collection.json" "$PWD/TRACEABILITY.md" 2>&1)"; }

test_a_complete_suite_passes() {
  plant_suite
  assert_ok run_check
  assert_out_contains "api traceability: ok"
}

test_removing_a_tag_from_a_request_name_fails() {
  plant_suite
  sed -i.bak 's/\[p2p tsq\] P2P app tsq-commit/[p2p] P2P app tsq-commit/' collection.json
  assert_fails run_check
  assert_out_contains "not mapped in TRACEABILITY.md: 01 [p2p] P2P app tsq-commit"
  assert_out_contains "row names no request: 01 [p2p tsq] P2P app tsq-commit"
}

test_a_covers_item_needs_the_requirement_tag_of_its_flow_and_outcome() {
  plant_suite
  sed -i.bak 's/\[p2p tsq\] P2P app tsq-commit/[p2p] P2P app tsq-commit/' collection.json TRACEABILITY.md
  assert_fails run_check
  assert_out_contains "claims 'P2P app tsq-commit' without the tag tsq"
}

test_an_unclaimed_required_item_fails() {
  plant_suite
  sed -i.bak 's/| 4 | ops audit |/| 4 | |/' TRACEABILITY.md
  assert_fails run_check
  assert_out_contains "claimed by no request: ops audit"
}

test_a_covers_item_outside_its_criterion_fails() {
  plant_suite
  sed -i.bak 's/| 1 | P2P app success |/| 2 | P2P app success |/' TRACEABILITY.md
  assert_fails run_check
  assert_out_contains "claims 'P2P app success' (criterion 1)"
}

test_a_request_without_requirement_tags_fails() {
  plant_suite
  sed -i.bak 's/\[bulk\] a supporting scenario/a supporting scenario/' collection.json TRACEABILITY.md
  assert_fails run_check
  assert_out_contains "has no [tags] prefix: 01 a supporting scenario"
}

test_an_unknown_tag_fails() {
  plant_suite
  sed -i.bak 's/\[bulk\] a supporting scenario/[bulky] a supporting scenario/' collection.json TRACEABILITY.md
  assert_fails run_check
  assert_out_contains "unknown tag 'bulky'"
}

test_a_request_mapped_twice_fails() {
  plant_suite
  printf '| 01 | [bulk] a supporting scenario | - | |\n' >>TRACEABILITY.md
  assert_fails run_check
  assert_out_contains "mapped twice: 01 [bulk] a supporting scenario"
}
