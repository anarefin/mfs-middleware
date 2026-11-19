# 130: Audit trail: payloads, search filters, per-hop timeline, audited CSV export (FRS AUD-01, AUD-03, AUD-04, AUD-05)

**What to build:** Close the audit gaps in `mw-audit-dw`: record masked inbound and outbound payloads with each hop,
search by partner, channel, status and amount range within 3 s over 30 days, show a full per-hop timeline with
latencies for one transaction, and export search results to CSV, where the export itself is audited.

**Principles:**
- Append-only and the hash chain stay exactly as built (AUD-02, ticket 74 anchor): payloads are new append-only rows
  linked by the audit record id and included in the chained hash.
- Payloads are masked **before** they leave the producing service (`MaskingPolicy`, CON-04 store of ticket 97);
  mw-audit-dw never receives a PIN, PAN or clear NID/MSISDN, and a defensive scrub runs again on write.
- Search runs on mw-audit-dw's own projections; the 3 s target is proven by a test on a 30-day seeded volume, not
  assumed.

**Scope:**
- **Schema `audit`** (Flyway): `audit.payload` (record id, direction IN/OUT, content type, masked body or a
  `payloadRef` to the adapter's payload store of ticket 97, size, sha256), monthly partitions like `audit.event`;
  indexes (or a `audit.txn_index` projection) on txnId, msisdn token, partner, channel, status, amount, time.
- **Producers:** the audit event schema `mw.audit.event.v1` (owner mw-audit-dw) gains optional `payload`,
  `direction`, `durationMs`, `hop` fields; the adapters' `PartnerAuditPublisher` and the channels' request logging send
  them (the "partner audit dropped when txnId is null" fix is ticket 97; here only keep it working for the new fields).
- **Search:** `AuditSearchController` filters partner, channel, status, amount range, MSISDN (via `TOKENISE_MSISDN`),
  time range; paginated; result in ≤ 3 s for 30 days (FRS AUD-03).
- **Timeline:** `GET /internal/v1/audit/transactions/{txnId}/timeline`: ordered hops (channel in, orchestrator steps,
  core calls, partner calls, callbacks, notifications) with latency and masked payloads; mw-admin-api transaction
  detail uses it (UI change in ticket 135).
- **Export:** `GET /internal/v1/audit/search/export.csv` (same filters, row cap config), and every export writes an
  `audit.operator-action` record (who, filters, row count).
- **Contracts:** `contracts/api/mw-audit-dw/audit-query.v1.openapi.yaml` updated; event schema and samples updated.
- **Docs:** `services/mw-audit-dw/CLAUDE.md`, traceability rows.

**FRS IDs covered:** AUD-01, AUD-03, AUD-04, AUD-05 (AUD-02 kept), CON-04 (audit side), FRS 10.4 criterion 6.

**Blocked by:** 97

**Status:** open

- [ ] `PayloadScrubTest`: a payload with a PIN-like field, PAN or clear MSISDN is masked again on write
- [ ] `HashChainWithPayloadIT`: payload rows are part of the chain; tampering a payload breaks `AuditChainVerifyJob`
- [ ] `AuditSearchPerfIT` (`@Tag("slow-wait")`): 30 days of seeded events, each filter combination answers in ≤ 3 s
- [ ] `TimelineIT`: a top-up through the stack-like fixture gives every hop in order with latencies
- [ ] `ExportAuditedIT`: an export returns CSV and writes one operator-action record
- [ ] `./gradlew :mw-audit-dw:build :mw-adapter-telco:build :mw-adapter-billpay:build :mw-adapter-bank:build :contractsLint` green with Docker
- [ ] API suite folder `64-audit`: search by partner and amount, timeline of a P2P, CSV export; `scripts/log-scan.sh` clean
