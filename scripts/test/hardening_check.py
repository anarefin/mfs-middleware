"""Container hardening check for test_security.sh (ticket 46): python3 hardening_check.py COMPOSE.yml

Every long-running service named mw-* (not labelled mw.oneshot) must run as a non-root user,
with a read-only root FS, cap_drop ALL and no-new-privileges. Prints the gaps, exit 1 if any.
"""
import sys

import yaml

d = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
bad = []
for name, s in (d.get("services") or {}).items():
    if not name.startswith("mw-") or (s.get("labels") or {}).get("mw.oneshot") == "true":
        continue
    user = str(s.get("user", ""))
    if not user or user.split(":")[0] in ("0", "root"):
        bad.append(name + ": user")
    if s.get("read_only") is not True:
        bad.append(name + ": read_only")
    if "ALL" not in (s.get("cap_drop") or []):
        bad.append(name + ": cap_drop ALL")
    if "no-new-privileges:true" not in (s.get("security_opt") or []):
        bad.append(name + ": no-new-privileges")
print("\n".join(bad))
sys.exit(1 if bad else 0)
