# mw-orchestrator

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.orchestrator`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Runs P2P, CASH_IN, CASH_OUT, MERCHANT_PAY, BULK_DISBURSE (child), TOPUP, BILL_PAY and ADD_MONEY_BANK transactions, and the REVERSAL of a completed one, as persisted sagas (spec 7, WP07 part, WP13 part, WP14, WP15 part).
  Consumes `mw.txn.request.v1` (`route/TxnRequestRoute`), the core results on
  `core.wallet.event.v1` (`route/CoreWalletEventRoute`) and `core.payment.event.v1` (`route/CorePaymentEventRoute`, same handler) and partner adapter answers on
  `mw.partner.reply.v1` (`route/PartnerReplyRoute`) and partner callbacks on `mw.partner.inbound.v1`
  (`route/PartnerInboundRoute`) and operator actions on `mw.txn.action.v1` (`route/TxnActionRoute`,
  ticket 37), all with retry topics and DLQ. Publishes every
  state change on `mw.txn.status.v1`, core commands on `core.payment.command.v1` (HoldFunds, CommitHold,
  ReleaseHold) or `core.wallet.command.v1` (every other command; `service/CoreTopics`, ticket 50) and partner
  requests on `mw.partner.request.<family>.v1`, all through the transactional outbox, in the same
  transaction as the state change.
- State machine: plain Java in `service/saga` (`Status` = the 14 states, sealed `SagaEvent`,
  `SagaMachine.apply(status, event)` -> `Transition(from, to, effect)` or
  `exception/InvalidTransitionException`). All transitions of the 7.2 table are modelled and unit-tested;
  flows (`Flow`, one per `txnType`, found by `FlowRegistry`) are sets of reusable `Step`s
  (VALIDATE, POST, HOLD, CALL_PARTNER, COMMIT, RELEASE, REVERSE, QUERY, INQUIRE; `Step.of(effect)`).
  `P2pFlow`, `CashInFlow`, `CashOutFlow`, `MerchantPayFlow` (ticket 30) = validate + post (parties as the channel set them:
  CASH_IN agent -> customer, CASH_OUT customer -> agent, MERCHANT_PAY customer -> merchant; the core enforces the wallet types).
  `BulkDisburseFlow` (ticket 34, one child of an org bulk batch, sent by mw-portal-api) is validate +
  post too: debit `{ORG, <org wallet>}`, credit the customer's WALLET, **no PIN** (the org
  authorised the batch by maker-checker; `core.command.v1` ValidateTransaction/PostTransfer require
  `encryptedPin` for every txnType but BULK_DISBURSE). Only channels in
  `mw.orchestrator.bulk-channels` (default `ORG_PORTAL`) may send one, else REJECTED `MW-AUTH-021`
  (`BulkDisburseSagaIT`); the core restricts it to ORG -> CUSTOMER. The portal derives txnId and
  key per row, so a resent child is answered from the idempotency record. The batch's parent
  BULK_DISBURSE txn is not a saga here (portal-side record, `attributes.parentTxnId`). `TopupFlow` = validate + hold + callPartner (`PartnerCall` telco,
  `mw.partner.request.telco.v1`) + commit + release; `BillPayFlow` (ticket 21) is the same with
  `PartnerCall` billpay on `mw.partner.request.billpay.v1` (partner `{partnerId: <aggregator>,
  productCode: BILL, billerCode}` and credit party `BILLER_ACCOUNT` as the app gateway sets them;
  same 30 s partner deadline, TSQ on UNKNOWN and NOT_FOUND rules as TOPUP; the sync bill fetch
  never reaches the orchestrator: mw-app-gateway asks mw-adapter-billpay over HTTP).
  `AddMoneyBankFlow` (ticket 23, ADD_MONEY_BANK / C2W, WP16) is inbound money: `needsHold()` false,
  `creditsWallet()` true, steps VALIDATE + CALL_PARTNER + COMMIT, `PartnerCall` bank on
  `mw.partner.request.bank.v1` (partner `{partnerId: c2wbank, productCode: C2W}`, debit party
  `BANK_ACCOUNT`, credit party the customer's WALLET, as the app gateway sets them). Path:
  VALIDATING -`Validated(needsHold=false, callPartner=true)`-> PARTNER_PENDING (PAY = debit the
  account; no hold) -> SUCCESS (reply or, usually, the bank's CALLBACK) -> COMMITTING, whose commit
  step for this flow is CreditWallet (`<txnId>:credit`, payload txnType, debit and credit party,
  amount, no PIN; resent with the same commandId every `retry-timeout` until answered) ->
  WalletCredited COMPLETED; CreditRejected -> MANUAL_REVIEW `MW-CORE-<reason>` (the bank already
  collected the money). A refusal (`PartnerFailed(held=false)`) goes PARTNER_PENDING -> FAILED with
  the partner's error, nothing to release; UNKNOWN / no callback within 30 s -> UNKNOWN -> partner
  STATUS_QUERY: SUCCESS -> COMMITTING (credit), FAILED / NOT_FOUND -> FAILED. A late or replayed
  callback follows the ticket 22 rules (replays are dropped by the inbox, a contradicting one is
  `txn.outcome.conflict`). Deviations from the 7.2 table, documented here: VALIDATING ->
  PARTNER_PENDING and PARTNER_PENDING -> FAILED (no hold), and COMMITTING sends CreditWallet for
  inbound money (spec 6.3 lists CreditWallet for "add money, C2W"; POSTING is "no partner").
  QUERY is allowed for every flow that holds,
  INQUIRE (status inquiry of an UNKNOWN outcome) for every flow.
  A new flow implements `txnType()`, `needsHold()` and, with a partner leg, `partnerCall()`.
  `SagaEngine.perform` runs the step behind a transition's effect only when the flow has it
  (`Flow.allows(Step)`: REVERSE and INQUIRE belong to every flow, QUERY to every flow that holds);
  SCHEDULE_INQUIRY arms the first inquiry slot. A transition whose effect is a step the flow lacks
  is refused by `SagaEngine.fire` as an `InvalidTransitionException` before anything is written
  (S2: ManualCommit of P2P/CASH_IN/CASH_OUT/MERCHANT_PAY, ManualRelease of ADD_MONEY_BANK); only an unknown
  txnType is logged `saga.effect.unwired` and skipped. `SagaEngine.fire` is the single place that applies an
  event: persist state + `saga_step`, publish status + audit event, re-arm the deadline, send the
  next command. `SagaEngine.created` publishes the first state (RECEIVED) the same way.
- Hold path (TOPUP): FundsHeld stores `holdId` (`transaction.hold_id`), PARTNER_PENDING sends PAY
  (`mapper/PartnerRequestMapper`: `requestId` = name-based UUID of `<txnId>:PAY`, stable across
  resends; `partnerTxnRef` `UPAY<txnId hex>`; `deadlineAt` = now + `partner-timeout`; never the
  PIN). `service/PartnerReplyService` (inbox key `requestId:outcome`): SUCCESS -> COMMITTING
  (partner's `partnerRef` stored in `transaction.partner_ref`, shown on the status), FAILED ->
  RELEASING with the reply's canonical error (kept into FAILED; a code outside the status
  pattern becomes `MW-PTR-<PARTNER>-UNMAPPED`), UNKNOWN -> UNKNOWN (status inquiry), PENDING
  ignored. HoldRejected -> REJECTED (`MW-CORE-*`). The deadline resends CommitHold / ReleaseHold
  until the core answers (7.2 "retry until acknowledged"; still unanswered 60 s after entering the
  state: WARN `txn.commit.unacknowledged` / `txn.release.unacknowledged` + counter, once); a definite
  refusal (CommitFailed: partner paid but the hold is gone; ReleaseFailed: hold already
  committed) goes to MANUAL_REVIEW with `MW-CORE-<reason>` (`CommitRefused` / `ReleaseRefused`;
  deviation from the 7.2 table, which lists no exit for a refusal), alerted ERROR `txn.commit.failed`
  (severity SEV-1; CreditRejected too) / `txn.release.failed` + counters.
- Alerts (S4, `service/SagaAlerts`, spec 6.3/7.2/21): stable `event` log lines and Micrometer meters
  `mw.txn.commit.unacknowledged`, `mw.txn.release.unacknowledged`, `mw.txn.commit.failed`,
  `mw.txn.release.failed`, `mw.txn.reversal.retry.exhausted` (counters) and
  `mw.manual.review.oldest.age` (gauge, seconds). Daily MANUAL_REVIEW ageing check:
  `scheduler/ManualReviewAgeingJob` (ShedLock `manual-review-ageing`, cron
  `mw.orchestrator.manual-review-ageing-cron`, default 06:00 UTC) -> `service/ManualReviewAgeingService`
  -> WARN `txn.manual_review.ageing` with `count` and `oldestAgeSeconds` (age since its `updated_at`).
- Partner outcomes are read through `service/saga/Outcome` (SUCCESS, FAILED, UNKNOWN, NOT_FOUND,
  PENDING) and the core's TransactionStatus `txnStatus` through `service/saga/CoreTxnStatus`; DTOs keep
  the raw strings. `Outcome.contradicts(Status)` is the one conflict rule (late callbacks, late
  STATUS_QUERY answers, late PAY replies): SUCCESS against RELEASING/FAILED/MANUAL_REVIEW,
  FAILED/NOT_FOUND against COMMITTING/COMPLETED/REVERSING/REVERSED/MANUAL_REVIEW. A late PAY reply that
  contradicts (e.g. SUCCESS after MANUAL_REVIEW, S5) changes nothing and logs ERROR
  `txn.outcome.conflict`. `TransactionRecord.holdsFunds(FlowRegistry)` says whether a refusal releases.
- Unknown outcomes (ticket 18, spec 7.4, `service/InquiryService`, `service/saga/InquirySchedule`):
  entering UNKNOWN (partner UNKNOWN reply, PARTNER_PENDING 30 s, POSTING 5 s) publishes UNKNOWN
  (the app shows 202 PROCESSING) and arms the `UNKNOWN` timer at the first slot. Each slot sends
  one inquiry and arms the next (`saga_timer.attempt` = inquiries sent): with a partner leg a
  `STATUS_QUERY` on the partner topic (`requestId` = name UUID of `<txnId>:STATUS_QUERY:<epoch
  ms>`, fresh per inquiry; same `partnerTxnRef`; `originalPartnerRef` = stored partnerRef), else a
  core QueryTransaction (fresh commandId, as the HOLDING query). Schedule
  `mw.orchestrator.inquiry.*`: `schedule` 30s,1m,2m,5m,15m,1h (waits between inquiries),
  `repeat-every` 6h, `cut-off` 24h after entering UNKNOWN (stands in for the partner's settlement
  cut-off), `partners.<partnerId>.{schedule,repeat-every,cut-off}` overrides. The expiry at the
  cut-off asks nothing: MANUAL_REVIEW `MW-TMO-004`. Answers (any reply for an UNKNOWN txn, the
  STATUS_QUERY answer or a late PAY reply): SUCCESS -> COMMITTING (partnerRef stored); FAILED ->
  RELEASING with the reply's error; NOT_FOUND -> RELEASING `MW-TMO-003` only once PARTNER_PENDING
  entry + `partner-timeout` + `not-found-grace` (30 s) has passed (the adapter refuses an expired
  PAY unsent), else pending; PENDING / UNKNOWN -> pending (next slot). Core answers (P2P): POSTED /
  CREDITED -> COMPLETED; REJECTED -> FAILED `MW-TMO-003`; NOT_FOUND / VALIDATED -> FAILED
  `MW-TMO-003` once POSTING entry + `command-ttl` + grace has passed (the core drops expired
  commands), else pending; anything else (HELD, ...) -> MANUAL_REVIEW `MW-TMO-005`. A
  TransactionStatus for an UNKNOWN txn with a partner leg is a stale HOLDING answer: ignored. A
  STATUS_QUERY answer after the txn left UNKNOWN changes nothing; one contradicting the
  resolution is logged `txn.outcome.conflict`. Gauges `mw_txn_unknown_open` and
  `mw_manual_review_open` (`service/BacklogMetrics`, count per scrape). MANUAL_REVIEW arms no timer
  (the daily ageing check above reports it); ManualCommit / ManualRelease come from the admin API
  (below) and are refused for a flow without the step (S2).
- Operator actions (ticket 37, maker-checker approved in mw-admin-api): `mw.txn.action.v1` (contract
  owned by mw-admin-api; `route/TxnActionRoute`, retry topics + DLQ, `messaging/TxnActionListener`,
  own type `dto/TxnAction`) -> `service/OperatorActionService`, inbox key `actionId` (consumer
  `orchestrator.txn-actions`): FORCE_COMMIT = ManualCommit, FORCE_RELEASE = ManualRelease (MANUAL_REVIEW
  only), INQUIRE = `SagaEvent.ManualInquiry` (UNKNOWN or MANUAL_REVIEW, self-transition with
  SEND_INQUIRY: one status inquiry now, the schedule's timer untouched; in MANUAL_REVIEW the answer
  only lands on the timeline, `partner_call` / `core_call`, and is logged late/conflict). A forced
  commit / release / credit is its own core command round: commandId `<txnId>:commit:manual-<n>`
  (`release`, `credit` likewise; n = how many ManualCommit / ManualRelease steps the txn has had,
  `TransactionRepository.countSteps`), so the core decides it afresh instead of replaying the refusal
  it logged for `<txnId>:commit`; the core's holds make that safe (commit of a committed hold is a
  no-op). An action the state or flow does not allow, or an unknown action, changes nothing. Every
  action is audited in the same transaction: `txn.operator.action`, actor `user`/checkerId, before
  `{status}`, after `{status, action, makerId, changeRequestId[, refused: MW-VAL-023]}`, outcome
  SUCCESS / FAILURE (the state change itself is audited as usual by `SagaEngine`). An action for an
  unknown txn is logged `txn.action.unknown_txn` and dropped. Reversal is NOT an action: mw-admin-api
  sends a REVERSAL on `mw.txn.request.v1` (channel SYSTEM), handled as below.
- Partner callbacks (ticket 22, spec 11.2, `service/PartnerInboundService`, own type
  `dto/PartnerInbound`): kind CALLBACK only, families telco/billpay/bank (REMITTANCE / NPSB_CREDIT logged
  `partner.inbound.unsupported` and dropped; C2W needs none: its confirmation is a CALLBACK of the customer's own request, decision in mw-adapter-bank's CLAUDE.md). Inbox key `inboundId` (the adapter
  derives it from partner, reference and outcome, so a duplicate callback is dropped). Ownership
  first (security review): applied only when the outcome is SUCCESS/FAILED/PENDING, the flow has
  a partner leg, `partnerId` equals the transaction's partner (`SagaEngine.partnerId`), `family`
  equals the leg's family and, once a partner reference is stored, the callback's `partnerRef` is
  that reference or our own `UPAY<txnId hex>`; otherwise nothing changes, event
  `security.partner_callback_mismatch` and audit `partner.callback.rejected` outcome DENIED with
  `after.reason` UNKNOWN_OUTCOME / NO_PARTNER_LEG / PARTNER_MISMATCH / FAMILY_MISMATCH /
  PARTNER_REF_MISMATCH. The adapter sets `partnerId` from the authenticated route (the URL's
  partner, whose secret verified the signature), never from the body. The callback
  is read as a definite partner answer (operation `CALLBACK`): PARTNER_PENDING -> SUCCESS
  COMMITTING (partnerRef stored), FAILED RELEASING with the callback's `errorCode`, PENDING
  nothing; UNKNOWN -> `InquiryService.partnerAnswer` (same rules as a STATUS_QUERY answer); any
  other state (resolved by the deadline, a status inquiry or an earlier callback) -> no state
  change, recorded on `mw.audit.event.v1` (`AuditMapper.lateCallback`: actor `partner`/<partnerId>,
  `before`/`after` `{status}` unchanged plus `after.callbackOutcome`, `errorCode`): action
  `partner.callback.late` outcome SUCCESS, or `txn.outcome.conflict` outcome FAILURE (also an
  ERROR log) when it contradicts the resolution (`Outcome.contradicts`: SUCCESS for
  RELEASING/FAILED/MANUAL_REVIEW, FAILED for COMMITTING/COMPLETED/REVERSING/REVERSED/MANUAL_REVIEW). A terminal state is never left on a callback: the
  conflict goes to operations and reconciliation (spec 15 STATUS_MISMATCH -> maker-checker
  proposal), not to MANUAL_REVIEW, which the 7.2 table only enters from non-terminal states.
  An unknown txnId is logged `txn.unknown` and dropped.
- HOLDING timeout (5 s): QueryTransaction without `queryCommandId`, commandId
  `<txnId>:query:<epoch ms>` (a fresh one per attempt, so the core never answers from its command
  log), re-asked every `holding-timeout` until answered. TransactionStatus `HELD` -> RELEASING
  (holdId from the answer), anything else -> REJECTED; both carry `MW-TMO-002` (retryable). A
  FundsHeld that arrives after the transaction ended REJECTED/FAILED gets a ReleaseHold
  (`saga.hold.orphan_released`) and changes no state.
- Reversal (ticket 19, spec 7.2): a `mw.txn.request.v1` request with `txnType=REVERSAL` and
  `originalTxnId` does not become a transaction of its own; it moves the ORIGINAL (row-locked)
  from COMPLETED to REVERSING and stores the request's txnId in `transaction.reversal_txn_id`
  (unique). ReverseTransaction goes under that txnId (`commandId` `<reversalTxnId>:reverse`,
  payload `originalTxnId`, the original's amount and fee), because mw-core-mock books the
  compensating entries under the reversal's own txnId; its answer is found through
  `reversal_txn_id`. TransactionReversed -> REVERSED; ReversalRejected -> MANUAL_REVIEW
  (`MW-CORE-<reason>`). No answer (S1, `SagaTimerService.reversingDeadline`, measured from entering
  REVERSING): resent every `retry-timeout` until `reversing-timeout` (60 s); then resends stop
  (`ReversalResendsStopped`, WARN `txn.reversal.retry_exhausted`) and the transaction waits until the
  last ReverseTransaction has expired (`+ command-ttl`; the core drops an expired command); then
  QueryTransaction under the original txnId, commandId `<txnId>:reverse-query:<epoch ms>`
  (`ReversalUnconfirmed`, asked again every `retry-timeout` for one more `command-ttl`): `REVERSED`
  -> REVERSED, any other txnStatus -> MANUAL_REVIEW `MW-TMO-020` (`ReversalQueryResult`); still no
  answer -> MANUAL_REVIEW `MW-TMO-020` (`ReversalFailed`). MANUAL_REVIEW accepts a TransactionReversed
  (or a REVERSED query answer) and moves to REVERSED: only a transaction handed over from REVERSING
  has a reversal the core can confirm, and the core's truth wins (deviation from the 7.2 table).
  Every status of the original from then on carries `attributes.reversalTxnId`. Only channels
  in `mw.orchestrator.reversal-channels` (default `SYSTEM`: operations, i.e. mw-admin-api after
  maker-checker, ticket 37) may reverse. Refused with a REJECTED status under the request's txnId
  (no row, key not consumed): channel `MW-AUTH-020`, unknown original `MW-VAL-020`, original not
  COMPLETED `MW-VAL-021`, amount other than the original's `MW-VAL-022` (no partial reversal).
  Each transaction is reversed once: a further REVERSAL of a transaction already
  REVERSING/REVERSED/MANUAL_REVIEW (after a reversal), under any key, gets the original's current
  status as a duplicate answer (`attributes.duplicate`, `requestTxnId`) and changes nothing; the
  idempotency key is stored with the ORIGINAL's txnId. Reversal is core-only: no partner REVERSE
  is sent (a reversed TOPUP gives the money back, not the airtime).
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
  row. Valkey 60 s double-tap lock (7.3 rule 4, ticket 50, `service/IdempotencyGate` +
  `client/ValkeyIdempotencyLock`, `config/ValkeyConfig`): before the claim, `SET NX PX 60000` on
  `mw:orchestrator:idem:<sha256(channel, initiator id, key)>` (no MSISDN in Valkey) with value
  `<txnId> <request hash hex>`. Held by another txnId: same hash -> duplicate answer of the holder
  without a claim attempt (its current status, or RECEIVED + `attributes.duplicate` while the
  holder's row is not committed yet, `StatusMapper.toInFlightDuplicateStatus`), other hash ->
  `MW-IDEM-001`. Held by the same txnId (a redelivered record), an unreadable value, or Valkey
  unreachable/slow (200 ms timeouts, commands rejected while disconnected) -> on to the claim;
  the primary key stays the final guarantee. Valkey trouble never blocks or fails a request:
  WARN `txn.idempotency.lock_unavailable` + counter `mw.txn.idempotency.lock.unavailable`;
  Valkey is not in the health groups. The lock is never released early. REVERSAL requests take
  no lock (the original's row lock serialises them). Settings `mw.orchestrator.idempotency-lock.{enabled,ttl}`
  (`MW_IDEMPOTENCY_LOCK_ENABLED`; off in the test config, `IdempotencyLockIntakeIT` plugs in an
  in-memory lock), `spring.data.redis.*` (`VALKEY_HOST`; password `spring.data.redis.password` in
  `secret/mw-orchestrator`, synced by the init from `VALKEY_PASSWORD`).
  Deviations from 7.3 rule 2 (S7, recorded, not restructured): the final response is not stored,
  a repeat is answered from the live transaction row (current status of the stored txnId), so the
  never-written `idempotency.response` column was dropped (V7); `txn.idempotency` is not
  partitioned, so there is no purge by partition drop; an expired key (`expires_at` passed) is
  reclaimed in place by the next request with it, and no job deletes expired rows yet
  (`RetentionService` purges only outbox and inbox).
- Timers: `txn.saga_timer` (one row per txn and state), swept by `scheduler/SagaTimerJob` every
  second under ShedLock; `SagaTimerService.sweep` uses `FOR UPDATE SKIP LOCKED`. Deadlines are
  `mw.orchestrator.*-timeout`: VALIDATING 5 s -> FAILED (`MW-TMO-001`, retryable), POSTING 5 s ->
  UNKNOWN, HOLDING 5 s -> QueryTransaction (stays HOLDING), PARTNER_PENDING 30 s -> UNKNOWN,
  COMMITTING / RELEASING / REVERSING every `retry-timeout` (5 s) -> resend CommitHold /
  ReleaseHold / ReverseTransaction (same commandId; REVERSING stops resending after
  `reversing-timeout` and asks the core, above),
  UNKNOWN on the inquiry schedule (above). MANUAL_REVIEW and terminal states arm no timer. A late
  core reply after a timeout is ignored (state machine refuses it).
- `commandId` = `<txnId>:validate` / `:post` / `:hold` / `:commit` / `:release` / `:credit`
  (QueryTransaction: above; ReverseTransaction: `<reversalTxnId>:reverse`). `expiresAt` = issuedAt + `command-ttl` (60 s).
  Topics (ticket 50, M2 gate): `HoldFunds`, `CommitHold` and `ReleaseHold` (also the orphan-hold release) go to
  `core.payment.command.v1`, their results come back on `core.payment.event.v1`; every other command
  (Validate, Post, CreditWallet, Reverse, Query) goes to `core.wallet.command.v1` (`service/CoreTopics`,
  `CoreTopicsTest`; the old `wallet-command-topic` property is gone). Both event topics feed the same
  `CoreEventListener`. ITs read the outbox of both command topics (`coreCommands`); `messaging/CoreStub`
  answers on the event topic paired with the command topic.
- Core reason -> error: `MW-CORE-<reasonCode>` (same pattern as the status contract), message
  English + Bengali for the reasons mw-core-mock emits (`mapper/CoreEventMapper`), not retryable.
  Other codes used: `MW-VAL-010` unsupported txn type, `MW-VAL-011` amount <= 0, `MW-VAL-012`
  partner missing for a flow with a partner leg, `MW-IDEM-001`, `MW-TMO-001`, `MW-TMO-002`,
  `MW-TMO-003` (not found by inquiry, retryable), `MW-TMO-004` (inquiry exhausted),
  `MW-TMO-005` (conflicting inquiry answer), reversal `MW-VAL-020..022`, operator action not allowed `MW-VAL-023`, `MW-AUTH-020`, `MW-TMO-020`, bulk channel `MW-AUTH-021`;
  partner codes come from the adapter's reply.
- PIN: only ciphertext. It is kept in `transaction.encrypted_pin` (jsonb) because PostTransfer
  follows ValidateTransaction, and cleared (`TransactionRecord.moved`, the one write path) on every
  transition into COMPLETED, REJECTED, FAILED, REVERSED or MANUAL_REVIEW (ticket 50, spec 18.3;
  `entity/TransactionRecordPinTest` walks every state-machine path). The stored
  request never contains it; it is never logged (`@Sensitive`).
- `mw-reply-to` (7.5): copied from the request record to every status record (and to the answer
  for a repeated request, taken from the repeated request). Stored in `transaction.reply_to`.
- Schema `txn` (role `orchestrator_rw`): `transaction`, `saga_step`, `saga_timer`, `idempotency`
  (+ `outbox`, `inbox`, `shedlock`, `partner_call`, `core_call`); migrations V1..V10 (V6 `reversal_txn_id`, V7 drops
  `idempotency.response`, V8 `initiator_hash` + `partner_call`, ticket 35; V9 `initiator_type`,
  `payee_hash`, `timeline_seq` + `saga_step.seq`, `core_call`; V10 = Java migration
  `repository/SearchHashMigration`, a Spring bean Flyway picks up, re-hashing every older row as
  `HMAC(<type>:<id>)` from its raw `initiator_id` and stored request, `SearchHashMigrationIT`). Repository ITs: `repository/TransactionRepositoryIT` (round trip, row
  lock, unique `reversal_txn_id`, step history, backlog queries) and
  `IdempotencyAndTimerRepositoryIT` (claim, scope, expired key, timers incl. SKIP LOCKED). `transaction` is not partitioned yet and keeps parties in
  the stored request JSON instead of separate columns.
- Internal query API (ticket 35, spec 15.2/16.2, contract `api/mw-orchestrator/txn-query.v1.openapi.yaml`;
  `controller/TransactionQueryController` -> `service/TransactionQueryService`): `GET
  /internal/v1/transactions` (filters txnId, msisdn, orgId, partnerRef, status, txnType, parentTxnId,
  `[from, to)` on createdAt, limit 1..200, offset; newest first) and `GET /internal/v1/transactions/{txnId}`
  (`?orgId=` scopes it: a txn of neither role is 404 MW-VAL-003) with `timeline` (B13: every
  `saga_step` as `kind` STATE {from, to, trigger, at} and every core command as `kind` CORE
  {command, commandId, at = first send, sends, result = core event type, outcome, reasonCode,
  resultAt}, interleaved in the order they happened by the shared `timeline_seq`; `txn.core_call`
  is written by `SagaEngine.send` (a resend under the same commandId counts in `sends`; a
  ReverseTransaction is filed under the original's txnId) and answered by `CoreEventService` before
  the state machine runs, so an ignored late result still shows; first result kept) and
  `partnerCalls` (`txn.partner_call`, V8: one row per PAY / STATUS_QUERY sent by
  `SagaEngine.sendToPartner`, answered by `PartnerReplyService` (outcome, partnerRef, errorCode,
  `repliedAt`), plus one CALLBACK row per callback that passed the ownership checks). Clients are
  mw-admin-api and mw-portal-api over the `app` network; **no APISIX route**. Auth (M3 gate decision,
  `config/SecurityConfig`, `security/InternalCallerPolicy`, `config/InternalAuthProperties`
  `mw.internal-auth.*`): a Keycloak client-credentials JWT of realm `services` (JWKS
  `MW_INTERNAL_JWK_SET_URI`, exact issuer `MW_INTERNAL_ISSUER`, default `http://keycloak:8080/realms/services`,
  `aud` must contain `mw-orchestrator`); caller `azp` in `allowed-clients` (mw-portal-api, mw-admin-api),
  and `org-scoped-clients` (mw-portal-api) must send a non-blank `orgId` on every call. Reveal
  (ticket 36, contract 1.2.0): `GET .../{txnId}?reveal=true` from a `reveal-clients` caller
  (mw-admin-api only; anyone else sending `reveal` is 403) returns MSISDN-shaped party ids whole
  (`TransactionViewMapper.masked(p, true)`: only an id `maskPartyId` masks as an MSISDN; accounts,
  names and error text stay masked). mw-admin-api grants it to compliance-officer/auditor only and
  audits each reveal; the orchestrator does not audit it itself. No / bad token
  `401 MW-AUTH-001`, other client or portal without orgId `403 MW-AUTH-002` (WARN
  `security.internal_call_denied`); actuator stays open. `InternalApiAuthIT` (test JWKS
  `security/InternalTokens`, registered by `TestInfrastructure`). MSISDN and org id are
  matched through `transaction.initiator_hash` (V8; `util/SearchHash` = HMAC-SHA256 of
  `<type>:<canonical id>` (A2), MSISDN in any form -> `01XNNNNNNNN`; key
  `mw.orchestrator.search.hash-key`, >= 32 chars, in `secret/mw-orchestrator`, created once by the
  init; `config/SearchProperties`), so `initiator_id` is never indexed, and the initiator type
  (`initiator_type`, V9) is checked as well: `msisdn` matches initiator types MSISDN/WALLET only,
  `orgId` type ORG only, so an org id never matches an MSISDN. Org scope (A3, `util/SearchKeys`):
  `orgId` also returns the transactions the org is the **payee** of, matched through
  `transaction.payee_hash` (V9, indexed like the initiator hash; `BILLER:<billerCode>` of a BILL_PAY,
  `MERCHANT:<wallet>` of a credit to a MERCHANT wallet, else null) against the org's payees in
  `mw.orchestrator.search.org-payees` (`<orgId>: [BILLER:<code>, MERCHANT:<msisdn>]`, checked at
  start; none in the main config, the dev ones (ORG002: DESCO and 01911000001) in `compose.yml` as
  `SPRING_APPLICATION_JSON`, test ones in the test config). Each org-scoped item carries `role`
  INITIATOR (wins) or PAYEE; unscoped results carry none. Rows from before V8 had no hash and V8
  rows an untyped one: V10 re-hashes them all, so none is left behind. Views
  (`mapper/TransactionViewMapper`): parties masked by type, names to the initial, error text and the
  SP `reference` scrubbed, only the bulk attributes (`parentTxnId`, `batchId`, `lineNo`, `reference`);
  no PIN, idempotency key or raw request. `TransactionQueryApiIT` (TSQ'd top-up timeline with core
  commands, resend count and failure reason, masking, typed org scope, payee roles).
