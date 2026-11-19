# 127: mw-recon: reports, trust position and exception work queue (FRS UC-22, REC-05, REC-06, REC-07, T12)

**Supersedes 88** (together with 126). Builds on the matching of ticket 126.

**What to build:** The output side of `mw-recon`: per-partner reports (daily, intraday, on demand) in CSV, XLSX and
PDF; the daily trust account position (sum of all e-money wallet balances vs the trust bank closing balance); and the
exception work queue API that the console workbench (ticket 134) uses, where every action is a maker-checker change
request in mw-admin-api.

**Principles:**
- mw-recon still **never moves money or a transaction state**: an exception action ("force complete", "reverse",
  "raise dispute", "accept as explained") is turned into a change request in mw-admin-api; only after a checker
  approves does mw-admin-api run the existing manual action (FORCE_COMMIT, REVERSE, ...; ADM-10). mw-recon only moves
  the exception to ACTIONED / RESOLVED when it sees the outcome event.
- Reports are computed from mw-recon's own tables; wallet balances come from core REST (ticket 94), read-only.
- "Zero unexplained differences" (FRS 10.4 #7) means every exception is either resolved or carries an explanation.

**Scope:**
- **Reports:** `service/ReportService` + `GET /internal/v1/recon/reports?partner=&from=&to=&kind=DAILY|INTRADAY&format=csv|xlsx|pdf`
  (Apache POI for XLSX, OpenPDF for PDF; totals by count and amount per class, Asia/Dhaka dates); a scheduled daily
  report per partner stored in `recon.report` for download.
- **Trust position (REC-07):** `scheduler/TrustPositionJob` after the MT940 cut-off: sum of e-money balances (core
  REST aggregate of CUSTOMER, AGENT, MERCHANT, DISTRIBUTOR, ORGANISATION wallets) vs the MT940 closing balance;
  `recon.trust_position` row with the difference; a difference ≠ 0 raises an exception of class `AMOUNT_MISMATCH`
  with `detail=TRUST_POSITION`.
- **Work queue API:** `GET /internal/v1/recon/exceptions` (filters class, partner, state, age), `GET .../{id}` with
  suggested action, `POST .../{id}/explanations`; mw-admin-api gets a `RECON_EXCEPTION` resource type in its
  change-request engine (roles `recon-maker` / `recon-checker` of the ops realm) that calls mw-recon and then the
  manual action.
- **Events:** `mw.recon.exception.v1` (FRS `recon.exception`, DAT-01) on open/resolve; consumer mw-audit-dw (and DW in
  128).
- **Docs:** service `CLAUDE.md`, runbook ("a day does not reconcile"), traceability rows.

**FRS IDs covered:** REC-05, REC-06, REC-07, UC-22 (reporting), DAT-01 `recon.exception`, T12, FRS 10.4 criterion 7.

**Blocked by:** 126

**Status:** open

- [ ] `ReportServiceTest`: totals per class match the fixture; CSV, XLSX (POI reads it back) and PDF (non-empty, page count) produced
- [ ] `TrustPositionTest`: equal balances → 0 difference, no exception; a gap → one exception with the exact amount
- [ ] `ExceptionActionIT`: an action creates a change request in mw-admin-api; nothing moves before approval; after approval and the outcome event the exception is RESOLVED
- [ ] mw-admin-api `ChangeRequestServiceTest` additions for `RECON_EXCEPTION` incl. maker ≠ checker (`MW-AUTH-030`)
- [ ] T12 for P2P, top-up, bill pay and C2W: next-day recon shows MATCHED, and an injected mismatch shows the right class — API suite folder `61-recon-reports`
- [ ] One full mock business day reconciles with zero unexplained differences (scripted in the same folder)
- [ ] `./gradlew :mw-recon:build :mw-admin-api:build :contractsLint` green with Docker
