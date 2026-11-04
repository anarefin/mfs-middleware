# mw-notify

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.notify`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Customer notifications (spec 10, WP17 part, ticket 24). MVP: SMS over SMPP 3.4 only; email,
  push, quiet hours and channel fallback come later (a request without SMS to an MSISDN is
  recorded FAILED `MW-VAL-101`).
- Intake (`service/NotificationService`): `mw.notify.request.v1` (`route/NotifyRequestRoute`,
  `messaging/NotifyRequestListener`) and rules on `mw.txn.status.v1` (`route/TxnStatusRoute`,
  `service/TxnStatusService`, `service/RuleEngine`). `messageId` is the idempotency key (PK of
  `notify.message`, duplicates dropped, event `notify.duplicate`); rule-built ids are
  `<txnId>:sms:<ruleId>`. `expiresAt` defaults by priority (`dto/Priority`: OTP 2 min, TXN 24 h,
  INFO 24 h, PROMO 72 h); an expired message is recorded EXPIRED and never sent. Listeners only
  store; nothing is sent on a Kafka thread.
- Template `CHECKOUT_OTP` (bn/en, `V6_1`, ticket 31): mw-checkout's OTP SMS, params otp, amount, merchant.
- Rules (`notify.rule`, seeds in `V5`): `condition` is SpEL in a `SimpleEvaluationContext`
  (read-only getters of `RuleEngine.TxnFacts`: status, txnType, channel, amountMinor,
  initiatorId, debitPartyId, creditPartyId, debitPartyType, creditPartyType; no types, beans or
  constructors); `recipient` INITIATOR|DEBIT_PARTY|CREDIT_PARTY (an MSISDN-shaped id only).
  Params: amount, fee (taka, 2 decimals), txnId, txnType, counterparty (the other party, masked
  by type), coreRef, partnerRef. Locale: the event's, else `mw.notify.default-locale` (bn).
- Templates (`notify.template`, highest ACTIVE version with `valid_from <= now`): logic-less
  `{{name}}` (`service/TemplateRenderer`; sections refused, a missing param fails the message
  `MW-VAL-102`); params masked first by name (`service/ParamMasker`: msisdn, receiver, sender,
  counterparty, agent -> MSISDN; account, accountNumber -> last 4; nid -> last 3; name,
  receiverName, senderName -> initial; pin -> ****). `sender_id` per template (default `upay`).
- Encoding (`service/SmsEncoder`): GSM-7 (GSM 03.38 + extension table, unpacked, data_coding 0)
  when every character fits, else UCS-2 (data_coding 8); one SMS up to 160 septets / 70 units,
  else parts of 153 / 67 with the UDH `05 00 03 ref total seq` (esm_class UDHI); escape and
  surrogate pairs never split. One `notify.segment` row per part.
- Dispatch (`service/DispatchService`, `scheduler/DispatchJob` every `mw.notify.dispatch.period`,
  ShedLock): priorities in order OTP > TXN > INFO > PROMO, `dispatch-batch` each per cycle, one
  transaction per message (`FOR UPDATE SKIP LOCKED`). Transient SMPP errors (0x58, 0x14, 0x08,
  timeouts, I/O, no bind) retry after `mw.notify.retry-backoff` (2 s, 10 s, 30 s) on the next bind
  of the pool, then FAILED `MW-SYS-102`; each transient failure is published FAILED final=false
  `MW-SYS-104`; other command statuses fail at once `MW-SYS-101`. Only parts without a message_id
  are resent.
- SMPP (`client/SmppSmsSender`, `client/SmppBind`, jSMPP direct): transceiver binds per priority
  (`mw.notify.smpp.binds`), enquire_link 30 s, reconnect 1-60 s exponential, events
  `smpp.bind.up` / `smpp.bind.down`; submit_sm asks for DLRs. Password from OpenBao
  (`secret/mw-notify`, `mw.notify.smpp.password`, created by `init/init.sh`).
  `mw.notify.smpp.enabled=false` -> `client/DisabledSmsSender` (every send transient).
- DLRs (`messaging/SmppReceiptListener` -> `service/ReceiptService`): matched on the part's
  message_id; DELIVRD -> DELIVERED, EXPIRED -> EXPIRED, UNDELIV/REJECTD/DELETED -> UNDELIVERABLE
  (`MW-SYS-103`); the message is final when all parts have one (worst wins); repeats ignored. A
  DLR before its part is stored waits in `notify.receipt` (applied each cycle, dropped after
  `receipt-buffer-ttl`).
- Status: every attempt and final status to `mw.notify.status.v1` via the outbox
  (`service/StatusEvents`; SENT, FAILED, DELIVERED, UNDELIVERABLE, EXPIRED). `mw-txn-id` header =
  txnId, else messageId.
- Init (`mw-notify-init`, `init/init.sh`, dev only): role `notify_rw`, schema `notify`,
  `secret/mw-notify` (DB and SMPP passwords), AppRole `mw-notify`.

## Contracts owned

- `events/mw-notify/mw.notify.request.v1.schema.json` (consumed; `dto/NotificationRequest`).
- `events/mw-notify/mw.notify.status.v1.schema.json` (produced; `dto/NotifyStatusEvent`).

## Contracts used

- `events/mw-orchestrator/mw.txn.status.v1.schema.json` (own subset type `dto/TxnStatusEvent`).

## Tests

- `service/SmsEncoderTest`, `TemplateRendererTest`, `RuleEngineTest` (units);
  `service/NotificationFlowIT` (PostgreSQL, `client/FakeSmsSender`, `MutableClock`: dedup, expiry,
  priority order, 2/10/30 s retries, DLR outcomes); `SmscFlowIT` (Kafka in, real SMPP to the
  in-test `client/SmscStub`, DLRs, `mw.notify.status.v1` out); `dto/NotifyContractsSchemaTest`.

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
- `application.yml`: Resilience4j defaults (19.2), own schema `notify` migrated by Flyway on
  start from `db/migration` (16.4); rename the schema to the spec 16.2 name if it has one.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | unit, slice and Testcontainers tests (Docker needed for `*IT`), JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-notify:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-notify` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make up M=mw-notify` | run this service's `compose.yml` (platform project must be up) |
