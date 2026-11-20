# mw-orchestrator

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.orchestrator`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Runs P2P, CASH_IN, CASH_OUT, MERCHANT_PAY, BULK_DISBURSE (child), TOPUP, BILL_PAY and ADD_MONEY_BANK transactions, the BALANCE check (ADR D26) and the REVERSAL of a completed one, as persisted sagas (spec 7, WP07 part, WP13 part, WP14, WP15 part).
  Consumes `mw.txn.request.v1` (`messaging/TxnRequestListener`), the core's events on
  `core.wallet.event.v1` and `core.payment.event.v1` (one listener, `messaging/CoreEventListener`; since ticket 95a it
  ignores the result events of the REST calls, and since ticket 95c it applies HoldExpired and consumes
  wallet-state events, see "Core over REST") and partner adapter answers on
  `mw.partner.reply.v1` (`messaging/PartnerReplyListener`) and partner callbacks on `mw.partner.inbound.v1`
  (`messaging/PartnerInboundListener`) and operator actions on `mw.txn.action.v1` (`messaging/TxnActionListener`,
  ticket 37): Spring Kafka `@KafkaListener`s (ADR D27), all with retry topics and DLQ, all stopped by
  `mw.orchestrator.consumers.enabled=false` (the test default). Publishes every
  state change on `mw.txn.status.v1` and partner requests on `mw.partner.request.<family>.v1`, through the
  transactional outbox in the same transaction as the state change, and calls the core over REST once that
  transaction has committed (ADR D31, ticket 95a: "Core over REST" below; no core command topic any more).
- Routing (ticket 98, FRS PLT-032, PLT-040..043): `service/routing` chooses the connector before any money
  moves. The most specific enabled rule wins (product, biller, bank, operator, channel, and an amount
  range each count; a higher priority breaks a tie). A top-up with no `partnerId` takes the operator from
  the credit MSISDN: the longest enabled prefix, unless an MNP override names that number. Prefixes shipped
  in V14 are 017/013 gp, 018 robi, 015 teletalk, 019/014 banglalink. A `partnerId` the channel did send is
  used only when the winning rule allows it (it is the connector, a fallback, or the operator, biller or
  bank the rule is about); otherwise the request is `MW-PTR-UNKNOWN`. A connector reported DOWN,
  MAINTENANCE, DISABLED or DELETED on `mw.partner.route.status.v1`, or covered by an active maintenance
  message, is skipped. The next connector is tried only after a definite pre-send refusal (open circuit,
  busy, unavailable, disabled, misconfigured, maintenance, unknown partner). An ambiguous PAY timeout stays
  UNKNOWN and is never failed over. No connector left, with a maintenance message, is `MW-SYS-PARTNER_DISABLED`
  (bn and en); with none, `MW-SYS-PARTNER_CIRCUIT_OPEN`. The snapshot reloads from the tables every 30 seconds
  (`mw.orchestrator.routing.reload-interval`) and on `mw.config.changed.v1`. Health is remembered across a reload.
  Approved changes are maker-checker resources of mw-admin-api (`ROUTING_RULE`, `MSISDN_PREFIX`, `MNP_OVERRIDE`,
  `MAINTENANCE_MESSAGE`).
- Core over REST (ADR D31, ticket 95a; FRS EXT-18, WAL-13, PLT-021/023/030/033; contract
  `api/mw-core-mock/core-wallet.v1.openapi.yaml`, consumed): `SagaEngine.send` records the call on `core_call`
  and hands the `CoreCommand` (now an internal step description, no longer a Kafka message) to
  `service/CoreCallDispatcher`, which submits it **after commit** to `service/CoreCallExecutor` (bean in
  `config/CoreClientConfig`: one virtual thread per call, named `core-call-<n>`; never a Kafka listener, timer or
  request thread). `client/CoreWalletClient` makes the call: ValidateTransaction `POST /core/v1/transactions/validate`,
  PostTransfer `POST /transfers`, HoldFunds `POST /holds` (mw-auth's assertion in `X-Txn-Assertion`, the request's
  `idempotencyKey` in the body; none for a bulk child), CommitHold / ReleaseHold `POST /holds/{holdId}/commit|release`,
  CreditWallet `POST /credits` (an operator round `:manual-<n>` sends `round` n, so the core decides it afresh),
  ReverseTransaction `POST /reversals` (the reversal's txnId, no amount: the whole original; a merchant refund sends its amount, ticket 116b), every QueryTransaction
  (HOLDING query, inquiry, reversal query, credit check) `GET /transactions/{txnId}` (WAL-13), BalanceInquiry
  `GET /balance/{walletId}?txnId=` with the assertion and `Idempotency-Key`. Service JWT: `client/ServiceTokenClient`,
  Keycloak realm `services` client `mw-orchestrator` (audience mapper `aud-mw-core-mock`, secret
  `mw.orchestrator.core.client-secret` from Vault, `KC_MW_ORCHESTRATOR_SECRET` in dev); a 401 `MW-AUTH-001` renews it
  once. Plain HTTP (mw-core-mock serves no mTLS). Config `mw.orchestrator.core` (`OrchestratorProperties.Core`:
  `base-url` `MW_CORE_BASE_URL`, `token-uri`, `connect-timeout` 1 s, `read-timeout` 1500 ms `MW_CORE_READ_TIMEOUT`, below
  every saga deadline); Resilience4j circuit breaker `core`, bulkhead `core` (100), retry `core-read` for the reads
  only: a money call is never retried by the client (idempotent by txnId at the core, PLT-031; the step's deadline
  resends it). `dto/CoreReply` classifies the answer: 2xx ANSWERED, 422 `MW-CORE-*` / 401 `MW-AUTH-046` / other 4xx
  REFUSED, 5xx / 408 / 429 / timeout / connection / breaker / bulkhead / 403 / 409 / refused token AMBIGUOUS.
  `mapper/CoreReplyMapper` names it with the core's result name (TransactionValidated, FundsHeld, CommitFailed, ...,
  recorded on `core_call.result`) and maps it to the step's `SagaEvent`; `service/CoreReplyService` applies it in its
  own transaction (REQUIRES_NEW, row lock; reversal query, credit check and inquiry answers as before; a late
  FundsHeld after REJECTED releases the orphan hold). **An ambiguous answer applies nothing**: the step's persisted
  deadline decides exactly as for a lost answer (VALIDATING -> FAILED, HOLDING -> WAL-13 query, POSTING -> UNKNOWN ->
  WAL-13 inquiries, COMMITTING / RELEASING / REVERSING resend with the same txnId), so an ambiguous answer
  adds no state, and a crash between commit and call loses nothing. `service/CoreEventService` ignores the result events the REST
  operations also publish (FundsHeld, HoldCommitted, TransferPosted, ...), so nothing is applied twice
  (`SagaServiceIT`). Hold expiry and wallet-state events are the exception, below. Tests:
  `client/CoreWalletClientTest` (endpoints, headers, classification, retries, token renewal, request bodies against
  the core contract), `mapper/CoreReplyMapperTest` (2xx, 422, 401 assertion, 5xx / timeout -> no event),
  `messaging/CoreRestFlowIT` (P2P and TOPUP over `messaging/CoreRestStub`; a lost PostTransfer answer -> UNKNOWN ->
  WAL-13 -> COMPLETED with one posting; every call on a `core-call-` virtual thread). Service-seam ITs import
  the test config `CoreCalls` (records each call in line, answers none; `answer(CoreEvent)` feeds the step's
  answer written in the core's result names through `CoreReplyService`).
- State machine: plain Java in `service/saga` (`Status` = the 14 states, sealed `SagaEvent`,
  `SagaMachine.apply(status, event)` -> `Transition(from, to, effect)` or
  `exception/InvalidTransitionException`). All transitions of the 7.2 table are modelled and unit-tested;
  flows (`Flow`, one per `txnType`, found by `FlowRegistry`) are sets of reusable `Step`s
  (VALIDATE, POST, HOLD, CALL_PARTNER, COMMIT, RELEASE, REVERSE, QUERY, INQUIRE, BALANCE_INQUIRY; `Step.of(effect)`).
  `P2pFlow`, `CashInFlow`, `CashOutFlow`, `MerchantPayFlow` (ticket 30) = validate + post (parties as the channel set them:
  CASH_IN agent -> customer, CASH_OUT customer -> agent, MERCHANT_PAY customer -> merchant; the core enforces the wallet types).
  Merchant system leg (ticket 115b, FRS UC-11, EXT-11; `config/MerchantSystemProperties`
  `mw.orchestrator.merchant-systems.{merchants.<merchant wallet id>.{partner-id, merchant-id, till-id}, notify-attempts 3}`;
  none in the main config, dev: merchant20 `01911000020` -> `merchant-soap` / `M-0001` / `TILL-01` in the compose
  `SPRING_APPLICATION_JSON`): a MERCHANT_PAY whose credit wallet has a merchant system starts with
  `MerchantValidationStarted` -> VALIDATING + `Effect.SEND_MERCHANT_VALIDATE` (step CALL_PARTNER, which
  `MerchantPayFlow.steps()` lists; `partnerCall()` stays empty so an UNKNOWN posting is still inquired at the
  core): an INQUIRY on `mw.partner.request.merchant.v1` (`MerchantPayFlow.MERCHANT`, `PartnerRequestMapper.merchantValidate`:
  requestId `<txnId>:INQUIRY`, partner `{merchant-soap, MERCHANT}`, credit party `{MERCHANT, <merchant id>}`,
  `attributes.tillId`, no amount, deadline the VALIDATING timeout) before any core call. `PartnerReplyService.merchantAnswer`
  (any reply of family `merchant` for a MERCHANT_PAY): INQUIRY SUCCESS -> `MerchantValidated` (VALIDATING
  self-transition, SEND_VALIDATE: the core's ValidateTransaction, then post as usual); FAILED / UNKNOWN / NOT_FOUND ->
  `MerchantRejected` -> FAILED with the reply's error (fallback `MW-TMO-001`): nothing moved, no inquiry; PENDING
  waits for the VALIDATING deadline (FAILED `MW-TMO-001`). Entering COMPLETED (not from REVERSING) sends the
  NOTIFY (`SagaEngine.sendMerchantNotify`, a PAY with the amount, requestId `<txnId>:NOTIFY:<n>`) through the outbox,
  so it leaves only after the completion commits. A NOTIFY answer never moves the saga: SUCCESS is logged
  `merchant.notify.delivered`; any other answer to the LATEST attempt sends attempt n+1 at once until
  `notify-attempts` sends, then the notify is dead-lettered: ERROR `merchant.notify.dead_lettered` and audit
  `merchant.notify.failed` (outcome FAILURE, `after.attempts`, `after.errorCode`; visible in the console's audit
  search), the payment stays COMPLETED. An answer to an older attempt is ignored. Deviations: the retries have no
  back-off (the adapter's own call time spaces them) and the dead letter is the audit record, not a Kafka DLQ topic.
  A merchant without a system is validate + post as before (folder `04-merchant`). Tests `MerchantPayNotifyIT`,
  `SagaMachineTest`, `FlowRegistryTest`; API folder `50-merchant-soap`.
  `BulkDisburseFlow` (ticket 34, one child of an org bulk batch, sent by mw-portal-api) is validate +
  post too: debit `{ORG, <org wallet>}`, credit the customer's WALLET, **no PIN** (the org
  authorised the batch by maker-checker; the core's validate / transfer require the
  assertion + `idempotencyKey` for every txnType but BULK_DISBURSE). Only channels in
  `mw.orchestrator.bulk-channels` (default `ORG_PORTAL,PARTNER_APP`) may send one, else REJECTED `MW-AUTH-021`
  (`BulkDisburseSagaIT`); the core restricts it to ORG -> CUSTOMER. The portal derives txnId and
  key per row, so a resent child is answered from the idempotency record. The batch's parent
  BULK_DISBURSE txn is not a saga here (portal-side record, `attributes.parentTxnId`). `TopupFlow` = validate + hold + callPartner (`PartnerCall` telco,
  `mw.partner.request.telco.v1`) + commit + release. A postpaid preview fetches the bill once (`QuoteService` to
  mw-adapter-telco `POST /telco/v1/bills/fetch` on the operator profile, `gp` to `gp-ocs`) and the quote stores
  outstanding and minimum due; confirm pays any amount from the minimum through the outstanding and does not fetch
  again. An amount outside that range is `MW-VAL-030` before a hold. A prepaid amount off `mw.topup.denominations.<operator>`
  is `MW-VAL-031` before a hold (an operator with no list is unrestricted). `BillPayFlow` (ticket 21) is the same with
  `PartnerCall` billpay on `mw.partner.request.billpay.v1` (partner `{partnerId: <aggregator>,
  productCode: BILL, billerCode}` and credit party `BILLER_ACCOUNT` as the app gateway sets them;
  same 30 s partner deadline, TSQ on UNKNOWN and NOT_FOUND rules as TOPUP; the sync bill fetch
  never reaches the orchestrator: mw-app-gateway asks mw-adapter-billpay over HTTP).
  Biller amount rule (ticket 113c, FRS FL-04/FL-05): `messaging/ConfigChangedListener` also hands every
  `mw.config.changed.v1` BILLER record (key `biller/<code>`, owner mw-admin-api) to `service/BillerRules`, which keeps
  only the amount rule and its bounds in `txn.biller_rule` (V17, `repository/BillerRuleRepository`; an older version is
  ignored, DELETE removes the row, a value without a known rule is logged `biller.config_refused` and skipped). At intake,
  before routing and before any hold, `BillPayFlow.amountRefusal` refuses `MW-VAL-032` (bn and en): MINIMUM below
  `minAmountMinor`, OPEN outside `minAmountMinor..maxAmountMinor` (both inclusive, each optional), FIXED other than the
  fetched bill in `attributes.billOutstandingMinor`. FIXED without a fetched bill attached is left to the aggregator
  (the channels do not attach it yet; folder `06-billpay` pays DESCO that way); a biller with no row is not checked.
  `BillPayFlowTest`, `BillerAmountRuleIT`, API folder `49-biller-catalogue`. A stack whose orchestrator group already
  committed the BILLER records before V17 gets each rule on the biller's next change (or a fresh stack).
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
  `BalanceFlow` (ADR D26, txnType BALANCE) moves no money and the middleware never stores the balance:
  steps BALANCE_INQUIRY only (it overrides `allows`: no REVERSE, no INQUIRE). Intake refuses a BALANCE
  with an amount other than 0 (`MW-VAL-011`; 0 is refused `MW-VAL-011` for every other type), debit and
  credit party not the same WALLET (any MSISDN form, `SearchKeys.sameWallet`) or no assertion (`MW-VAL-001`).
  Path: RECEIVED -`BalanceInquiryStarted`-> VALIDATING (`Effect.SEND_BALANCE_INQUIRY`: `BalanceInquiry`
  over REST `GET /core/v1/balance/{walletId}?txnId=` (ticket 95a), commandId `<txnId>:balance`, `TransferPayload` with txnType BALANCE, amount
  0, the wallet on both sides, assertion + idempotencyKey) -`BalanceReported`-> COMPLETED;
  `BalanceInquiryRejected` -> FAILED `MW-CORE-<reason>` (`MW-AUTH-046` as it is); the VALIDATING deadline ->
  FAILED `MW-TMO-001` (retryable; never UNKNOWN, no inquiry). The balance (`dto/Balance`, from
  `CoreReplyMapper.balance`) is passed transiently `SagaEngine.fire(rec, event, error, balance)` ->
  `StatusMapper.toStatus(rec, balance)` into the COMPLETED status's `balance` only: not on the record, the
  `saga_step`/`core_call` timeline, the audit event or `wallet_txn` (`SearchKeys.walletParties` is empty for
  BALANCE, so a check is no wallet-history entry, ADR D25); `BalanceSagaIT` proves it. A repeated request
  (same key) is answered COMPLETED without a balance (there is none to replay); a REVERSAL of a BALANCE is
  refused `MW-VAL-021`. A balance answer for any other flow is refused by `SagaEngine.fire` (the event
  answers a step the flow lacks). Tests: `BalanceFlowTest`, `BalanceSagaIT`, `BalanceKafkaIT`.
  QUERY is allowed for every flow that holds,
  INQUIRE (status inquiry of an UNKNOWN outcome) for every flow but BALANCE.
  A new flow implements `txnType()`, `needsHold()` and, with a partner leg, `partnerCall()`.
  `SagaEngine.perform` runs the step behind a transition's effect only when the flow has it
  (`Flow.allows(Step)`: REVERSE and INQUIRE belong to every flow but BALANCE, QUERY to every flow that holds);
  SCHEDULE_INQUIRY arms the first inquiry slot. A transition whose effect is a step the flow lacks
  is refused by `SagaEngine.fire` as an `InvalidTransitionException` before anything is written
  (S2: ManualCommit of P2P/CASH_IN/CASH_OUT/MERCHANT_PAY, ManualRelease of ADD_MONEY_BANK); only an unknown
  txnType is logged `saga.effect.unwired` and skipped. `SagaEngine.fire` is the single place that applies an
  event: persist state + `saga_step`, publish status + audit event, re-arm the deadline, send the
  next command. `SagaEngine.created` publishes the first state (RECEIVED) the same way.
- Hold path (TOPUP): FundsHeld stores `holdId` (`transaction.hold_id`), PARTNER_PENDING sends PAY
  (`mapper/PartnerRequestMapper`: `requestId` = name-based UUID of `<txnId>:PAY`, stable across
  resends; `partnerTxnRef` `BITS<txnId hex>`; `deadlineAt` = now + `partner-timeout`; never the
  PIN). `service/PartnerReplyService` (inbox key `requestId:outcome`): SUCCESS -> COMMITTING
  (partner's `partnerRef` stored in `transaction.partner_ref`, shown on the status), FAILED ->
  RELEASING with the reply's canonical error (kept into FAILED; a code outside the status
  pattern becomes `MW-PTR-<PARTNER>-UNMAPPED`), UNKNOWN -> UNKNOWN (status inquiry), PENDING
  ignored. HoldRejected -> REJECTED (`MW-CORE-*`). HoldExpired (ticket 95c, FRS 7.3; the core has already
  freed the reservation and a later commit fails `HOLD_EXPIRED`, a release succeeds without effect): on
  `HOLDING`, `PARTNER_PENDING` or `UNKNOWN` the saga goes to RELEASING with `MW-CORE-HOLD_EXPIRED` and sends
  ReleaseHold for the event's hold id (kept into FAILED when the release is acknowledged); on `COMMITTING`
  (the partner already succeeded) it goes to MANUAL_REVIEW with the same code and sends nothing. Any other
  state, a repeated event (inbox `orchestrator.hold-expiry`) and a flow that holds nothing stay as they are.
  `WalletStateChanged`, `WalletUnlocked` and `UnlockRejected` are consumed and move no saga. `HoldExpirySagaIT`.
  The deadline resends CommitHold / ReleaseHold
  until the core answers (7.2 "retry until acknowledged"; still unanswered 60 s after entering the
  state: WARN `txn.commit.unacknowledged` / `txn.release.unacknowledged` + counter, once); a definite
  release refusal (ReleaseFailed: hold already committed) goes to MANUAL_REVIEW with
  `MW-CORE-<reason>` (`ReleaseRefused`; deviation from the 7.2 table, which lists no exit for a
  refusal), alerted ERROR `txn.release.failed`. A definite commit refusal (CommitFailed or
  CreditRejected, ERROR `txn.commit.failed` Sev-1, `MW-CORE-<reason>`) on a flow with a partner leg
  does not stop there when it is the saga's own commit: see "Partner reversal after a refused
  commit" below. A refusal of an operator round (`:manual-n`), or a commit with no partner leg,
  still goes to MANUAL_REVIEW.
- Alerts (S4, `service/SagaAlerts`, spec 6.3/7.2/21): stable `event` log lines and Micrometer meters
  `mw.txn.commit.unacknowledged`, `mw.txn.release.unacknowledged`, `mw.txn.commit.failed`,
  `mw.txn.release.failed`, `mw.txn.reversal.retry.exhausted` (counters) and
  `mw.manual.review.oldest.age` (gauge, seconds). Entering MANUAL_REVIEW from any state (FRS PLT-024,
  ticket 96a; hook in `SagaEngine.fire`): counter `mw.manual.review.entered` (`mw_manual_review_entered_total`)
  and WARN `txn.manual_review.entered` (`from`, `errorCode`), both after the state change commits; the
  operations SMS/email is mw-notify's `OPS` rule on the MANUAL_REVIEW status of `mw.txn.status.v1` (no
  extra event). Daily MANUAL_REVIEW ageing check:
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
  `mw.orchestrator.inquiry.*` (FRS PLT-023/024, ticket 96a): `schedule` 30s,2m,10m,1h (waits before each
  inquiry; `MW_ORCHESTRATOR_INQUIRY_SCHEDULE`), `answer-wait` 30s (how long the last inquiry's answer is
  awaited), `partners.<partnerId>.schedule` (a partner's own list of waits; nothing else is overridable).
  The slot after the last inquiry asks nothing: MANUAL_REVIEW `MW-TMO-004`. Answers (any reply for an UNKNOWN txn, the
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
  owned by mw-admin-api; `messaging/TxnActionListener`, retry topics + DLQ,
  own type `dto/TxnAction`) -> `service/OperatorActionService`, inbox key `actionId` (consumer
  `orchestrator.txn-actions`): FORCE_COMMIT = ManualCommit, FORCE_RELEASE = ManualRelease (MANUAL_REVIEW
  only), INQUIRE = `SagaEvent.ManualInquiry` (UNKNOWN or MANUAL_REVIEW, self-transition with
  SEND_INQUIRY: one status inquiry now, the schedule's timer untouched; in MANUAL_REVIEW the answer
  only lands on the timeline, `partner_call` / `core_call`, and is logged late/conflict). A forced
  commit / release / credit is its own core command round: commandId `<txnId>:commit:manual-<n>`
  (`release`, `credit` likewise; n = how many ManualCommit / ManualRelease steps the txn has had,
  `TransactionRepository.countSteps`), so the core decides it afresh instead of replaying the refusal
  it logged for `<txnId>:commit`. Forced CommitHold is safe as it is: the core commits a hold once
  whatever the commandId (commit of a committed hold succeeds without effect, of a released one fails
  `HOLD_RELEASED`). A forced **credit** (ADD_MONEY_BANK, no hold; security review of ticket 37) is not:
  if an earlier CreditWallet was applied but its answer lost, a fresh-round id would credit twice. So
  the commit step of a forced credit first asks the core (`CoreCommandMapper.creditCheck`:
  QueryTransaction, commandId `<txnId>:credit-check:<epoch ms>`, fresh per query) and
  `CoreReplyService` turns the TransactionStatus answer into `SagaEvent.CreditCheckResult` (COMMITTING
  only; elsewhere stale, ignored): CREDITED / POSTED -> COMPLETED with no new credit; NOT_FOUND /
  VALIDATED / REJECTED -> stays COMMITTING with `Effect.SEND_CREDIT`, which sends
  `<txnId>:credit:manual-<n>`; any other answer -> MANUAL_REVIEW `MW-TMO-005`. Each COMMITTING deadline
  of a forced credit asks again instead of resending blind. Only the answer to the LATEST check counts
  (`CoreCallRepository.latestCreditCheck`: last `:credit-check:` commandId of the txn in `core_call`
  send order, the one decision the saga takes from that table): a late answer to an older check (an
  earlier round, or one superseded by a deadline re-check) is logged `saga.event.ignored` and changes
  nothing; a redelivered answer to the latest one only resends the same round's id (the core
  deduplicates it). The saga's own credit (round 0, `<txnId>:credit`)
  sends no check. Defence in depth: mw-core-mock credits a txnId once whatever the commandId (an
  already credited one is answered WalletCredited without effect). An action the state or flow does not allow, or an unknown action, changes nothing. Every
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
  that reference or our own `BITS<txnId hex>`; otherwise nothing changes, event
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
  idempotency key is stored with the ORIGINAL's txnId. A completed transaction with no held partner
  leg (P2P, cash, merchant pay, add money) is reversed at the core only. A completed TOPUP or
  BILL_PAY is different, below: the partner is reversed first. A refused commit is also below.
- Merchant refund (ticket 116b, FRS CH-WEB-03, FL-06, UC-12; `RefundSagaIT`): a REVERSAL from a channel in
  `mw.orchestrator.refund-channels` (default `WEB_CHECKOUT`) is NOT the SYSTEM reversal above: it is its
  own transaction record (`saga/RefundFlow`, txnType REVERSAL, its own txnId and idempotency key, scope
  (channel, merchant id, key); stored `attributes.originalTxnId` and `attributes.refundOf` = the
  original's txnType) and the original stays COMPLETED, so a payment can be refunded several times.
  Accepted only when the original (row-locked, so refunds of one payment serialise) is a COMPLETED
  MERCHANT_PAY made on the same channel whose `metadata.merchantId` equals the refund's initiator
  `{type: MERCHANT, id: <merchantId>}`; amount 1..the original's. Refused with a REJECTED status under
  the request's txnId (no row, key not consumed): unknown original `MW-VAL-020`, not the owning merchant
  or not paid on this channel `MW-AUTH-022`, parties not the original's reversed (debit the merchant paid,
  credit the payer) `MW-VAL-001`, original not COMPLETED `MW-VAL-021`, amount `MW-VAL-022`. The record stores
  the ORIGINAL's parties reversed, never the request's own.
  Path: RECEIVED -`RefundStarted`-> REVERSING (ReverseTransaction under the refund's own txnId,
  `<refundTxnId>:reverse`, `POST /core/v1/reversals` with `amount`, partial or whole; the core keeps the
  refunded total) -`RefundConfirmed`-> COMPLETED; a core refusal (`MW-CORE-REFUND_EXCEEDS` once the total
  would pass the original, `ALREADY_REVERSED`, ...) -`RefundRefused`-> REJECTED. No answer: the REVERSING
  schedule above, the query asks about the refund's own txnId (`REVERSED` -> COMPLETED, else / none
  MANUAL_REVIEW `MW-TMO-020`; a later TransactionReversed moves MANUAL_REVIEW to COMPLETED). A refund is
  never reversed itself (`MW-VAL-021`) or inquired. The SYSTEM full reversal keeps the original-moving
  path; after a partial refund the core refuses it (`REFUND_EXCEEDS`) and it ends MANUAL_REVIEW.
- Partner reversal after a refused commit (ticket 96b, FRS PLT-022, PLT-025): CommitFailed or
  CreditRejected on a flow with a partner leg (`CommitRefused(compensatePartner=true)`) moves
  COMMITTING to REVERSING and sends `REVERSE` on `mw.partner.request.<family>.v1`. An operator's
  forced commit or credit that the core refuses again stays MANUAL_REVIEW. The request id
  is the name-based UUID of `reverse:<txnId>` (the same key on every resend; amount and
  `originalPartnerRef` are the PAY's). `transaction.pending_leg` names the leg:
  `PARTNER_REVERSE`, then `HOLD_QUERY`, then `HOLD_RELEASE`. The partner leg is resent every
  `retry-timeout` until `reversing-timeout` (60 s); no definite answer by then is MANUAL_REVIEW
  `MW-TMO-022`. A definite FAILED ends FAILED (`PartnerReverseRefused`) and does not touch the hold.
  SUCCESS (`PartnerReverseConfirmed`) asks the core, commandId `<txnId>:hold-check:<epoch ms>`:
  `HELD` releases the hold and HoldReleased ends REVERSED; `RELEASED`, `NOT_FOUND`, `REJECTED` or
  `REVERSED` ends REVERSED with nothing to release; any other status, or no answer by
  `retry-timeout`, is MANUAL_REVIEW `MW-TMO-021`. ReleaseHold of a hold that remains is resent
  every `retry-timeout` (the release-unacknowledged alert); ReleaseFailed is MANUAL_REVIEW. A
  duplicate REVERSE ack is dropped by the inbox (`requestId:outcome`). `PartnerReversalIT`.
- Partner-first reversal of a completed top-up or bill payment (ticket 96c, FRS PLT-022, PLT-025,
  spec 7.4): TOPUP and BILL_PAY hold, pay the partner, then commit. Reversing one
  (`ReversalRequested(partnerFirst)`) sets `pending_leg` `PARTNER_REVERSE` and sends the same
  `REVERSE` (key `reverse:<txnId>`) before any core call. The partner leg is resent on the same
  schedule as a refused commit; no definite answer is MANUAL_REVIEW `MW-TMO-022`, and no
  ReverseTransaction has been sent. SUCCESS (`PartnerReverseConfirmed(CORE)`) clears the leg and
  only then sends ReverseTransaction under the reversal's txnId; from there the core reversal
  above applies (TransactionReversed -> REVERSED, a core refusal or no confirmation ->
  MANUAL_REVIEW). A definite partner FAILED (`PartnerReverseRefused(MANUAL_REVIEW)`) ends
  MANUAL_REVIEW with the partner's error and does not move the posting: the partner still delivered
  the airtime or the bill. A completed ADD_MONEY_BANK stays a core reversal (the bank credit is not
  this leg). A reply that arrives after the partner leg has finished changes nothing.
  `CompletedPartnerReversalIT`. API folder `30-saga-frs`.
- Audit (ticket 12, spec 14.3): one `mw.audit.event.v1` per state a transaction passes through
  (`mapper/AuditMapper`, own type `dto/AuditEvent`), through the outbox in the same transaction:
  action `txn.state.changed`, resource `txn/<txnId>`, actor `system`/`mw-orchestrator`, `before`
  `{status}` (absent for RECEIVED), `after` `{status, errorCode?}`, random `auditId` (also the
  `mw-idempotency-key` header), key txnId. No parties, amounts or PIN, so nothing to mask. A
  repeated request or a refused event changes no state and is not audited.
- Intake (`service/TransactionService`): idempotency in `txn.idempotency`, scope (channel,
  initiator id, key), TTL 72 h (`mw.orchestrator.idempotency-ttl`). The request hash is SHA-256
  over the business content (channel, type, parties, amount, partner, originalTxnId,
  attributes), not txnId, correlationId, requestedAt or the assertion. Same key + same hash:
  a status record for the STORED txnId with the current status and `attributes.requestTxnId` =
  txnId of the repeated request (so a channel waiting on its own txnId can match it); same key
  + other hash: REJECTED status under the new request's txnId with `MW-IDEM-001`, no transaction
  row. Redis 60 s double-tap lock (7.3 rule 4, ticket 50, `service/IdempotencyGate` +
  `client/RedisIdempotencyLock`, `config/RedisConfig`): before the claim, `SET NX PX 60000` on
  `mw:orchestrator:idem:<sha256(channel, initiator id, key)>` (no MSISDN in Redis) with value
  `<txnId> <request hash hex>`. Held by another txnId: same hash -> duplicate answer of the holder
  without a claim attempt (its current status, or RECEIVED + `attributes.duplicate` while the
  holder's row is not committed yet, `StatusMapper.toInFlightDuplicateStatus`), other hash ->
  `MW-IDEM-001`. Held by the same txnId (a redelivered record), an unreadable value, or Redis
  unreachable/slow (200 ms timeouts, commands rejected while disconnected) -> on to the claim;
  the primary key stays the final guarantee. Redis trouble never blocks or fails a request:
  WARN `txn.idempotency.lock_unavailable` + counter `mw.txn.idempotency.lock.unavailable`;
  Redis is not in the health groups. The lock is never released early. REVERSAL requests take
  no lock (the original's row lock serialises them). Settings `mw.orchestrator.idempotency-lock.{enabled,ttl}`
  (`MW_IDEMPOTENCY_LOCK_ENABLED`; off in the test config, `IdempotencyLockIntakeIT` plugs in an
  in-memory lock), `spring.data.redis.*` (`REDIS_HOST`; password `spring.data.redis.password` in
  `secret/mw-orchestrator`, synced by the init from `REDIS_PASSWORD`).
  Deviations from 7.3 rule 2 (S7, recorded, not restructured): the final response is not stored,
  a repeat is answered from the live transaction row (current status of the stored txnId), so the
  never-written `idempotency.response` column was dropped (V7); `txn.idempotency` is not
  partitioned, so there is no purge by partition drop; an expired key (`expires_at` passed) is
  reclaimed in place by the next request with it, and no job deletes expired rows yet
  (`RetentionService` purges only outbox and inbox).
- Business duplicates (ticket 99, FRS PLT-013; `service/DuplicateGuard`, settings
  `mw.orchestrator.duplicate-guard.{enabled, window (60 s, MW_DUPLICATE_WINDOW; 0 = off), prompt-channels,
  exempt-channels (SYSTEM)}`): before the idempotency lock and the claim, `SET NX EX window` on
  `mw:orchestrator:dup:<sha256(initiator id, txnType, debit party, credit party, amount, currency)>` with
  value `sha256(channel, initiator id, key)` in the same Redis as the lock (through `IdempotencyLock`,
  so it is off with it). Held by another key: an app/USSD channel (`prompt-channels`) gets REJECTED
  `MW-VAL-024` (Bangla text, not retryable), an API client (checkout, portal, partner) `MW-VAL-025`; nothing
  recorded, the key not consumed, no core call. Held by the same key: idempotency answers (7.3). A request
  with `confirmDuplicate: true` (contract field, not in the request hash) from a prompt channel passes; an
  API client cannot confirm. BALANCE, REVERSAL and exempt channels are never checked; Redis trouble lets the
  request through (WARN `txn.duplicate.guard_unavailable`, counter `mw.txn.duplicate.guard.unavailable`).
  The dev stack runs with the window at 0 (`deploy/dev.env.defaults`): the test API `POST
  /test/v1/duplicate-guard {windowSeconds}` sets it in memory for API folder 36. Tests `DuplicateGuardTest`,
  `DuplicateGuardIT`.
- Timers: `txn.saga_timer` (one row per txn and state), swept by `scheduler/SagaTimerJob` every
  second under ShedLock; `SagaTimerService.sweep` uses `FOR UPDATE SKIP LOCKED`. Deadlines are
  `mw.orchestrator.*-timeout`: VALIDATING 5 s -> FAILED (`MW-TMO-001`, retryable), POSTING 5 s ->
  UNKNOWN, HOLDING 5 s -> QueryTransaction (stays HOLDING), PARTNER_PENDING 30 s -> UNKNOWN,
  COMMITTING / RELEASING / REVERSING every `retry-timeout` (5 s) -> resend CommitHold /
  ReleaseHold / ReverseTransaction (same commandId; REVERSING stops resending after
  `reversing-timeout` and asks the core, above),
  UNKNOWN on the inquiry schedule (above). A REVERSING transaction whose pending leg is
  `PARTNER_REVERSE` resends the partner REVERSE instead, and goes to MANUAL_REVIEW `MW-TMO-022`
  when that window ends with no definite answer, before any core call. MANUAL_REVIEW and terminal states arm no timer. A late
  core reply after a timeout is ignored (state machine refuses it).
- `commandId` = `<txnId>:validate` / `:post` / `:hold` / `:commit` / `:release` / `:credit` / `:balance`
  (QueryTransaction: above; ReverseTransaction: `<reversalTxnId>:reverse`). `expiresAt` = issuedAt + `command-ttl` (60 s).
  The commandId is the step id on the saga timeline (`core_call`); the core's own idempotency key is the txnId and
  the operation (`<txnId>:rest:<op>` at mw-core-mock). `expiresAt` is no longer sent (REST has no expired-command
  drop); `command-ttl` still times the not-found grace and the reversal schedule. Calls go over REST since
  ticket 95a (no core command topics; `service/CoreTopics` keeps the two event topic names).
- Core reason -> error: `MW-CORE-<reasonCode>` (same pattern as the status contract), message
  English + Bengali for the reasons mw-core-mock gives (`mapper/CoreReplyMapper`), not retryable.
  Other codes used: `MW-VAL-010` unsupported txn type (a contract type without a flow, e.g. NPSB_OUT, NPSB_IN until their tickets; `SagaServiceIT`), `MW-VAL-011` amount <= 0, `MW-VAL-012`
  partner missing for a flow with a partner leg, `MW-VAL-030`/`MW-VAL-031` top-up amount, `MW-VAL-032` biller amount rule, `MW-IDEM-001`, `MW-TMO-001`, `MW-TMO-002`,
  `MW-TMO-003` (not found by inquiry, retryable), `MW-TMO-004` (inquiry exhausted),
  `MW-TMO-005` (conflicting inquiry answer), reversal `MW-VAL-020..022`, operator action not allowed `MW-VAL-023`, `MW-AUTH-020`, `MW-TMO-020`, bulk channel `MW-AUTH-021`, refund not by the owning merchant `MW-AUTH-022`;
  partner codes come from the adapter's reply.
- Transaction assertion (spec 18.7, D22, ticket 71): a `mw.txn.request.v1` with `assertion` (mw-auth's JWS)
  keeps it in the stored request (it is no PIN material; `dto/TxnRequest.assertion`, `@Sensitive`) and
  `TransferPayload.of` copies it, with the request's `idempotencyKey` (part of its txnHash), into
  ValidateTransaction, PostTransfer, HoldFunds and BalanceInquiry; the client sends it as `X-Txn-Assertion`
  (ticket 95a). It is not part of the idempotency request hash. A core refusal `401 MW-AUTH-046` (assertion
  invalid or used) becomes the status error `MW-AUTH-046` as it is (`CoreReplyMapper`), not `MW-CORE-*`. Tests:
  `mapper/CoreCommandMapperTest` (the assertion reaches every authorising call; a bulk child sends none),
  `mapper/CoreReplyMapperTest`, `client/CoreWalletClientTest`.
- No PIN (ticket 72, M11 removed): the channels check the PIN at mw-auth and send only the assertion; the
  contracts carry no `encryptedPin` any more, `transaction.encrypted_pin` and its wipe on terminal states are
  gone (`V11__drop_encrypted_pin.sql`; V10 is the Java search-hash migration), and an old channel's
  `encryptedPin` would be ignored as an unknown field (`dto/TxnRequestSchemaTest`). The core reasons
  `INVALID_PIN` / `INVALID_PIN_BLOCK` are gone with mw-core-mock's PIN check (no text kept for them; an
  unknown reason still maps to `MW-CORE-<reason>` with the generic text); `ACCOUNT_LOCKED` now means a
  core-side lock (mw-core-mock CLAUDE.md).
- `mw-reply-to` (7.5): copied from the request record to every status record (and to the answer
  for a repeated request, taken from the repeated request). Stored in `transaction.reply_to`.
- Schema `txn` (role `orchestrator_rw`): `transaction`, `saga_step`, `saga_timer`, `idempotency`
  (+ `outbox`, `inbox`, `shedlock`, `partner_call`, `core_call`, `wallet_txn`, `routing_rule`, `msisdn_prefix`,
  `mnp_override`, `maintenance_message`, `biller_rule`); migrations V1..V17 (V17 `biller_rule`, ticket 113c; V14 routing tables and the prefix and partner
  seeds, ticket 98; V12 `wallet_txn`,
  V13 = Java migration `repository/WalletPartyMigration` filling it for older rows, ADR D25, `WalletPartyMigrationIT`;
  V11 drops `transaction.encrypted_pin`, ticket 72; V6 `reversal_txn_id`, V7 drops
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
  ReverseTransaction is filed under the original's txnId) and answered by `CoreReplyService` before
  the state machine runs, so an ignored late result still shows; first result kept) and
  `partnerCalls` (`txn.partner_call`, V8: one row per PAY / STATUS_QUERY sent by
  `SagaEngine.sendToPartner`, answered by `PartnerReplyService` (outcome, partnerRef, errorCode,
  `repliedAt`), plus one CALLBACK row per callback that passed the ownership checks). Clients are
  mw-admin-api, mw-portal-api and (wallet history, ADR D25) mw-app-gateway over the `app` network; **no APISIX route**. Auth (M3 gate decision,
  `config/SecurityConfig`, `security/InternalCallerPolicy`, `config/InternalAuthProperties`
  `mw.internal-auth.*`): a Keycloak client-credentials JWT of realm `services` (JWKS
  `MW_INTERNAL_JWK_SET_URI`, exact issuer `MW_INTERNAL_ISSUER`, default `http://keycloak:8080/realms/services`,
  `aud` must contain `mw-orchestrator`); caller `azp` in `allowed-clients` (mw-portal-api, mw-admin-api,
  mw-app-gateway wallet-scoped, below),
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
  start; none in the main config, the dev ones (ORG002: DESCO and 01911000001) in `deploy/docker-compose.yml` as
  `SPRING_APPLICATION_JSON`, test ones in the test config). Each org-scoped item carries `role`
  INITIATOR (wins) or PAYEE; unscoped results carry none. Rows from before V8 had no hash and V8
  rows an untyped one: V10 re-hashes them all, so none is left behind. Views
  (`mapper/TransactionViewMapper`): parties masked by type, names to the initial, error text and the
  SP `reference` scrubbed, only the bulk attributes (`parentTxnId`, `batchId`, `lineNo`, `reference`);
  no PIN, idempotency key or raw request. `TransactionQueryApiIT` (TSQ'd top-up timeline with core
  commands, resend count and failure reason, masking, typed org scope, payee roles, wallet history).
  Wallet scope (ADR D25, contract 1.3.0): `walletId` (any MSISDN form) returns the transactions the wallet
  is the debit party of (`direction` OUT, any status) or the credit party of (`direction` IN, only in
  `mw.orchestrator.search.wallet-in-statuses`, default COMPLETED,REVERSING,REVERSED: a failed transfer to it
  gave it nothing), every channel; `direction` narrows it (without `walletId` 400 `MW-VAL-001`). Matched
  through `txn.wallet_txn` (V12: `wallet_hash` = `HMAC(WALLET:<canonical MSISDN>)` under the search key,
  `txn_id` (FK, on delete cascade), `direction`, `created_at`; index `(wallet_hash, created_at DESC, txn_id
  DESC)`), written by `TransactionRepository.insert` in the row's transaction from
  `util/SearchKeys.walletParties` (debit party OUT, credit party IN, each only when its type is WALLET (any
  walletType) or MERCHANT: never ORG, a top-up MSISDN, BANK_ACCOUNT or BILLER_ACCOUNT; a wallet on both sides
  once, as OUT); V13 fills older rows (`on conflict do nothing`). Each item carries `direction`
  (`TransactionQueryService.direction`); unscoped results carry none. Caller: `wallet-scoped-clients`
  (mw-app-gateway, also in `allowed-clients`) may call `POST /internal/v1/quotes` (ticket 105, no query
  parameters; the wallet is in the body) and `GET /internal/v1/transactions` with a non-blank
  `walletId` and the query parameters walletId, direction, txnType, status, from, to, limit, offset;
  anything else 403 `MW-AUTH-002`, WARN `security.internal_call_denied` reason `PATH_NOT_ALLOWED` /
  `WALLET_REQUIRED` / `PARAM_NOT_ALLOWED` (`security/InternalCallerPolicyTest`, `InternalApiAuthIT`).
