# mw-notify

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.notify`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Customer notifications (spec 10, WP17, tickets 24 and 25): SMS over SMPP 3.4 and email over
  SMTP; push and channel fallback come later. One `notify.message` row per requested
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
  only when `MW_NOTIFY_FLYWAY_LOCATIONS` adds `classpath:db/dev`, as `deploy/docker-compose.yml`
  does), else the rule is skipped. Params: amount, fee (taka, 2 decimals), txnId, txnType,
  counterparty (the other side, masked by type: for the INITIATOR the side that is not itself, so
  an ADD_MONEY_BANK shows the bank account), coreRef, partnerRef, partner (partnerId), biller
  (billerCode, else partnerId), errorCode. Locale: the event's, else `mw.notify.default-locale` (bn).
- Templates (`notify.template`, highest ACTIVE version with `valid_from <= now`): logic-less
  `{{name}}` (`service/TemplateRenderer`; sections refused, a missing param fails the message
  `MW-VAL-102`); params masked first by name (`service/ParamMasker`: msisdn, receiver, sender,
  counterparty, agent -> MSISDN; account, accountNumber -> last 4; nid -> last 3; name,
  receiverName, senderName -> initial; pin -> ****; a value already containing `****` is kept).
  `sender_id` per template (default `Bits`). EMAIL templates: `subject` and `body` (text part)
  required, `html_body` optional (HTML part, params HTML-escaped, `TemplateRenderer.renderHtml`).
- Published templates (ticket 39, spec 10.3, 20.4): mw-admin-api's approved versions arrive on
  `mw.config.changed.v1` (compacted; `route/ConfigChangedRoute`, a `RetryTopicRouteBuilder`, group
  mw-notify, retry topics then DLQ; `messaging/ConfigChangedListener`, tombstones skipped) and
  `service/TemplatePublisher` takes configType TEMPLATE UPSERTs (value `{code, channel, locale,
  version, body, subject?, htmlBody?, senderId?, validFrom}`; other types / DELETE ignored): one
  insert of the next ACTIVE version (numbered here; the admin version in
  `notify.template.config_version`, V10, unique per template: redelivery stored once, an older one
  ignored) valid from `validFrom`. Rendering takes the highest ACTIVE version valid now, so the swap
  is atomic and the next message uses it. A value it could not render (no body, EMAIL without
  subject, a placeholder that is not a plain `{{name}}`) is logged `notify.template.rejected` and
  dropped. Events `notify.template.published`, `notify.template.publish_skipped`
  (`TemplatePublisherIT`, on test-only codes `IT_TPL_*`).
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
  are resent. PROMO quiet hours (spec 10.1, review B14; `mw.notify.quiet-hours`: enabled, start,
  end, zone, default 22:00-08:00 Asia/Dhaka, env `MW_NOTIFY_QUIET_HOURS_*`; end before start spans
  midnight): PROMO is held while quiet (only its expired rows are taken, recorded EXPIRED, never
  sent) and goes after; other priorities are not affected.
- Email (`client/SmtpEmailSender`, Spring `JavaMailSender`, `spring.mail.*`: `SMTP_HOST` /
  `SMTP_PORT`, default GreenMail `greenmail:3025`; STARTTLS required and server identity checked by default (`config/MailTlsDefaultsTest`), off only by the explicit dev override `SMTP_STARTTLS_REQUIRED=false` (service `mw-notify` in `deploy/docker-compose.yml`) and the test config;
  From `mw.notify.email.from` / `MAIL_FROM`; SMTP credentials, if any, from OpenBao): one
  multipart/alternative message, SENT is final (no bounce handling yet), providerMessageId = the
  Message-ID. Same retries as SMS (2/10/30 s, `MW-SYS-104` / `MW-SYS-102`); a 5xx reply fails at
  once `MW-SYS-101`. `mw.notify.email.enabled=false` -> `client/DisabledEmailSender`.
- SMPP (`client/SmppSmsSender`, `client/SmppBind`, jSMPP direct): transceiver binds per priority
  (`mw.notify.smpp.binds`), enquire_link 30 s, reconnect 1-60 s exponential, events
  `smpp.bind.up` / `smpp.bind.down`; submit_sm asks for DLRs. Password from OpenBao
  (`secret/mw-notify`, `mw.notify.smpp.password`, created by `deploy/provision/services.sh`).
  `mw.notify.smpp.enabled=false` -> `client/DisabledSmsSender` (every send transient).
- DLRs (`messaging/SmppReceiptListener` -> `service/ReceiptService`): matched on the part's
  message_id; DELIVRD -> DELIVERED, EXPIRED -> EXPIRED, UNDELIV/REJECTD/DELETED -> UNDELIVERABLE
  (`MW-SYS-103`); the message is final when all parts have one (worst wins); repeats ignored. A
  DLR before its part is stored waits in `notify.receipt` (applied each cycle, dropped after
  `receipt-buffer-ttl`).
- Status: every attempt and final status to `mw.notify.status.v1` via the outbox
  (`service/StatusEvents`; SENT, FAILED, DELIVERED, UNDELIVERABLE, EXPIRED; `channel` names the
  row). `mw-txn-id` header = txnId, else messageId.
- Dev provisioning (`provision_mw_notify` in `deploy/provision/services.sh`, run by the one-shot `provision` on every `docker compose up`, idempotent, dev only): role `notify_rw`, schema `notify`,
  `secret/mw-notify` (DB and SMPP passwords), AppRole `mw-notify`.

## Contracts owned

- `events/mw-notify/mw.notify.request.v1.schema.json` (consumed; `dto/NotificationRequest`).
- `events/mw-notify/mw.notify.status.v1.schema.json` (produced; `dto/NotifyStatusEvent`).

## Contracts used

- `events/mw-orchestrator/mw.txn.status.v1.schema.json` (own subset type `dto/TxnStatusEvent`).
- `events/mw-admin-api/mw.config.changed.v1.schema.json` (ticket 39; own type `dto/ConfigChangedEvent`).
- Machine-readable list: `contracts.yml` (produces `mw.notify.status.v1`; consumes `mw.notify.request.v1`,
  `mw.txn.status.v1` and `mw.config.changed.v1`; no APIs), checked by `./gradlew :contractsLint`, tested by
  `contracts/ProducedContractsTest` (events built by `StatusEvents`) / `ConsumedContractsTest` (samples
  through the real listeners); no Spring, no Docker.

## Tests

- `service/SmsEncoderTest`, `TemplateRendererTest`, `RuleEngineTest` (units);
  `service/NotificationFlowIT` (PostgreSQL, `client/FakeSmsSender`, `MutableClock`: dedup, expiry,
  priority order, 2/10/30 s retries, DLR outcomes, PROMO quiet hours); `config/QuietHoursTest`; `service/EmailFlowIT` (in-process GreenMail:
  subject, text and HTML parts, SMS+EMAIL request, SMTP down retried); `service/FlowNotificationsIT`
  (every seeded flow state -> exact SMS texts and email receipts, rule/template completeness);
  `SmscFlowIT` (Kafka in, real SMPP to the
  in-test `client/SmscStub`, DLRs, `mw.notify.status.v1` out); `dto/NotifyContractsSchemaTest`;
  `contracts/ProducedContractsTest`, `ConsumedContractsTest`. Spring contexts with Testcontainers are
  `*IT` (`NotifyApplicationIT`, `config/MwServicePropertiesIT`, `logging/JsonLoggingIT`).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (written inline in
  `build.gradle.kts`; drop once the Boot / Camel BOMs ship them); the shared dev provisioning image `deploy/provision/Dockerfile` runs `apk upgrade` and removes gosu. `scripts/security.sh <this module>` scans the SBOM and the
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
- `application.yml`: Resilience4j defaults (19.2), own schema `notify` migrated by Flyway on
  start from `db/migration` (16.4); rename the schema to the spec 16.2 name if it has one.

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-notify:test` | every test in one JVM: unit, slice and contract tests (`contracts/*ContractsTest`), the Testcontainers `*IT` classes and the `@Conformance` suite; one PostgreSQL and one Kafka per test JVM, a fresh database per Spring context; the `*IT` and conformance classes are skipped without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-notify:build` | `test`, then the boot jar `build/libs/mw-notify.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-notify:bootJar && docker build -t mw-notify:dev services/mw-notify` | image, as spec 4.3 rule 1 |
| `./gradlew clean build` | the full gate: every module, plus the repo checks (`:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`); unchanged modules come from the build cache |
| `./gradlew :mw-notify:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-notify` | rebuild this service's image (`mfs-mw/mw-notify:local`) and restart it with what it depends on (dev timers from `deploy/.env`) |
