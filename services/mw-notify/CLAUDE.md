# mw-notify

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.notify`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Customer notifications (spec 10, WP17, tickets 24 and 25): SMS over SMPP 3.4 and email over
  SMTP; push, quiet hours and channel fallback come later. One `notify.message` row per requested
  channel, key (message_id, channel); a channel without its address (SMS: msisdn, EMAIL: email)
  or PUSH is recorded FAILED `MW-VAL-101`; a request naming no channel is a FAILED SMS.
- Intake (`service/NotificationService`): `mw.notify.request.v1` (`route/NotifyRequestRoute`,
  `messaging/NotifyRequestListener`) and rules on `mw.txn.status.v1` (`route/TxnStatusRoute`,
  `service/TxnStatusService`, `service/RuleEngine`). `messageId` is the idempotency key (with the
  channel the PK of `notify.message`, duplicates dropped, event `notify.duplicate`); rule-built ids
  are `<txnId>:<sms|email>:<ruleId>`. `expiresAt` defaults by priority (`dto/Priority`: OTP 2 min, TXN 24 h,
  INFO 24 h, PROMO 72 h); an expired message is recorded EXPIRED and never sent. Listeners only
  store; nothing is sent on a Kafka thread.
- Template `CHECKOUT_OTP` (bn/en SMS, `V8`, ticket 31): mw-checkout's OTP, params otp, amount, merchant.
- Templates `DEVICE_BIND_OTP` (param otp, priority OTP) and `DEVICE_BOUND` (no params, TXN) (bn/en SMS, `V9`,
  review A5): mw-app-gateway's device binding OTP and the "a new device was bound" notice.
- Rules (`notify.rule`, seeds in `V5` and `V7`: P2P, CASH_IN, CASH_OUT, TOPUP, BILL_PAY,
  ADD_MONEY_BANK on success COMPLETED, failure FAILED|REJECTED and processing
  UNKNOWN|MANUAL_REVIEW; failure and processing go to the INITIATOR; BILL_PAY and ADD_MONEY_BANK
  success also EMAIL the initiator): `condition` is SpEL in a `SimpleEvaluationContext`
  (read-only getters of `RuleEngine.TxnFacts`: status, txnType, channel, amountMinor,
  initiatorId, debitPartyId, creditPartyId, debitPartyType, creditPartyType; no types, beans or
  constructors); `recipient` INITIATOR|DEBIT_PARTY|CREDIT_PARTY. An SMS rule needs an
  MSISDN-shaped id; an EMAIL rule needs the party's address in `notify.contact` (party_id ->
  email; maintained by the admin console later; dev seed `db/dev/R__dev_contacts.sql`, applied
  only when `MW_NOTIFY_FLYWAY_LOCATIONS` adds `classpath:db/dev`, as the module's compose.yml
  does), else the rule is skipped. Params: amount, fee (taka, 2 decimals), txnId, txnType,
  counterparty (the other side, masked by type: for the INITIATOR the side that is not itself, so
  an ADD_MONEY_BANK shows the bank account), coreRef, partnerRef, partner (partnerId), biller
  (billerCode, else partnerId), errorCode. Locale: the event's, else `mw.notify.default-locale` (bn).
- Templates (`notify.template`, highest ACTIVE version with `valid_from <= now`): logic-less
  `{{name}}` (`service/TemplateRenderer`; sections refused, a missing param fails the message
  `MW-VAL-102`); params masked first by name (`service/ParamMasker`: msisdn, receiver, sender,
  counterparty, agent -> MSISDN; account, accountNumber -> last 4; nid -> last 3; name,
  receiverName, senderName -> initial; pin -> ****; a value already containing `****` is kept).
  `sender_id` per template (default `upay`). EMAIL templates: `subject` and `body` (text part)
  required, `html_body` optional (HTML part, params HTML-escaped, `TemplateRenderer.renderHtml`).
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
- Email (`client/SmtpEmailSender`, Spring `JavaMailSender`, `spring.mail.*`: `SMTP_HOST` /
  `SMTP_PORT`, default GreenMail `greenmail:3025`; STARTTLS required and server identity checked by default (`config/MailTlsDefaultsTest`), off only by the explicit dev override `SMTP_STARTTLS_REQUIRED=false` in compose.yml and the test config;
  From `mw.notify.email.from` / `MAIL_FROM`; SMTP credentials, if any, from OpenBao): one
  multipart/alternative message, SENT is final (no bounce handling yet), providerMessageId = the
  Message-ID. Same retries as SMS (2/10/30 s, `MW-SYS-104` / `MW-SYS-102`); a 5xx reply fails at
  once `MW-SYS-101`. `mw.notify.email.enabled=false` -> `client/DisabledEmailSender`.
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
  (`service/StatusEvents`; SENT, FAILED, DELIVERED, UNDELIVERABLE, EXPIRED; `channel` names the
  row). `mw-txn-id` header = txnId, else messageId.
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
  priority order, 2/10/30 s retries, DLR outcomes); `service/EmailFlowIT` (in-process GreenMail:
  subject, text and HTML parts, SMS+EMAIL request, SMTP down retried); `service/FlowNotificationsIT`
  (every seeded flow state -> exact SMS texts and email receipts, rule/template completeness);
  `SmscFlowIT` (Kafka in, real SMPP to the
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
