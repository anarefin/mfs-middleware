# mw-portal-api

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.portal.api`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Org portal bulk disbursement, upload and maker-checker (spec 9.4, WP22 part, ticket 33) behind
  the APISIX route `/portal/org/v1/*` (`deploy/apisix/routes/portal-org-v1.json`,
  `openid-connect` bearer-only on realm `portals`, body cap 3 MiB, 60 req/min per token subject).
  Execution of an approved batch (ticket 34, below); SP transaction query (ticket 35, below); SFTP
  drop is a later ticket.
- SP transaction query (ticket 35, WP22; `controller/OrgTransactionController` ->
  `service/OrgTransactionService` -> `client/OrchestratorQueryClient`): `GET /portal/org/v1/transactions`
  (`batchId` (a batch of the org, else 404 MW-VAL-003; never approved -> empty), `status`, `[from, to)`,
  limit 1..200, offset) and `GET /portal/org/v1/transactions/{txnId}` (transaction + `timeline` of
  state changes; another org's or unknown txn 404 MW-VAL-003), maker or checker. The org sees what
  it initiated (bulk children) and what it collects as payee (bill / merchant payments), each with
  `role` INITIATOR|PAYEE as the orchestrator tags it (review fix A3; which billers and merchant
  wallets an org collects for is the orchestrator's `mw.orchestrator.search.org-payees`, never a
  portal parameter). The timeline keeps only `kind` STATE entries (`OrgTxn.Detail.statesOnly`): the
  orchestrator's core-command entries (B13) stay internal. The SP is the org:
  the client always sends the **token's** `orgId` to mw-orchestrator's internal query API
  (`api/mw-orchestrator/txn-query.v1`, `mw.portal-api.orchestrator.base-url`, default
  `http://mw-orchestrator:8080`, read timeout 5 s), never a request parameter, and never reads the
  `txn` schema. Every call carries this service's Keycloak client-credentials token (realm
  `services`, client `mw-portal-api`, M3 gate decision; `client/ServiceTokenClient`: HTTP Basic client
  auth to `mw.portal-api.orchestrator.auth.token-uri` (`MW_INTERNAL_TOKEN_URI`), cached until 30 s
  before `expires_in`, dropped when the orchestrator answers 401; secret
  `mw.portal-api.orchestrator.auth.client-secret` in `secret/mw-portal-api`, synced by the init from
  `KC_MW_PORTAL_API_SECRET` of `deploy/dev.env.defaults`). No token -> `503 MW-SYS-040`.
  `OrchestratorClientAuthTest`. Resilience4j `orchestrator` (retry + circuit breaker on
  `OrchestratorUnavailableException` only); unreachable / non-200 / unreadable -> `503 MW-SYS-040`
  (retryable). The view (`dto/OrgTxn`) drops correlation id, channel, initiator and partner calls;
  customer data arrives masked. Same edge route `/portal/org/v1/*`. `OrgTransactionApiIT` (stub
  orchestrator).
- SP portal (ticket 129d, FRS UC-20, CH-POR-05; `controller/SpPortalController` -> `service/SpPortalService`
  -> `client/OrchestratorQueryClient`), behind the APISIX route `/portal/sp/v1/*`
  (`deploy/apisix/routes/portal-sp-v1.json`, GET only, as the org route otherwise), contract
  `api/mw-portal-api/portal-sp.v1.openapi.yaml`. Realm role `sp-viewer` only
  (`mw.portal-api.roles.sp-viewer`; else `403 MW-AUTH-002`), read-only. Every read goes to the orchestrator
  with the **token's** `orgId` and only the filters named in the service: an `orgId` (or any unknown)
  query parameter changes nothing (`SpScopeTest`). `GET /portal/sp/v1/transactions` (`status`, `[from, to)`,
  limit 1..200, offset) and `/transactions/{txnId}` (another org's or unknown `404 MW-VAL-003`; state
  changes only) are the org query's views. `GET /portal/sp/v1/reports?from&to[&status]` (`mapper/SpReportFile`,
  `text/csv` attachment `sp-report-<from>-<to>.csv`): the org's transactions of `[from, to)`, read 200 a
  page; range at most `mw.portal-api.sp.report-max-range` (31 d, else `400 MW-VAL-001`), at most
  `report-max-rows` (10,000, else `400 MW-VAL-040`); party ids as the orchestrator masked them, amounts in
  taka, a cell starting `= + - @`, tab or CR prefixed with `'` (CSV injection). Settlements (mw-recon) and
  refunds (mw-checkout) are not served yet: slice 129f in the ticket's split decisions. Dev user
  `sp-viewer02` (ORG002) in `portals-realm.json`; the test console's `/sp` page.
- Auth (`config/SecurityConfig`, `security/`): bearer JWT of realm `portals` on `/portal/**`, verified
  again here (JWKS `mw.portal-api.jwt.jwk-set-uri`, issuer must end with `/realms/portals`). The
  org is the `orgId` claim (user attribute + `mw-portal` client mapper in `portals-realm.json`);
  maker-checker compares the token `sub`, `preferred_username` is shown as `makerId`/`checkerId`
  (staff usernames are not masked). No token / bad token `401 MW-AUTH-001`; no org `403
  MW-AUTH-002`. Roles per operation (`mw.portal-api.roles`, checked in `service/BatchService`):
  upload `org-maker`; approve/reject `org-checker`; list/get either. Missing role `403 MW-AUTH-002`.
- Upload `POST /portal/org/v1/bulk/batches` (body `text/csv` or XLSX
  `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` by `Content-Type`, ticket 129a;
  `service/BulkFileParser` -> `BulkCsvParser` or `BulkXlsxReader`, both into `BulkRowValidator`, so
  both formats give the same rows and errors). XLSX is read bounded: a ZIP pre-scan (at most 1,000
  entries, 64 MiB inflated, else `MW-VAL-030`), an `xl/externalLinks/` part refused, then the first
  sheet through POI's SAX reader (never the user model): a formula anywhere `MW-VAL-031` (never
  evaluated), more data rows than `bulk.max-rows` or more than (rows+1)*32 cells `MW-VAL-030`, cell
  text cut at 256 chars; shared strings read by their own SAX handler, at most (rows+1)*4 entries
  (else `MW-VAL-030`), each cut at 256 chars (never POI's whole table); a number in plain decimal
  notation only when small (exponent of at most 2 digits, scale within +-20, at most 30 digits),
  else its raw text, which fails the amount rule (`1E999999999` is never expanded); `line` = the
  sheet row; not a workbook
  `MW-VAL-031` field `file`. Rules (both formats): header
  `msisdn,amount,reference`, MSISDN in any accepted form (`util/Msisdn`, stored canonical), amount in
  taka > 0 with at most 2 decimals (stored in poisha), reference `[A-Za-z0-9._-]{1,32}` unique in
  the file. Whole-file refusals: more than `bulk.max-rows` (10,000) data rows or `bulk.max-bytes` (2 MB)
  `400 MW-VAL-030`; a bad header or no rows `400 MW-VAL-031`; nothing stored. Otherwise (ticket
  129b, preview) the file is one `bulk.batch` row (`PREVIEW`, `row_count` = every data row,
  `invalid_count`, `total_minor` = the valid rows, file SHA-256, maker) plus one `bulk.batch_item`
  per row in one transaction: valid rows `PENDING`, invalid rows `INVALID` with `row_errors`
  (`MSISDN_INVALID`, `AMOUNT_INVALID`, `REFERENCE_INVALID`, `REFERENCE_DUPLICATE`, `FIELD_COUNT`;
  `dto/InvalidRow`), no MSISDN and only the amount / reference that passed their rule (values never
  kept or echoed; CHECKs in `V7__bulk_preview.sql`); reference uniqueness holds among non-INVALID rows.
  `201` + `Location`, the view with `validCount`, `invalidCount` and `rowErrors` (first 100). An
  INVALID row is never sent (the dispatcher takes `PENDING` only) and never blocks completion.
  `GET .../{batchId}/preview?offset&limit(1..200, default 50)&valid` pages through the rows in file
  order (MSISDN masked; maker or checker, any status; bad paging `400 MW-VAL-001`).
  `POST .../{batchId}/submit`: the maker who uploaded it (else `403 MW-AUTH-002`) sends a `PREVIEW`
  to `PENDING_APPROVAL` (one conditional UPDATE); not a preview `409 MW-VAL-036`, no valid row `409
  MW-VAL-037`; audited `bulk.batch.submitted`. `BulkPreviewTest`, `BulkBatchApiIT`.
- Decide `POST .../{batchId}/approve` and `.../reject {reason}`: the maker of a batch can never
  decide it, even holding both roles (`403 MW-AUTH-030`; also a CHECK constraint in the schema);
  not pending `409 MW-VAL-032`; one conditional UPDATE, so of racing checkers exactly one wins.
  Another org's batch is `404 MW-VAL-003` everywhere. List filter `?status=` is a String mapped in
  the service (`BatchStatus.parse`; unknown `400 MW-VAL-001`); the controller binds no entity type.
- Expiry (M3 gate decision; `service/BatchExpiryService`, `scheduler/BulkExpiryJob`, ShedLock
  `bulk-expiry`, every `bulk.expiry.period` 5 min, `MW_BULK_EXPIRY_ENABLED`): a batch still
  `PREVIEW` (never submitted) or `PENDING_APPROVAL` `bulk.expiry.after` (72 h) after its upload becomes `EXPIRED` (`decided_at` =
  expiry time, one conditional UPDATE `FOR UPDATE SKIP LOCKED`, at most `batch-size` per run); then
  approve/reject `409 MW-VAL-032`, result `409 MW-VAL-035`.
- Audit (M3 gate decision; `service/BatchAudit` -> `mapper/BatchAuditMapper`, own type `dto/AuditEvent`):
  upload, approve, reject, expire, pause and resume each write one `mw.audit.event.v1` record to the
  outbox in the action's transaction (a refused action writes none): action `bulk.batch.<uploaded|
  submitted|approved|rejected|expired|paused|resumed>`, resource `bulk-batch/<batchId>`, actor `user`/<operator's
  `preferred_username`> (staff id, **not masked**) or `system`/`mw-portal-api` for the expiry, `before`
  `{status}` (absent on upload), `after` `{status, orgId, rowCount, totalMinor, currency, reason?}`
  (a rejection's reason through `MaskingPolicy.scrub`), `txnId` the parent txn once approved, key the batch's parent txnId (`util/BulkIds`, from the upload on, so one batch's trail keeps its order on one partition) rather than the
  actor id. The rows (MSISDNs) are never copied into an audit event. `BulkLifecycleIT` validates
  every event against the contract.
- Execution (ticket 34, spec 9.4, WP22 part; `service/BulkExecutionService`, schema V5). Approval
  needs the org's payout wallet in `bulk.org-wallets` (orgId -> core wallet; ORG001/ORG002 ->
  the core mock's `01311000001`/`...002`), else `409 MW-VAL-034`, and stores the parent
  `BULK_DISBURSE` txnId (`batch.parent_txn_id`, `util/BulkIds.parentTxnId`) and the wallet.
  **Decision:** the parent is the batch's own record and id, not an orchestrator saga (it moves no
  money); every child carries it in `attributes.parentTxnId` (+ `batchId`, `lineNo`, `reference`).
  `scheduler/BulkDispatchJob` (ShedLock `bulk-dispatch`, every `bulk.dispatch.period` 200 ms) ->
  `dispatch()`: token bucket at `bulk.dispatch.rate-per-second` (`MW_BULK_RATE_PER_SECOND`, default
  50) over all running batches, holding at most one period's worth (an idle dispatcher saves no
  burst); locks that many PENDING rows of EXECUTING batches (an APPROVED one is started first by the balance check below) (`FOR NO KEY UPDATE OF i, b
  SKIP LOCKED`, oldest approval first, file order; the batch row lock makes a pause wait for a
  running dispatch), writes one `mw.txn.request.v1` record per row to the outbox (channel
  `ORG_PORTAL`, txnType `BULK_DISBURSE`, initiator `{ORG, orgId}`, debit `{ORG, wallet}`, credit
  `{WALLET, msisdn, CUSTOMER}`, no PIN, correlationId `bulk-<batchId>`) and marks the rows
  SUBMITTED with their txnId, in one transaction. txnId (UUIDv7 shape:
  the batch id's timestamp + SHA-256 of `bulk:<batchId>:<line>`) and key `bulk-<batchId>-<line>`
  are derived, so a re-run after a crash sends the same request and the orchestrator answers it
  from its idempotency record (never pays twice; `BulkExecutionIT`). Throttle measured on a test
  clock in `BulkExecutionIT`. `POST .../pause` (checker; APPROVED/EXECUTING -> PAUSED) and
  `.../resume` (PAUSED -> EXECUTING), else `409 MW-VAL-033`; rows already sent still settle.
  The `@KafkaListener` `messaging/TxnStatusListener` consumes `mw.txn.status.v1` (group
  `mw-portal-api`, retry topics + DLQ; `mw.portal-api.consumers.enabled`; `TxnStatusKafkaIT`) -> `apply()`: only
  ORG_PORTAL BULK_DISBURSE records, only settled states (`entity/ItemStatus.settledBy`: COMPLETED,
  FAILED, REJECTED, REVERSED, MANUAL_REVIEW), one conditional UPDATE by txn_id (FAILED/REJECTED/
  REVERSED final, COMPLETED only to REVERSED), so redeliveries are no-ops without an inbox row;
  then the batch is COMPLETED (+ `completed_at`) once no row is PENDING/SUBMITTED, or
  `COMPLETED_WITH_EXCEPTIONS` when any row is MANUAL_REVIEW or UNRESOLVED (M3 gate decision). Which
  txn states settle a row is said once, in `entity/ItemStatus` (`settledBy`, `exception()`), never
  as string literals elsewhere.
- Reconcile (M3 gate decision; `service/BulkReconcileService`, `scheduler/BulkReconcileJob`, ShedLock
  `bulk-reconcile`, every `bulk.reconcile.period` 1 min, `MW_BULK_RECONCILE_ENABLED`): up to
  `batch-size` (200) SUBMITTED rows sent more than `bulk.reconcile.after` (10 min) ago, least recently
  checked first (`batch_item.reconciled_at`, V6), are looked up with `GET /internal/v1/transactions/
  {txnId}?orgId=<batch org>` (the authenticated client above). A settled state settles the row as the
  status record would (`BulkExecutionService.settle`); a row still unsettled or unknown to the
  orchestrator `give-up-after` (24 h) after it was sent becomes `UNRESOLVED` (WARN `bulk.row.unresolved`;
  a later status record still settles it, the batch keeps its final status). An unavailable
  orchestrator ends the run with nothing changed. Nothing is re-sent.
- Balance check (ticket 129c, FRS CH-POR-04; `service/FundsCheck` -> `client/CoreBalanceClient`, schema V8):
  the org payout wallet's `available` from the core wallet API `GET /core/v1/balance/{walletId}`
  (`api/mw-core-mock/core-wallet.v1`, consumed; `mw.portal-api.core.base-url` `MW_CORE_BASE_URL`, read
  timeout 3 s; the same client-credentials token as the orchestrator calls, realm `services` client
  `mw-portal-api` with audience mapper `aud-mw-core-mock`; the core lets this client read ORGANISATION
  wallets only, `mw.internal-auth.balance-clients`). At approve (PENDING_APPROVAL, or BLOCKED_FUNDS again)
  against the batch's total, and at dispatch start (`BulkExecutionService.startApproved`: every APPROVED
  batch, against its PENDING rows, before anything is sent; the dispatcher only sends EXECUTING batches):
  short -> `BLOCKED_FUNDS` with `shortfall_minor` and `blocked_reason` `MW-CORE-INSUFFICIENT_BALANCE`
  (view `shortfall`, `blockedReason`; approve answers 200 with it; audited `bulk.batch.blocked_funds` at
  approve; WARN `bulk.batch.blocked_funds`), nothing sent; a checker approves it again once funded (or
  rejects it). No balance (unreachable, non-200, unreadable): approve `503 MW-SYS-041` (retryable),
  nothing changed; at dispatch start the batch stays APPROVED for the next run (WARN
  `bulk.batch.balance_unavailable`). `mw.portal-api.core.balance-check` (`MW_BULK_BALANCE_CHECK`, default
  true; off in the test config, `BalanceCheckIT` switches it on against a stub core). A retry round
  (below) is not balance-checked: the core refuses a short posting row by row. `BalanceCheckIT`.
- Retry of FAILED rows (ticket 129c, FRS CH-POR-03; `service/BatchService.requestRetry|approveRetry`,
  `service/BulkRetryService`, `scheduler/BulkRetryJob` ShedLock `bulk-retry`, every `bulk.retry.period` 2 s,
  `MW_BULK_RETRY_ENABLED`; schema V8). **A row never pays twice.** The orchestrator answers a re-sent key
  that ended FAILED with that FAILED (its idempotency record), so a retried row goes out as a new
  attempt: `batch_item.attempt` n > 0, txnId from `bulk:<batchId>:<line>:<n>`, key
  `bulk-<batchId>-<line>-a<n>` (`util/BulkIds`; attempt 0 keeps the ticket 34 ids). Maker-checker as
  the batch: `POST .../{batchId}/retry-failed` (org-maker) records a request on a COMPLETED /
  COMPLETED_WITH_EXCEPTIONS batch with a FAILED row (else `409 MW-VAL-038`; one pending `409
  MW-VAL-039`); `POST .../retry-failed/approve` (org-checker, never the requester `403 MW-AUTH-030`;
  none pending `409 MW-VAL-039`) is one conditional UPDATE: `retry_round` + 1, batch EXECUTING, FAILED
  rows -> `RETRY_CHECK`; a repeated click finds nothing. Only FAILED rows (never REJECTED, REVERSED,
  MANUAL_REVIEW, UNRESOLVED, in flight). The job reads each RETRY_CHECK row's failed attempt from the
  orchestrator's query API and sends it again (PENDING, attempt + 1 only from the attempt it read,
  txn_id cleared so a late record of the old attempt matches nothing) only when it is still FAILED,
  never was UNKNOWN, has no `coreRef` and every PostTransfer was refused (`BulkRetryService.postedNothing`,
  from the timeline's CORE entries, never shown to the org); else back to FAILED (WARN
  `bulk.row.retry_refused`). Unavailable orchestrator: the rows wait. A RETRY_CHECK row keeps the batch
  open and is not settled by status records. Audited `bulk.batch.retry_requested|retry_approved`.
  Views carry `retryRound` and `retryRequestedBy`. `RetryFailedRowsIT`.
- Views of approved batches carry `parentTxnId`, `progress` (pending, inFlight, completed, failed, manualReview, unresolved, paidAmount), `completedAt`. `GET .../result`
  (`mapper/ResultFile`, maker or checker, `text/csv`, attachment): `line,msisdn,amount,reference,
  status,txnId,errorCode,batchStatus` (`batchStatus` = the batch's status on every line, e.g.
  COMPLETED_WITH_EXCEPTIONS), MSISDN masked (18.3), current state while running; before approval
  `409 MW-VAL-035`. No re-send of a SUBMITTED row that never settles (the reconcile job above settles
  or gives it up).
- Dev provisioning (`provision_mw_portal_api` in `deploy/provision/services.sh`, run by the one-shot `provision` on every `docker compose up`, idempotent, dev only): role
  `portal_api_rw`, schema `bulk`, `secret/mw-portal-api` (DB password; realm `services` client
  secret from `KC_MW_PORTAL_API_SECRET`), AppRole.

## Contracts owned

- `api/mw-portal-api/portal-sp.v1.openapi.yaml` (SP portal API v1, ticket 129d; GET only, no JSON body).
- `api/mw-portal-api/portal-org.v1.openapi.yaml` (org portal API v1). APISIX checks no body (review
  A6); the edge answers 401/413/429 itself (`deploy/apisix/README.md`). The only JSON body, reject
  `{reason}`, is checked here before binding against
  `src/main/resources/derived-schemas/portal-org-v1-reject-request.json` (derived from the contract's
  inline reject body schema; `scripts/derived-schemas.sh --write` after changing it, `./gradlew
  :contractsLint` fails on drift) by `filter/BodySchemaAdvice` -> `service/BodySchema` (copied from
  mw-app-gateway: duplicate keys, trailing content, non-objects refused; `400 MW-VAL-001`; over 64 KiB
  `413 MW-VAL-BODY_TOO_LARGE`); then Bean Validation. approve/pause/resume take no body. The CSV
  upload is not JSON: `BulkFileParser` and its caps (above) check it. A new JSON body type needs its
  derived schema added to the advice.

## Contracts used

- `contracts.yml`: the machine-readable list of what this module produces and consumes (checked
  by `./gradlew :contractsLint`, tested by `contracts/ProducedContractsTest` / `ConsumedContractsTest`,
  and `contracts/ApiContractsTest` for the served and called APIs; no Docker).
- `events/mw-audit-dw/mw.audit.event.v1` (produced, own type `dto/AuditEvent`; `BulkLifecycleIT`
  validates every batch audit event against it). Since ticket 130b `BulkExecutionService` also writes
  each child's CHANNEL_IN hop (`BatchAuditMapper.childSent`: `channel.request.completed`, resource
  `txn/<txnId>`, actor `system`/`mw-portal-api`, direction IN, payload the masked child request) in the
  dispatch transaction (`BulkExecutionIT`).

- `events/mw-orchestrator/mw.txn.request.v1` (produced, own type `dto/TxnRequest`;
  `BulkExecutionIT` validates every child against it) and `mw.txn.status.v1` (consumed, own type
  `dto/StatusMessage`; `StatusMessageContractTest` reads every valid sample).
- `api/mw-orchestrator/txn-query.v1.openapi.yaml` (client, own type `dto/OrgTxn`, unknown fields
  ignored; `coreRef` and the timeline's CORE entries are read for the retry check, never shown).
- `api/mw-core-mock/core-wallet.v1.openapi.yaml` (client, ticket 129c: only `GET /core/v1/balance/{walletId}`
  of the org payout wallet, `client/CoreBalanceClient`).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (written inline in
  `build.gradle.kts`; drop once the Boot BOM ships them); the shared dev provisioning image `deploy/provision/Dockerfile` runs `apk upgrade` and removes gosu. `scripts/security.sh <this module>` scans the SBOM and the
  `mfs-mw/*:local` images (HIGH/CRITICAL fail; accepted ones in `.trivyignore.yaml` with a reason and expiry).
- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field.
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
  Add context with `log.atInfo().addKeyValue("event", "...")` or the MDC.
- `config/MwServiceProperties`: config binding; secrets from Vault when `VAULT_ENABLED=true`.
- Kafka through Spring Kafka (ADR D27; no Camel in this module, copied from the template, ticket 79):
  `messaging/EventPublisher` sends records with the 6.1 headers through `KafkaTemplate` (idempotent,
  `acks=all`, at most 5 in flight; `publish` waits for the ack, `publishStoredAsync` sends an outbox row
  with exactly its stored headers). `config/KafkaConfig` puts every `@KafkaListener` on non-blocking retry
  topics `<topic>.<group>.retry.1..3` (10 s / 1 min / 10 min, `mw.kafka.retry.delays`) then
  `<topic>.<group>.dlq` (`messaging/RetryTopicNaming`, group = `spring.kafka.consumer.group-id`, the
  service name; declared in `deploy/kafka/topics.txt`); validation and JSON failures and records that are
  not UTF-8 go to the DLQ at once; `messaging/RetryTopicRecoverer` adds `mw-retry-attempt`,
  `mw-original-topic`, `mw-error-class`, `mw-retry-not-before`; `messaging/RetryDueTimeCap` caps a forged
  due time; `messaging/ConsumerContextInterceptor` puts the record's ids in the MDC. Listeners take
  `ConsumerRecord<String, String>` and `Acknowledgment` and call `ack.acknowledge()` after handling
  (per-record commit, AckMode `MANUAL_IMMEDIATE`); a delay pauses the partition, never sleeps on the
  consumer thread. Settings: `spring.kafka.*`, `mw.kafka.send-timeout`, `mw.kafka.listener.shutdown-timeout`
  (100 ms in tests), `mw.kafka.retry.exclude-topics`. `messaging/RetryTopicIT` (conformance) checks it all.
- `scheduler/OutboxRelayScheduler`: relays the transactional outbox (`messaging/OutboxRelay`, 6.4) every
  `mw.outbox.relay.period` after a run ends, ShedLock-guarded; off in tests (`mw.outbox.relay.enabled`).
  Tests that consume create their topics with retry topics and DLQ first (`KafkaTestTopics.createWithRetries`).
- `application.yml`: Resilience4j defaults (19.2), own schema `bulk` (spec 16.2) migrated by Flyway on
  start from `db/migration` (16.4).

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-portal-api:test` | every test in one JVM: unit, slice and contract tests (`contracts/`), the Testcontainers `*IT` classes and the `@Conformance` suite; one PostgreSQL and one Kafka per test JVM, a fresh database per Spring context; the `*IT` and conformance classes are skipped without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-portal-api:build` | `test`, then the boot jar `build/libs/mw-portal-api.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-portal-api:bootJar && docker build -t mw-portal-api:dev services/mw-portal-api` | image, as spec 4.3 rule 1 |
| `./gradlew clean build` | the full gate: every module, plus the repo checks (`:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`); unchanged modules come from the build cache |
| `./gradlew :mw-portal-api:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-portal-api` | rebuild this service's image (`mfs-mw/mw-portal-api:local`) and restart it with what it depends on (dev timers from `deploy/.env`) |
