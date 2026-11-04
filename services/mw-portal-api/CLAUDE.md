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
  state changes; another org's or unknown txn 404 MW-VAL-003), maker or checker. The SP is the org:
  the client always sends the **token's** `orgId` to mw-orchestrator's internal query API
  (`api/mw-orchestrator/txn-query.v1`, `mw.portal-api.orchestrator.base-url`, default
  `http://mw-orchestrator:8080`, read timeout 5 s), never a request parameter, and never reads the
  `txn` schema. Resilience4j `orchestrator` (retry + circuit breaker on
  `OrchestratorUnavailableException` only); unreachable / non-200 / unreadable -> `503 MW-SYS-040`
  (retryable). The view (`dto/OrgTxn`) drops correlation id, channel, initiator and partner calls;
  customer data arrives masked. Same edge route `/portal/org/v1/*`. `OrgTransactionApiIT` (stub
  orchestrator).
- Auth (`config/SecurityConfig`, `security/`): bearer JWT of realm `portals` on `/portal/**`, verified
  again here (JWKS `mw.portal-api.jwt.jwk-set-uri`, issuer must end with `/realms/portals`). The
  org is the `orgId` claim (user attribute + `mw-portal` client mapper in `portals-realm.json`);
  maker-checker compares the token `sub`, `preferred_username` is shown as `makerId`/`checkerId`
  (staff usernames are not masked). No token / bad token `401 MW-AUTH-001`; no org `403
  MW-AUTH-002`. Roles per operation (`mw.portal-api.roles`, checked in `service/BatchService`):
  upload `org-maker`; approve/reject `org-checker`; list/get either. Missing role `403 MW-AUTH-002`.
- Upload `POST /portal/org/v1/bulk/batches` (body `text/csv`, `service/BulkCsvParser`): header
  `msisdn,amount,reference`, MSISDN in any accepted form (`util/Msisdn`, stored canonical), amount in
  taka > 0 with at most 2 decimals (stored in poisha), reference `[A-Za-z0-9._-]{1,32}` unique in
  the file. All or nothing: more than `bulk.max-rows` (10,000) data rows or `bulk.max-bytes` (2 MB)
  `400 MW-VAL-030`; any bad row `400 MW-VAL-031` with a `details[]` entry per problem (`line <n>:
  <column>`, header = line 1, first 100 listed), values never echoed, nothing stored. A valid file
  is one `bulk.batch` row (`PENDING_APPROVAL`, row count, total, file SHA-256, maker) plus one
  `bulk.batch_item` per row (`PENDING`), in one transaction; `201` + `Location`.
- Decide `POST .../{batchId}/approve` and `.../reject {reason}`: the maker of a batch can never
  decide it, even holding both roles (`403 MW-AUTH-030`; also a CHECK constraint in the schema);
  not pending `409 MW-VAL-032`; one conditional UPDATE, so of racing checkers exactly one wins.
  Another org's batch is `404 MW-VAL-003` everywhere. No expiry of pending batches yet (15.3's
  72 h applies to admin change requests).
