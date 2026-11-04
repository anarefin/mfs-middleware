# mw-orchestrator

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.orchestrator`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Runs P2P, CASH_IN, CASH_OUT and TOPUP transactions as persisted sagas (spec 7, WP07 part, WP13 part, WP14).
  Consumes `mw.txn.request.v1` (`route/TxnRequestRoute`), the core results on
  `core.wallet.event.v1` (`route/CoreWalletEventRoute`) and partner adapter answers on
  `mw.partner.reply.v1` (`route/PartnerReplyRoute`), all with retry topics and DLQ. Publishes every
  state change on `mw.txn.status.v1`, core commands on `core.wallet.command.v1` and partner
  requests on `mw.partner.request.<family>.v1`, all through the transactional outbox, in the same
  transaction as the state change.
- State machine: plain Java in `service/saga` (`Status` = the 14 states, sealed `SagaEvent`,
  `SagaMachine.apply(status, event)` -> `Transition(from, to, effect)` or
  `InvalidTransitionException`). All transitions of the 7.2 table are modelled and unit-tested;
  flows (`Flow`, one per `txnType`, found by `FlowRegistry`) are sets of reusable `Step`s
  (VALIDATE, POST, HOLD, CALL_PARTNER, COMMIT, RELEASE, REVERSE, QUERY; `Step.of(effect)`).
  `P2pFlow`, `CashInFlow`, `CashOutFlow` = validate + post (parties as the channel set them:
  CASH_IN agent -> customer, CASH_OUT customer -> agent; the core enforces the wallet types). `TopupFlow` = validate + hold + callPartner (`PartnerCall` telco,
  `mw.partner.request.telco.v1`) + commit + release; QUERY is allowed for every flow that holds.
  A new flow implements `txnType()`, `needsHold()` and, with a partner leg, `partnerCall()`.
  `SagaEngine.perform` runs the step behind a transition's effect only when the flow has it;
  REVERSE (ticket 19), SCHEDULE_INQUIRY (ticket 18) and steps outside the flow are logged
  (`saga.effect.unwired`) and skipped. `SagaEngine.fire` is the single place that applies an
  event: persist state + `saga_step`, publish status + audit event, re-arm the deadline, send the
  next command. `SagaEngine.created` publishes the first state (RECEIVED) the same way.
- Hold path (TOPUP): FundsHeld stores `holdId` (`transaction.hold_id`), PARTNER_PENDING sends PAY
  (`mapper/PartnerRequestMapper`: `requestId` = name-based UUID of `<txnId>:PAY`, stable across
  resends; `partnerTxnRef` `UPAY<txnId hex>`; `deadlineAt` = now + `partner-timeout`; never the
  PIN). `service/PartnerReplyService` (inbox key `requestId:outcome`): SUCCESS -> COMMITTING
  (partner's `partnerRef` stored in `transaction.partner_ref`, shown on the status), FAILED ->
  RELEASING with the reply's canonical error (kept into FAILED; a code outside the status
  pattern becomes `MW-PTR-<PARTNER>-UNMAPPED`), UNKNOWN -> UNKNOWN (inquiry is ticket 18),
  PENDING ignored. HoldRejected -> REJECTED (`MW-CORE-*`). CommitFailed / ReleaseFailed are not
  mapped: the deadline resends the command until the core acknowledges (7.2 "retry until
  acknowledged"; the 60 s alert is not built).
- HOLDING timeout (5 s): QueryTransaction without `queryCommandId`, commandId
  `<txnId>:query:<epoch ms>` (a fresh one per attempt, so the core never answers from its command
  log), re-asked every `holding-timeout` until answered. TransactionStatus `HELD` -> RELEASING
  (holdId from the answer), anything else -> REJECTED; both carry `MW-TMO-002` (retryable). A
  FundsHeld that arrives after the transaction ended REJECTED/FAILED gets a ReleaseHold
  (`saga.hold.orphan_released`) and changes no state.
- Audit (ticket 12, spec 14.3): one `mw.audit.event.v1` per state a transaction passes through
  (`mapper/AuditMapper`, own type `dto/AuditEvent`), through the outbox in the same transaction:
  action `txn.state.changed`, resource `txn/<txnId>`, actor `system`/`mw-orchestrator`, `before`
  `{status}` (absent for RECEIVED), `after` `{status, errorCode?}`, random `auditId` (also the
  `mw-idempotency-key` header), key txnId. No parties, amounts or PIN, so nothing to mask. A
  repeated request or a refused event changes no state and is not audited.
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
  UNKNOWN, HOLDING 5 s -> QueryTransaction (stays HOLDING), PARTNER_PENDING 30 s -> UNKNOWN,
  COMMITTING / RELEASING every `retry-timeout` (5 s) -> resend CommitHold / ReleaseHold (same
  commandId). UNKNOWN, MANUAL_REVIEW and terminal states arm no timer yet (the 7.4 inquiry
  schedule is ticket 18). A late core reply after a timeout is ignored (state machine refuses it).
- `commandId` = `<txnId>:validate` / `:post` / `:hold` / `:commit` / `:release` (QueryTransaction:
  above). `expiresAt` = issuedAt + `command-ttl` (60 s).
  All wallet commands go to `core.wallet.command.v1`; nothing is sent on `core.payment.*` yet.
- Core reason -> error: `MW-CORE-<reasonCode>` (same pattern as the status contract), message
  English + Bengali for the reasons mw-core-mock emits (`mapper/CoreEventMapper`), not retryable.
  Other codes used: `MW-VAL-010` unsupported txn type, `MW-VAL-011` amount <= 0, `MW-VAL-012`
  partner missing for a flow with a partner leg, `MW-IDEM-001`, `MW-TMO-001`, `MW-TMO-002`;
  partner codes come from the adapter's reply.
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
  `mw-orchestrator`; role-id/secret-id land in a volume the service reads as a configtree. On a
  fresh stack it waits for OpenBao's `dev-init.json` and retries the database step when another
  init races it.

## Contracts owned

- `events/mw-orchestrator/mw.txn.request.v1`, `mw.txn.status.v1`, `core.command.v1` (+ per-command
  payloads), `mw.partner.request.v1`, `mw.partner.reply.v1`, `mw.partner.inbound.v1`, and
  `common/defs.v1` (see `contracts/OWNERS.yml`). Tests validate every status and command the
  service emits against them.

## Contracts used

- `events/mw-core-mock/core.event.v1.schema.json`: own type `dto/CoreEvent` (payload read as a
  tree per `eventType`). Unknown fields are ignored.
- Own partner contracts: `dto/PartnerRequest` (produced, `PartnerMessagesTest` and `TopupSagaIT`
  validate it) and `dto/PartnerReply` (consumed; the telco samples read into it).
- `events/mw-audit-dw/mw.audit.event.v1.schema.json` (produced): own type `dto/AuditEvent`;
  `SagaServiceIT` and `P2pKafkaIT` validate every audit record against it.

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