- Init (`mw-orchestrator-init`, label `mw.oneshot`, `init/init.sh`, idempotent, dev only): creates
  role and schema, `secret/mw-orchestrator` (DB password, never overwritten; search hash key, created once) and the AppRole
  `mw-orchestrator`; role-id/secret-id land in a volume the service reads as a configtree. On a
  fresh stack it waits for OpenBao's `dev-init.json` and retries the database step when another
  init races it.

## Contracts owned

- `events/mw-orchestrator/mw.txn.request.v1`, `mw.txn.status.v1`, `core.command.v1` (+ per-command
  payloads), `mw.partner.request.v1`, `mw.partner.reply.v1`, `mw.partner.inbound.v1`,
  `api/mw-orchestrator/txn-query.v1.openapi.yaml` (internal query API, ticket 35), and
  `common/defs.v1` (see `contracts/OWNERS.yml`). Tests validate every status and command the
  service emits against them. `core.command.v1#UnlockWallet` (ticket 52) is in this contract but
  produced by mw-admin-api (ops unlock after maker-checker), not by the orchestrator; its results
  `WalletUnlocked` / `UnlockRejected` carry a txnId the orchestrator does not know and are dropped
  (`txn.unknown`).

## Contracts used

- `contracts.yml` (module root) is the machine-readable list of what this service produces and
  consumes (each `core.command.v1` command type, `core.event.v1` event type and partner family
  separately), checked by `make contracts-lint` and tested without Docker by
  `contracts/ProducedContractsTest` (every produced event built by the real mapper, serialised as
  the outbox does, validated), `contracts/ConsumedContractsTest` (every valid sample of a consumed
  event read into the own DTO) and `contracts/ApiContractsTest` (txn-query response bodies against
  the OpenAPI component schemas). A new event type or family goes into `contracts.yml` and gets a
  fixture there.