- Test-only API (ticket 58, contract `api/mw-orchestrator/orchestrator-test.v1.openapi.yaml`;
  `controller/TestApiController` -> `service/TestApiService`, both only with
  `mw.orchestrator.test-api.enabled=true` = `MW_ORCHESTRATOR_TEST_API_ENABLED`, which only the dev
  stack sets; off, the paths are 404): the host API suite (`tests/api`) plays operations' REVERSAL
  producer without the Docker CLI. `POST /test/v1/reversals {originalTxnId, reason?}` loads the
  original (404 `MW-VAL-003` if unknown) and publishes on `mw.txn.request.v1` (`messaging/MwTopics.TXN_REQUEST`, keyed by txnId,
  `messaging/EventPublisher` directly: no local state changes) a REVERSAL as operations sends it:
  fresh UUIDv7 txnId, key `test-rev-<uuidv7>`, a plain random UUID correlationId (command id, no prefix), channel SYSTEM, initiator ORG `bits-ops-test`, the
  original's parties swapped, its amount, `attributes.reason` (default `test reversal`); answers 202
  `{txnId, idempotencyKey}`. The request is then handled like any other (rules above). `GET
  /test/v1/txn-requests/{idempotencyKey}` is 200 `{idempotencyKey, handled: true}` once
  `txn.idempotency` holds the key (any scope, `IdempotencyRepository.exists`), else 404 `MW-VAL-003` (both decided in `TestApiService.handled`): the only
  trace of a repeated reversal. Retry-to-DLQ path (ticket 58, folder `90 Slow`): `POST
  /test/v1/poison-requests` publishes (keyed by a fresh UUIDv7 txnId) a record `TxnRequestListener` can
  read but always refuses (schemaVersion, txnId, txnType only), so it walks retry.1..3 to
  `mw.txn.request.v1.mw-orchestrator.dlq`; 202 `{txnId, dlqTopic}`, nothing stored. `GET
  /test/v1/dlq-records/{txnId}` is 200 `{txnId, topic, parked: true}` once the DLQ holds the key, else
  404 `MW-VAL-003` (`client/DlqClient`: a short-lived group-less consumer reading the topic from its start
  to its current end on the HTTP thread, nothing committed; `DlqClientIT`). No auth in the service (`anyRequest().permitAll()` outside
  `/internal/**`); APISIX exposes it only with `MW_E2E_ROUTES=1` as `/test/orchestrator/v1/*`
  (`deploy/apisix/routes-test/test-orchestrator.json`, key-auth `mw_e2e`, private networks).
  `POST /test/v1/inquiries` drives one status inquiry of a stored transaction (ticket 112c; 404
  `MW-VAL-003` when it is unknown) and `GET /test/v1/partner-health/{partnerId}` returns the health
  routing has noted. `TestApiControllerTest` (on/off), `TestApiServiceTest`.
