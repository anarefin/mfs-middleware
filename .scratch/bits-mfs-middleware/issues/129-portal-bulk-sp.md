# 129: Portals: bulk disbursement preview, XLSX, balance check, row retry; SP portal (FRS UC-20, CH-POR-01, 03, 04, 05)

**What to build:** Close the portal gaps in `mw-portal-api`:
1. **Bulk disbursement:** XLSX upload next to CSV, a preview with totals and per-row errors instead of rejecting the
   whole file, an organisation wallet balance check before execution, and retry of failed rows.
2. **SP portal:** service providers see their own transactions, settlements, refunds and reports, scoped to their
   organisation.

**Principles:**
- A batch is still one maker-checker approval (CH-POR-02, built), and each row is still its own transaction with its
  own txnId linked to the batch (FL-13, built). Rows with errors are excluded from execution, never "fixed" silently.
- The balance check reads the org wallet through core REST (ticket 94) at approval **and** again at execution start;
  a short balance blocks the batch (`MW-CORE-INSUFFICIENT_BALANCE`), nothing is sent.
- A retried row reuses its derived idempotency key (`bulk:<batchId>:<line>`), so a row never pays twice.
- XLSX parsing is bounded (row and cell limits, no formulas evaluated, no external links) to avoid zip bombs.

**Scope:**
- **mw-portal-api** `service/BulkFileParser` (CSV existing `BulkCsvParser` + new XLSX through Apache POI streaming
  `XSSF` reader), `POST /portal/org/v1/bulk-batches` now returns status `PREVIEW` with total count, total amount,
  valid and invalid rows and per-row error codes; `GET .../{id}/preview` paginated; `POST .../{id}/submit` sends the
  valid rows for approval. `POST .../{id}/retry-failed` (maker-checker as the batch) re-dispatches FAILED rows only.
- **Balance check:** at approve and at dispatch start; batch status `BLOCKED_FUNDS` with the shortfall.
- **SP portal:** role `sp-viewer` in the Keycloak `portals` realm; `GET /portal/sp/v1/transactions`, `/settlements`
  (from mw-recon run results, ticket 126 internal API), `/refunds` (checkout refunds of ticket 116), `/reports`
  (CSV); every query scoped by the org id in the token, never by a request parameter.
- **Contracts:** `contracts/api/mw-portal-api/portal-org.v1.openapi.yaml` updated; new `portal-sp.v1.openapi.yaml`
  (owner mw-portal-api in `contracts/OWNERS.yml`); APISIX route for `/portal/sp/v1`.
- **Test console:** org portal page shows the preview and retry; SP page lists the SP's data.
- **Docs:** `services/mw-portal-api/CLAUDE.md`, testing guide, traceability.

**FRS IDs covered:** UC-20, CH-POR-01, CH-POR-03, CH-POR-04, CH-POR-05, FL-13 (kept).

**Blocked by:** 95, 116

**Status:** open

- [ ] `BulkFileParserTest`: CSV and XLSX give the same rows; formula, oversize and malformed files rejected
- [ ] `BulkPreviewTest`: totals and per-row errors; only valid rows submitted
- [ ] `BalanceCheckIT`: short funds at approval or at dispatch → `BLOCKED_FUNDS`, no child txn created
- [ ] `RetryFailedRowsIT`: only FAILED rows re-dispatched, same idempotency key, a row never pays twice (core-mock balance check)
- [ ] `SpScopeTest` (`@WebMvcTest`): an SP token never sees another org's data, whatever the query parameters
- [ ] API suite folder `63-portal-bulk-sp`: XLSX upload → preview → approve → execute → retry; SP queries
- [ ] `./gradlew :mw-portal-api:build :contractsLint` green with Docker; `scripts/tester-e2e.sh @bulk` green
