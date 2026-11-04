# mw-orchestrator

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.orchestrator`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Runs a P2P transaction as a persisted saga (spec 7, WP07 part). Consumes `mw.txn.request.v1`
  (`route/TxnRequestRoute`) and the core results on `core.wallet.event.v1`
  (`route/CoreWalletEventRoute`), both with retry topics and DLQ. Publishes every state change on
  `mw.txn.status.v1` and sends `ValidateTransaction` / `PostTransfer` on `core.wallet.command.v1`,
  all through the transactional outbox, in the same transaction as the state change.
- State machine: plain Java in `service/saga` (`Status` = the 14 states, sealed `SagaEvent`,
  `SagaMachine.apply(status, event)` -> `Transition(from, to, effect)` or
  `InvalidTransitionException`). All transitions of the 7.2 table are modelled and unit-tested;
  only the P2P path is wired (`P2pFlow`, `FlowRegistry` by `txnType`). Effects of unwired flows
  (HoldFunds, partner request, commit, release, reverse, QueryTransaction) are logged
  (`saga.effect.unwired`) and skipped. `SagaEngine.fire` is the single place that applies an
  event: persist state + `saga_step`, publish status, re-arm the deadline, send the next command.
  Add the audit event (ticket 12) there.
- Intake (`service/TransactionService`): idempotency in `txn.idempotency`, scope (channel,
  initiator id, key), TTL 72 h (`mw.orchestrator.idempotency-ttl`). The request hash is SHA-256
  over the business content (channel, type, parties, amount, partner, originalTxnId,
  attributes), not txnId, correlationId, requestedAt or the PIN ciphertext. Same key + same hash:
  a status record for the STORED txnId with the current status and `attributes.requestTxnId` =
  txnId of the repeated request (so a channel waiting on its own txnId can match it); same key
  + other hash: REJECTED status under the new request's txnId with `MW-IDEM-001`, no transaction
  row. The Valkey 60 s double-tap lock (7.3 rule 4) is not implemented: PostgreSQL is the source
  of truth and the primary key serialises concurrent first inserts.
- Timers: `txn.saga_timer` (one row per txn and state), swept by `scheduler/SagaTimerJob` every
  second under ShedLock; `SagaTimerService.sweep` uses `FOR UPDATE SKIP LOCKED`. Deadlines are
  `mw.orchestrator.*-timeout`: VALIDATING 5 s -> FAILED (`MW-TMO-001`, retryable), POSTING 5 s ->
  UNKNOWN. UNKNOWN, MANUAL_REVIEW and terminal states arm no timer yet (the 7.4 inquiry schedule
  is ticket 18). A late core reply after a timeout is ignored (state machine refuses it).
- `commandId` = `<txnId>:validate` / `<txnId>:post`. `expiresAt` = issuedAt + `command-ttl` (60 s).
  All wallet commands go to `core.wallet.command.v1`; nothing is sent on `core.payment.*` yet.
- Core reason -> error: `MW-CORE-<reasonCode>` (same pattern as the status contract), message
  English + Bengali for the reasons mw-core-mock emits (`mapper/CoreEventMapper`), not retryable.
  Other codes used: `MW-VAL-010` unsupported txn type, `MW-VAL-011` amount <= 0, `MW-IDEM-001`,
  `MW-TMO-001`.
- PIN: only ciphertext. It is kept in `transaction.encrypted_pin` (jsonb) because PostTransfer
  follows ValidateTransaction, and cleared when the transaction becomes terminal. The stored
  request never contains it; it is never logged (`@Sensitive`).
- `mw-reply-to` (7.5): copied from the request record to every status record (and to the answer
  for a repeated request, taken from the repeated request). Stored in `transaction.reply_to`.
- Schema `txn` (role `orchestrator_rw`): `transaction`, `saga_step`, `saga_timer`, `idempotency`
  (+ `outbox`, `inbox`, `shedlock`). `transaction` is not partitioned yet and keeps parties in
  the stored request JSON instead of separate columns.
- Init (`mw-orchestrator-init`, label `mw.oneshot`, `init/init.sh`, idempotent, dev only): creates
  role and schema, `secret/mw-orchestrator` (DB password, never overwritten) and the AppRole
  `mw-orchestrator`; role-id/secret-id land in a volume the service reads as a configtree.

## Contracts owned

- `events/mw-orchestrator/mw.txn.request.v1`, `mw.txn.status.v1`, `core.command.v1` (+ per-command
  payloads), `mw.partner.request.v1`, `mw.partner.reply.v1`, `mw.partner.inbound.v1`, and
  `common/defs.v1` (see `contracts/OWNERS.yml`). Tests validate every status and command the
  service emits against them.

## Contracts used

- `events/mw-core-mock/core.event.v1.schema.json`: own type `dto/CoreEvent` (payload read as a
  tree per `eventType`). Unknown fields are ignored.

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
- `application.yml`: Resilience4j defaults (19.2), own schema `txn` migrated by Flyway on
  start from `db/migration` (16.4).

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | unit, slice and Testcontainers tests (Docker needed for `*IT`), JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-orchestrator:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-orchestrator` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make up M=mw-orchestrator` | run this service's `compose.yml` (platform project must be up) |