- Dev provisioning (`provision_mw_orchestrator` in `deploy/provision/services.sh`, run by the one-shot `provision` on every `docker compose up`, idempotent, dev only): creates
  role and schema, `secret/mw-orchestrator` (DB password, never overwritten; search hash key, created once) and the AppRole
  `mw-orchestrator`; role-id/secret-id land in a volume the service reads as a configtree.

## Contracts owned

- `events/mw-orchestrator/mw.txn.request.v1`, `mw.txn.status.v1`, `mw.partner.request.v1`, `mw.partner.reply.v1`, `mw.partner.inbound.v1`,
  `api/mw-orchestrator/txn-query.v1.openapi.yaml` (internal query API, ticket 35; 1.3.0 wallet scope, ADR D25),
  `api/mw-orchestrator/orchestrator-test.v1.openapi.yaml` (test-only API, ticket 58), and
  `common/defs.v1` (see `contracts/OWNERS.yml`). Tests validate every status the service emits against
  them. Since ticket 95a the orchestrator calls the core over REST; ticket 95b retired `core.command.v1`
  (its files, samples and the two command topics): `dto/CoreCommand` and its payloads are the internal
  shape of a saga step's core call only. The core's `WalletUnlocked` / `UnlockRejected` (mw-admin-api's
  REST unlock) carry a txnId the orchestrator does not know; they are consumed and move no saga.

