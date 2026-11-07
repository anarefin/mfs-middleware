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
  `KC_MW_PORTAL_API_SECRET` of `scripts/gen-dev-env.sh`). No token -> `503 MW-SYS-040`.
  `OrchestratorClientAuthTest`. Resilience4j `orchestrator` (retry + circuit breaker on
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
  Another org's batch is `404 MW-VAL-003` everywhere. List filter `?status=` is a String mapped in
  the service (`BatchStatus.parse`; unknown `400 MW-VAL-001`); the controller binds no entity type.
- Expiry (M3 gate decision; `service/BatchExpiryService`, `scheduler/BulkExpiryJob`, ShedLock
  `bulk-expiry`, every `bulk.expiry.period` 5 min, `MW_BULK_EXPIRY_ENABLED`): a batch still
  `PENDING_APPROVAL` `bulk.expiry.after` (72 h) after its upload becomes `EXPIRED` (`decided_at` =
  expiry time, one conditional UPDATE `FOR UPDATE SKIP LOCKED`, at most `batch-size` per run); then
  approve/reject `409 MW-VAL-032`, result `409 MW-VAL-035`.
- Audit (M3 gate decision; `service/BatchAudit` -> `mapper/BatchAuditMapper`, own type `dto/AuditEvent`):
  upload, approve, reject, expire, pause and resume each write one `mw.audit.event.v1` record to the
  outbox in the action's transaction (a refused action writes none): action `bulk.batch.<uploaded|
  approved|rejected|expired|paused|resumed>`, resource `bulk-batch/<batchId>`, actor `user`/<operator's
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
- Views of approved batches carry `parentTxnId`, `progress` (pending, inFlight, completed, failed, manualReview, unresolved, paidAmount), `completedAt`. `GET .../result`
  (`mapper/ResultFile`, maker or checker, `text/csv`, attachment): `line,msisdn,amount,reference,
  status,txnId,errorCode,batchStatus` (`batchStatus` = the batch's status on every line, e.g.
  COMPLETED_WITH_EXCEPTIONS), MSISDN masked (18.3), current state while running; before approval
  `409 MW-VAL-035`. No re-send of a SUBMITTED row that never settles (the reconcile job above settles
  or gives it up).
- Dev provisioning (`provision_mw_portal_api` in `deploy/provision/services.sh`, run by the one-shot `provision` on every `make up`, idempotent, dev only): role
  `portal_api_rw`, schema `bulk`, `secret/mw-portal-api` (DB password; realm `services` client
  secret from `KC_MW_PORTAL_API_SECRET`), AppRole.

## Contracts owned

- `api/mw-portal-api/portal-org.v1.openapi.yaml` (org portal API v1). APISIX checks no body (review
  A6); the edge answers 401/413/429 itself (`deploy/apisix/README.md`). The only JSON body, reject
  `{reason}`, is checked here before binding against
  `src/main/resources/derived-schemas/portal-org-v1-reject-request.json` (derived from the contract's
  inline reject body schema; `scripts/derived-schemas.sh --write` after changing it, `make
  contracts-lint` fails on drift) by `filter/BodySchemaAdvice` -> `service/BodySchema` (copied from
  mw-app-gateway: duplicate keys, trailing content, non-objects refused; `400 MW-VAL-001`; over 64 KiB
  `413 MW-VAL-BODY_TOO_LARGE`); then Bean Validation. approve/pause/resume take no body. The CSV
  upload is not JSON: `BulkCsvParser` and its caps (above) check it. A new JSON body type needs its
  derived schema added to the advice.

## Contracts used

- `contracts.yml`: the machine-readable list of what this module produces and consumes (checked
  by `make contracts-lint`, tested by `contracts/ProducedContractsTest` / `ConsumedContractsTest`,
  and `contracts/ApiContractsTest` for the served and called APIs; no Docker).
- `events/mw-audit-dw/mw.audit.event.v1` (produced, own type `dto/AuditEvent`; `BulkLifecycleIT`
  validates every batch audit event against it).

- `events/mw-orchestrator/mw.txn.request.v1` (produced, own type `dto/TxnRequest`;
  `BulkExecutionIT` validates every child against it) and `mw.txn.status.v1` (consumed, own type
  `dto/StatusMessage`; `StatusMessageContractTest` reads every valid sample).
- `api/mw-orchestrator/txn-query.v1.openapi.yaml` (client, own type `dto/OrgTxn`, unknown fields
  ignored).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (`security-*` in
  `gradle/libs.versions.toml`; drop once the Boot / Camel BOMs ship them); the Dockerfile runs
  `apt-get upgrade` in the runtime stage; the shared dev provisioning image `deploy/provision/Dockerfile` runs `apk upgrade` and removes gosu. `make security M=<this module>` scans the SBOM and the
  `mfs-mw/*:local` images (HIGH/CRITICAL fail; accepted ones in `.trivyignore.yaml` with a reason and expiry).
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
| `./gradlew test` | unit, slice and contract tests (`contracts/`), no Docker |
| `./gradlew integrationTest` | Testcontainers `*IT` classes (Docker needed) |
| `./gradlew conformance` | the `@Conformance` suite (Docker needed) |
| `./gradlew check` | `test` + `integrationTest` + `conformance`, JaCoCo gate (80% lines on `service`/`mapper`/`client`, over all three) |
| `./gradlew check bootJar && docker build -t mw-portal-api:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-portal-api` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make verify-changed [BASE=main]` | the gate for the modules changed since `BASE` (this one when it or a contract in its `contracts.yml` changed) |
| `make build M=mw-portal-api` | `bootJar`, then this service's images (`mfs-mw/mw-portal-api:local`, plus the shared `mfs-mw/provision:local`) |
| `make up M=mw-portal-api` | start this service and what it depends on from `deploy/docker-compose.yml` (`TIMERS=fast` for short test timers) |