- Execution (ticket 34, spec 9.4, WP22 part; `service/BulkExecutionService`, schema V5). Approval
  needs the org's payout wallet in `bulk.org-wallets` (orgId -> core wallet; ORG001/ORG002 ->
  the core mock's `01311000001`/`...002`), else `409 MW-VAL-034`, and stores the parent
  `BULK_DISBURSE` txnId (`batch.parent_txn_id`, `util/BulkIds.parentTxnId`) and the wallet.
  **Decision:** the parent is the batch's own record and id, not an orchestrator saga (it moves no
  money); every child carries it in `attributes.parentTxnId` (+ `batchId`, `lineNo`, `reference`).
  `scheduler/BulkDispatchJob` (ShedLock `bulk-dispatch`, every `bulk.dispatch.period` 200 ms) ->
  `dispatch()`: token bucket at `bulk.dispatch.rate-per-second` (`MW_BULK_RATE_PER_SECOND`, default
  50) over all running batches, holding at most one period's worth (an idle dispatcher saves no
  burst); locks that many PENDING rows of APPROVED/EXECUTING batches (`FOR NO KEY UPDATE OF i, b
  SKIP LOCKED`, oldest approval first, file order; the batch row lock makes a pause wait for a
  running dispatch), writes one `mw.txn.request.v1` record per row to the outbox (channel
  `ORG_PORTAL`, txnType `BULK_DISBURSE`, initiator `{ORG, orgId}`, debit `{ORG, wallet}`, credit
  `{WALLET, msisdn, CUSTOMER}`, no PIN, correlationId `bulk-<batchId>`) and marks the rows
  SUBMITTED with their txnId, in one transaction; APPROVED -> EXECUTING. txnId (UUIDv7 shape:
  the batch id's timestamp + SHA-256 of `bulk:<batchId>:<line>`) and key `bulk-<batchId>-<line>`
  are derived, so a re-run after a crash sends the same request and the orchestrator answers it
  from its idempotency record (never pays twice; `BulkExecutionIT`). Throttle measured on a test
  clock in `BulkExecutionIT`. `POST .../pause` (checker; APPROVED/EXECUTING -> PAUSED) and
  `.../resume` (PAUSED -> EXECUTING), else `409 MW-VAL-033`; rows already sent still settle.
  `route/TxnStatusRoute` + `messaging/TxnStatusListener` consume `mw.txn.status.v1` (group
  `mw-portal-api`, retry topics + DLQ; `mw.portal-api.consumers.enabled`) -> `apply()`: only
  ORG_PORTAL BULK_DISBURSE records, only settled states (`entity/ItemStatus.settledBy`: COMPLETED,
  FAILED, REJECTED, REVERSED, MANUAL_REVIEW), one conditional UPDATE by txn_id (FAILED/REJECTED/
  REVERSED final, COMPLETED only to REVERSED), so redeliveries are no-ops without an inbox row;
  then the batch is COMPLETED (+ `completed_at`) once no row is PENDING/SUBMITTED (MANUAL_REVIEW
  counts as settled). Views of approved batches carry `parentTxnId`, `progress` (pending,
  inFlight, completed, failed, manualReview, paidAmount), `completedAt`. `GET .../result`
  (`mapper/ResultFile`, maker or checker, `text/csv`, attachment): `line,msisdn,amount,reference,
  status,txnId,errorCode`, MSISDN masked (18.3), current state while running; before approval
  `409 MW-VAL-035`. No re-send of a SUBMITTED row that never settles (Kafka and the outbox keep
  the record; such a row shows SUBMITTED in the result file).
- Init (`mw-portal-api-init`, label `mw.oneshot`, `init/init.sh`, idempotent, dev only): role
  `portal_api_rw`, schema `bulk`, `secret/mw-portal-api` (DB password), AppRole.

## Contracts owned

- `api/mw-portal-api/portal-org.v1.openapi.yaml` (org portal API v1). No APISIX body schema (the
  body is CSV); the edge answers 401/413/429 itself (`deploy/apisix/README.md`).

## Contracts used

- `events/mw-orchestrator/mw.txn.request.v1` (produced, own type `dto/TxnRequest`;
  `BulkExecutionIT` validates every child against it) and `mw.txn.status.v1` (consumed, own type
  `dto/StatusMessage`; `StatusMessageContractTest` reads every valid sample).
- `api/mw-orchestrator/txn-query.v1.openapi.yaml` (client, own type `dto/OrgTxn`, unknown fields
  ignored).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field.
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
  Add context with `log.atInfo().addKeyValue("event", "...")` or the MDC.
- `config/MwServiceProperties`: config binding; secrets from OpenBao when `VAULT_ENABLED=true`.
- `messaging/EventPublisher`: Kafka records with the 6.1 headers, idempotent `acks=all` producer.
- `route/RetryTopicRouteBuilder` + `messaging/RetryDelayListener`: subclass per consumed topic; retry
  topics 10 s / 1 min / 10 min then DLQ (19.2); per-record commit; no sleeps on Kafka threads.
- `application.yml`: Resilience4j defaults (19.2), own schema `bulk` (spec 16.2) migrated by Flyway on
  start from `db/migration` (16.4).

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | unit, slice and Testcontainers tests (Docker needed for `*IT`), JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-portal-api:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-portal-api` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make up M=mw-portal-api` | run this service's `compose.yml` (platform project must be up) |