## Contracts used

- `contracts.yml` (module root) is the machine-readable list of what this service produces and
  consumes (partner families separately; the core wallet API under `consumes.apis`, ticket 95a;
  `core.event.v1#HoldExpired` and `#WalletStateChanged`, ticket 95c; the result events of the REST calls
  stay unlisted), checked by `./gradlew :contractsLint` and tested without Docker by
  `contracts/ProducedContractsTest` (every produced event built by the real mapper, serialised as
  the outbox does, validated), `contracts/ConsumedContractsTest` (every valid sample of a consumed
  event read into the own DTO) and `contracts/ApiContractsTest` (txn-query response bodies against
  the OpenAPI component schemas; also the test API's bodies). A new event type or family goes into
  `contracts.yml` and gets a fixture there. `mw.txn.request.v1` is consumed and, by the test-only
  reversal only, produced (fixture: the REVERSAL `TestApiService` publishes).
- `dto/ConfigChangedEvent` (`mw.config.changed.v1`, owner mw-admin-api): routing resources (ticket 98) and BILLER
  amount rules (ticket 113c), consumed by `messaging/ConfigChangedListener`.
- `events/mw-core-mock/core.event.v1.schema.json`: own type `dto/CoreEvent` (payload read as a
  tree per `eventType`), read by `CoreEventListener`. Result events of the REST calls are ignored
  (ticket 95a). `HoldExpired` and `WalletStateChanged` are consumed (ticket 95c; `ConsumedContractsTest`).
  Unknown fields are ignored.
- `api/mw-core-mock/core-wallet.v1.openapi.yaml` (consumed, ADR D31, ticket 95a): `client/CoreWalletClient`;
  `CoreWalletClientTest` validates its request bodies against the contract's schemas.
- `events/mw-admin-api/mw.txn.action.v1.schema.json` (consumed, ticket 37): own type `dto/TxnAction`;
  `ConsumedContractsTest` reads the valid samples, `service/OperatorActionIT` covers the handling.
  The REVERSAL mw-admin-api sends on `mw.txn.request.v1` names only `ORG`/`bits-ops` as parties and
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

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (written inline in
  `build.gradle.kts`; drop once the Boot BOM ships them); the shared dev provisioning image `deploy/provision/Dockerfile` runs `apk upgrade` and removes gosu. `scripts/security.sh <this module>` scans the SBOM and the
  `mfs-mw/*:local` images (HIGH/CRITICAL fail; accepted ones in `.trivyignore.yaml` with a reason and expiry).
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
- `config/MwServiceProperties`: config binding; secrets from Vault when `VAULT_ENABLED=true`.
- `messaging/EventPublisher`: Kafka records with the 6.1 headers, idempotent `acks=all` producer.
- Kafka through Spring Kafka (ADR D27, from the template, ticket 81; no Camel): `config/KafkaConfig`
  (`config/KafkaRetryProperties`, `config/MwKafkaProperties`), `messaging/RetryTopicNaming`,
  `RetryTopicRecoverer`, `RetryDueTimeCap`, `ConsumerContextInterceptor`, `Utf8Deserializer`. Every
  `@KafkaListener` on the default factory (`groupId = "${spring.kafka.consumer.group-id}"`,
  `autoStartup` = the service's `consumers.enabled` switch, `ack.acknowledge()` after the record is
  handled) gets retry topics `<topic>.<group>.retry.1..3` (10 s / 1 min / 10 min) then
  `<topic>.<group>.dlq` (19.2, names unchanged, `deploy/kafka/topics.txt`); validation, JSON and
  deserialisation failures go to the DLQ at once; delays pause the partition, never sleep on the
  consumer thread. `mw.kafka.retry.exclude-topics` opts a topic out. Outbox relay:
  `scheduler/OutboxRelayScheduler` (`mw.outbox.relay.period`, ShedLock). Conformance:
  `messaging/RetryTopicIT`, `scheduler/OutboxRelaySchedulerIT`; `messaging/ConsumerTopicsIT` checks
  this service's consumed topics, groups and retry/DLQ names.
- `application.yml`: Resilience4j defaults (19.2), own schema `txn` migrated by Flyway on
  start from `db/migration` (16.4).

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-orchestrator:test` | every test in one JVM: unit, slice and contract tests, the Testcontainers `*IT` classes and the `@Conformance` suite; one PostgreSQL and one Kafka per test JVM, a fresh database per Spring context; the `*IT` and conformance classes are skipped without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-orchestrator:build` | `test`, then the boot jar `build/libs/mw-orchestrator.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-orchestrator:bootJar && docker build -t mw-orchestrator:dev services/mw-orchestrator` | image, as spec 4.3 rule 1 |
| `./gradlew clean build` | the full gate: every module, plus the repo checks (`:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`); unchanged modules come from the build cache |
| `./gradlew :mw-orchestrator:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-orchestrator` | rebuild this service's image (`mfs-mw/mw-orchestrator:local`) and restart it with what it depends on (dev timers from `deploy/.env`) |