- `dto/ConfigChangedEvent` (`mw.config.changed.v1`) exists with its schema tests, but no route
  consumes the topic yet, so it is not in `contracts.yml`.
- `events/mw-core-mock/core.event.v1.schema.json`: own type `dto/CoreEvent` (payload read as a
  tree per `eventType`). Unknown fields are ignored.
- `events/mw-admin-api/mw.txn.action.v1.schema.json` (consumed, ticket 37): own type `dto/TxnAction`;
  `ConsumedContractsTest` reads the valid samples, `service/OperatorActionIT` covers the handling.
  The REVERSAL mw-admin-api sends on `mw.txn.request.v1` names only `ORG`/`upay-ops` as parties and
  the change request id as idempotency key.
- `mw.partner.inbound.v1` (owned, consumed): own type `dto/PartnerInbound`;
  `PartnerMessagesTest` reads every valid sample, `PartnerCallbackSagaIT` validates the callbacks
  it feeds in.
- Own partner contracts: `dto/PartnerRequest` (produced, `PartnerMessagesTest`, `TopupSagaIT` and
  `InquirySagaIT` validate it) and `dto/PartnerReply` (consumed; the telco samples read into it).
  Reply `outcome` `NOT_FOUND` (ticket 18) answers a STATUS_QUERY only.
- `events/mw-audit-dw/mw.audit.event.v1.schema.json` (produced): own type `dto/AuditEvent`;
  `SagaServiceIT` and `P2pKafkaIT` validate every audit record against it.

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field. Party ids are
  `PARTY_ID` (ticket 23): masked by the party's `type` (wallet/MSISDN as an MSISDN, a bank account or any
  other type last 4 only).
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
| `./gradlew test` | unit, slice and contract tests, no Docker (leaves out `*IT` and `@Conformance`) |
| `./gradlew integrationTest` | Testcontainers `*IT` classes (Docker needed), not `@Conformance` |
| `./gradlew conformance` | the `@Conformance` suite (Docker needed) |
| `./gradlew check` | `test` + `integrationTest` + `conformance`, JaCoCo gate over all three (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-orchestrator:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-orchestrator` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make verify-changed [BASE=main]` | the gate for the modules changed since BASE (this one when it or a contract in its `contracts.yml` changed) |
| `make up M=mw-orchestrator` | run this service's `compose.yml` (platform project must be up) |
