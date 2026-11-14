# 74: HSM-signed daily audit anchor

**What to build:** The signed anchor of spec 14.3 (S12).

- **mw-audit-dw:**
  - a daily `scheduler/AnchorJob` (ShedLock) collects the chain head of every partition written that day and builds `{date, heads[{partition, hash, rowCount}], prevAnchorHash}`;
  - it signs that through mw-crypto `/jws/sign` (purpose `AUDIT_ANCHOR_SIGN`, mTLS client from ticket 66) and stores it in `audit.anchor` (INSERT-only);
  - export to the WORM target stays TBC: write the file to the existing export folder or volume.
- `service/ChainVerification` also verifies each anchor (the signature via JWKS, the heads match, the anchor chain is unbroken) and alerts on a failure.
- The admin console's audit view shows the anchor status (one field in the mw-admin-api internal API owned by mw-audit-dw).

**Blocked by:** 63, 66

**Status:** done

- [x] `./gradlew :mw-audit-dw:build` green with Docker; a tampered row or anchor is detected in an IT
  (`service/AuditAnchorIT`: a chain rewritten and re-sealed by a superuser, a forged anchor, a deleted anchor)
- [x] `./gradlew :mw-admin-api:build`, `pnpm test` in `ui/mw-admin-ui`, `:checkIsolation :checkFieldInjection
  :contractsLint :composeCheck :scriptTests` green
- [ ] On the running stack (after `deploy/reset.sh`: the new Keycloak client): mw-audit-dw starts with its
  certificate, signs the day's anchor at mw-crypto, `<date>.jws` lands in the `audit-anchors` volume, the
  console's audit trail shows "Anchors intact" (deferred to final integration test)

**Decisions recorded (ticket asked):**
- Anchor shape: the chains are monthly, so a day's anchor covers the head of every monthly chain written
  that UTC day: `{date, heads[{partition: yyyy-MM, hash: hex of the chain's last row recorded before
  midnight, rowCount: that row's seq}], prevAnchorHash: hex SHA-256 of the previous anchor's compact JWS
  (64 zeros first)}`. A day without writes gets an anchor with empty heads, so anchors are daily without
  gaps from the first day of the first chain's month; a missing day is a verification failure.
- Signed ES256 with header `typ: mw-audit-anchor+jws`; job hourly at :15 UTC, catching up every past day
  not yet anchored (at most 31 per run), so a mw-crypto outage only delays the anchor.
- Export: no export folder existed, so a new one: `mw.audit-dw.anchor.export-dir`
  (`/var/lib/mw/audit-anchors`, compose named volume `audit-anchors`), one `<date>.jws` per day; WORM stays TBC.
- Verification lives in the hourly `AuditChainVerifyJob` (after the chains); alerts are log events
  (`audit.anchor.broken` ERROR, `audit.anchor.failed` ERROR, `audit.anchor.unverifiable` WARN) plus the gauge
  `mw.audit.anchor.broken`; runbook rows added. The status is in-memory (UNVERIFIED until the first check
  after a start) and travels as `AuditPage.anchor` (audit-query.v1, required) -> mw-admin-api
  `TxnDetail.auditAnchor` (admin.v1, optional) -> the console's audit trail badge `audit-anchor`.
- Not detected by design: deleting the newest anchors (the exported files are the outside copy).
