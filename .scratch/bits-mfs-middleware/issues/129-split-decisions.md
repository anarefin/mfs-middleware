# 129 split (2026-11-20, auto-split rule; 1 h cap per part)

Ticket 129 touches mw-portal-api's upload state machine, two new external reads (core balance, mw-recon run
results, checkout refunds), a new API contract, Keycloak, APISIX, the test console and a Newman folder: too much
for one part. Split by seam, each part shippable on its own and leaving the live stack working.

- **129a-bulk-xlsx** (done, branch `ticket/129-portal-bulk-sp`): `service/BulkFileParser` = existing CSV
  (`BulkCsvParser`) + new XLSX (`BulkXlsxReader`, POI 6.0.0 SAX reader, never the user model) sharing one
  `BulkRowValidator`; upload `POST /portal/org/v1/bulk/batches` accepts `text/csv` and the XLSX media type; the
  all-or-nothing rule is kept (no state machine change yet, so the test console, Playwright `@bulk` and the Newman
  folders are unchanged). Bounds: zip pre-scan (<= 1000 entries, <= 64 MiB inflated), external-link part refused,
  formula refused (never evaluated), row cap = `bulk.max-rows`, cell cap (rows+1)*32, cell text cut at 256 chars,
  shared strings by an own SAX handler capped at (rows+1)*4 entries, numbers expanded only when small (security
  review fix: `1E999999999` exhausted the heap through BigDecimal).
  Contract `portal-org.v1` upload: XLSX media type + rules. Tests `BulkFileParserTest`, `BulkBatchControllerTest`
  (format by Content-Type), `BulkBatchApiIT.anXlsxUploadIsStoredLikeTheSameCsv`.
- **129b-bulk-preview** (129a): status `PREVIEW` on upload with totals, valid/invalid rows and per-row error codes
  (invalid rows stored, excluded from execution), `GET .../{id}/preview` paginated, `POST .../{id}/submit` ->
  `PENDING_APPROVAL`; Flyway next free V of mw-portal-api (V7 at the time of 129a); expiry of a PREVIEW batch;
  audit action `bulk.batch.submitted`; contract; test console org page shows the preview; Playwright `@bulk` and the
  existing Newman folders that upload adjusted (upload + submit). `BulkPreviewTest`.
  Note: the ticket names the path `/bulk-batches`; the built API is `/bulk/batches`. Keep `/bulk/batches` (no
  rename of a live contract) unless the user decides otherwise.
- **129c-bulk-funds-retry** (129b, 94): org wallet balance via core REST (ticket 94 client) at approve and at
  dispatch start -> `BLOCKED_FUNDS` with the shortfall (`MW-CORE-INSUFFICIENT_BALANCE`), nothing sent;
  `POST .../{id}/retry-failed` (maker-checker as the batch) re-dispatches FAILED rows with the same derived key
  `bulk:<batchId>:<line>`. `BalanceCheckIT`, `RetryFailedRowsIT`. Check first whether the orchestrator answers a
  re-sent FAILED request from its idempotency record with the old FAILED result: if so a retry needs an attempt
  suffix on the key or an orchestrator change, which is a money question for the user (a row must never pay twice).
- **129d-sp-portal** (116, 126): role `sp-viewer` in realm `portals`; `GET /portal/sp/v1/{transactions,settlements,
  refunds,reports}` scoped by the token's org only; new contract `portal-sp.v1.openapi.yaml` (owner mw-portal-api in
  `contracts/OWNERS.yml`), APISIX route `/portal/sp/v1`, test console SP page. `SpScopeTest`.
- **129e-api-suite** (129b-d): API suite folder 65 (assigned by the run; the ticket's "63" is taken): XLSX upload ->
  preview -> submit -> approve -> execute -> retry; SP queries; `TRACEABILITY.md`; testing guide.
