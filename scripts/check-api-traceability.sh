#!/usr/bin/env bash
# MVP acceptance traceability of the Newman API suite (ticket 58; it replaces tests/e2e's
# TraceabilityTest, same rule, same data source: the scenarios and tests/api/TRACEABILITY.md).
#
#   scripts/check-api-traceability.sh [COLLECTION TABLE]
#
# Defaults: tests/api/mfs-mw.postman_collection.json and tests/api/TRACEABILITY.md; with the
# defaults it also fails when the committed collection is stale against tests/api/src and lib.js
# (node tests/api/build_collection.mjs --check). Offline: no stack needed. Fails when
#   - a request name does not start with its requirement tags ("[p2p tsq] ..."), or uses an
#     unknown tag;
#   - a request is missing from the table, mapped twice, or a row names no request;
#   - a criterion is not 1-5 (or "-"), or a Covers item is unknown or claimed outside its criterion;
#   - a Covers item is claimed by a request without the tag its flow / outcome demands (so a tag
#     removed from a request name fails here);
#   - a coverage item the MVP acceptance criteria demand is claimed by no request.
# Run by make contracts-lint; self-test scripts/test/test_api_traceability.sh.
set -eu
. "$(dirname "$0")/lib/modules.sh"

collection="${1:-$MW_ROOT/tests/api/mfs-mw.postman_collection.json}"
table="${2:-$MW_ROOT/tests/api/TRACEABILITY.md}"
if [ "$#" -eq 0 ]; then
  if [ ! -f "$collection" ]; then
    info "api traceability: no tests/api collection yet, nothing to check"
    exit 0
  fi
  command -v node >/dev/null 2>&1 || die "api traceability: node is required"
  node "$MW_ROOT/tests/api/build_collection.mjs" --check >/dev/null \
    || die "api traceability: tests/api/mfs-mw.postman_collection.json is stale: run node tests/api/build_collection.mjs"
fi
[ -f "$collection" ] || die "api traceability: no collection at $collection"
[ -f "$table" ] || die "api traceability: no table at $table"

python3 - "$collection" "$table" <<'EOF'
import json, re, sys

TAGS = {"health", "seed", "p2p", "device", "cash", "merchant", "topup", "billpay", "callback", "addmoney", "c2w",
        "checkout", "ipn", "notify", "tsq", "reversal", "admin", "operator", "partner-config", "wallet-unlock",
        "ussd", "smpp", "bulk", "sp-query", "dlq"}
FLOWS = {"P2P app": "p2p", "CASH_IN app": "cash", "CASH_OUT app": "cash", "TOPUP app": "topup", "BILL_PAY app": "billpay",
         "ADD_MONEY_BANK app": "addmoney", "MERCHANT_PAY app": "merchant", "MERCHANT_PAY checkout": "checkout"}
USSD_FLOWS = ["P2P ussd", "CASH_OUT ussd", "TOPUP ussd", "BILL_PAY ussd"]
OUTCOMES = ["success", "failure", "tsq-commit", "tsq-release", "idempotent"]

required = [f + " " + o for f in FLOWS for o in OUTCOMES] + [f + " success" for f in USSD_FLOWS]
required += ["ops search-txnId", "ops search-msisdn", "ops timeline", "ops audit", "ops reversal-approved", "ussd http", "ussd smpp"]

def criterion(item):
    return 2 if item.endswith(" idempotent") else 4 if item.startswith("ops ") else 5 if item.startswith("ussd ") else 1

def needed_tags(item):
    """The requirement tags a request must carry to claim item (any one of each inner list)."""
    need = []
    if item.startswith("ops "):
        need.append(["operator", "admin"])
    elif item == "ussd http":
        need.append(["ussd"])
    elif item == "ussd smpp":
        need.append(["smpp"])
    else:
        flow = " ".join(item.split(" ")[:2])
        need.append(["ussd"] if flow.endswith(" ussd") else [FLOWS[flow]])
        if " tsq-" in item:
            need.append(["tsq"])
    return need

errors = []
coll = json.load(open(sys.argv[1]))
requests = []
for folder in coll.get("item", []):
    num = folder["name"].split(" ")[0]
    for it in folder.get("item", []):
        name = it["name"]
        requests.append((num, name))
        m = re.match(r"^\[([a-z0-9 -]+)\] \S", name)
        if not m:
            errors.append("request has no [tags] prefix: %s %s" % (num, name))
            continue
        for t in m.group(1).split():
            if t not in TAGS:
                errors.append("unknown tag '%s' in request %s %s" % (t, num, name))

ROW = re.compile(r"^\|\s*(\d\d)\s*\|\s*(.+?)\s*\|\s*([0-9, -]+?)\s*\|\s*(.*?)\s*\|\s*$")
rows = []
for line in open(sys.argv[2], encoding="utf-8"):
    m = ROW.match(line.rstrip("\n"))
    if m:
        crit = {int(c) for c in m.group(3).replace(" ", "").split(",") if c and c != "-"}
        covers = [c.strip() for c in m.group(4).split(";") if c.strip()]
        rows.append((m.group(1), m.group(2), crit, covers))

existing = ["%s %s" % r for r in requests]
mapped = ["%s %s" % (r[0], r[1]) for r in rows]
for r in existing:
    if r not in mapped:
        errors.append("request not mapped in TRACEABILITY.md: " + r)
for r in mapped:
    if r not in existing:
        errors.append("row names no request: " + r)
for r in sorted({x for x in mapped if mapped.count(x) > 1}):
    errors.append("request mapped twice: " + r)
for r in sorted({x for x in existing if existing.count(x) > 1}):
    errors.append("request name used twice in one folder: " + r)

claimed = set()
for num, name, crit, covers in rows:
    if any(c < 1 or c > 5 for c in crit):
        errors.append("criteria of %s %s must be 1-5 or -" % (num, name))
    m = re.match(r"^\[([a-z0-9 -]+)\]", name)
    tags = set(m.group(1).split()) if m else set()
    for item in covers:
        if item not in required:
            errors.append("'%s' of %s %s is not a known coverage item" % (item, num, name))
            continue
        if criterion(item) not in crit:
            errors.append("%s %s claims '%s' (criterion %d) without that criterion" % (num, name, item, criterion(item)))
        for alternatives in needed_tags(item):
            if not tags.intersection(alternatives):
                errors.append("%s %s claims '%s' without the tag %s" % (num, name, item, " or ".join(alternatives)))
        claimed.add(item)
for item in required:
    if item not in claimed:
        errors.append("coverage item claimed by no request: " + item)

if errors:
    for e in errors:
        print("api traceability: " + e, file=sys.stderr)
    sys.exit(1)
print("api traceability: ok (%d requests, %d coverage items claimed)" % (len(requests), len(claimed)))
EOF
