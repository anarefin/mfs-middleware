# 74: HSM-signed daily audit anchor

**What to build:** The signed anchor of spec 14.3 (S12).

- **mw-audit-dw:**
  - a daily `scheduler/AnchorJob` (ShedLock) collects the chain head of every partition written that day and builds `{date, heads[{partition, hash, rowCount}], prevAnchorHash}`;
  - it signs that through mw-crypto `/jws/sign` (purpose `AUDIT_ANCHOR_SIGN`, mTLS client from ticket 66) and stores it in `audit.anchor` (INSERT-only);
  - export to the WORM target stays TBC: write the file to the existing export folder or volume.
- `service/ChainVerification` also verifies each anchor (the signature via JWKS, the heads match, the anchor chain is unbroken) and alerts on a failure.
- The admin console's audit view shows the anchor status (one field in the mw-admin-api internal API owned by mw-audit-dw).

**Blocked by:** 63, 66

**Status:** open

- [ ] `./gradlew :mw-audit-dw:build` green with Docker; a tampered row or anchor is detected in an IT
