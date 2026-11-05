"""ZAP baseline gate (ticket 46): python3 zap-gate.py REPORT.json DECISIONS.tsv

Runs inside the ZAP image (scripts/security.sh) after zap-baseline.py wrote its JSON report.
Fails (exit 1) when an alert is High (ZAP has no Critical), when an alert has no decision in
DECISIONS.tsv (pluginid<TAB>accept|fix<TAB>reason), or when a 'fix' alert shows up again.
Prints every alert with its risk and decision. No third-party modules.
"""
import json
import sys

RISK = {"0": "INFO", "1": "LOW", "2": "MEDIUM", "3": "HIGH"}


def decisions(path):
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3:
                out[parts[0].strip()] = (parts[1].strip(), parts[2].strip())
    return out


def main(report, decision_file):
    try:
        with open(report, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as e:
        print("zap-gate: no readable ZAP report (%s): did zap-baseline.py reach the target?" % e)
        return 1
    known = decisions(decision_file)
    bad = 0
    for site in data.get("site", []):
        for a in site.get("alerts", []):
            pid = str(a.get("pluginid", ""))
            risk = RISK.get(str(a.get("riskcode", "")), "?")
            name = a.get("alert") or a.get("name") or ""
            kind, reason = known.get(pid, (None, ""))
            if risk == "HIGH":
                verdict, bad = "FAIL HIGH", bad + 1
            elif kind is None:
                verdict, bad = "FAIL no decision (add it to deploy/zap/decisions.tsv)", bad + 1
            elif kind == "fix":
                verdict, bad = "FAIL must be fixed: " + reason, bad + 1
            else:
                verdict = "accepted: " + reason
            print("%-6s %-5s %s (%s x%s) %s" % (risk, pid, name, site.get("@name", ""), a.get("count", "?"), verdict))
    print("zap-gate: %s" % ("FAILED" if bad else "passed"))
    return 1 if bad else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
