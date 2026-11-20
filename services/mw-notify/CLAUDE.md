# mw-notify

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.notify`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Customer notifications (spec 10, WP17, tickets 24, 25 and 110): SMS over SMPP 3.4, email over
  SMTP and push to the app (below); channel fallback comes later. One `notify.message` row per requested
  channel, key (message_id, channel); a channel without its address (SMS: msisdn, EMAIL: email, PUSH: a
  device token) is recorded FAILED `MW-VAL-101`; a request naming no channel is a FAILED SMS.
- Push (ticket 110, FRS EXT-04, NOT-01/02/06, FL-14: SMS always, push optional):
  - Token registry `notify.push_device` (`V13`; wallet_id, device_id, token, platform ANDROID|IOS,
    updated_at; `repository/PushDeviceRepository`, `service/PushDeviceService`): fed by
    `mw.notify.push-token.v1` (contract owned here, produced by mw-app-gateway for `PUT
    /app/v1/notifications/push-token`; `messaging/PushTokenListener`, group mw-notify, retry topics + DLQ;
    an unknown platform or a missing field goes to the DLQ). One bound device per wallet: a registration
    replaces the wallet's other devices (and the device's rows under another wallet, and the token
    elsewhere); one older than the stored one is ignored. Re-bind revocation: mw-auth's `DEVICE_BOUND`
    notice (on `mw.notify.request.v1`) carries param `deviceId` (the new device; the SMS shows nothing of
    it); when it is accepted as a new message, `NotificationService` revokes every token of the wallet but
    that device's, and the device's tokens under any other wallet (a device that moved: the old wallet's
    pushes never reach the new holder). The binding is kept in `notify.push_device_binding` (`V14`;
    wallet_id, device_id, bound_at = when the notice was accepted; an older one never replaces a newer one)
    as a revocation tombstone: a registration that arrives later on its own topic but is stamped at or before
    a binding of another device to its wallet, or of its device to another wallet, is ignored
    (`notify.push.token.revoked_late`; ticket 110 security review). Tokens are not personal data but are logged masked to their last 6 characters
    (`SensitiveType.PUSH_TOKEN`).
  - Addressing: a request's `recipient.pushToken`, else the token registered for its `msisdn` (then
    `subjectId`) wallet. Rules with channel PUSH (`RuleEngine.ContactLookup.pushTokenOf`) are skipped when
    the recipient's wallet has no token (no FAILED row). Rule-built ids `<txnId>:push:<ruleId>`.
  - Templates and rules (`V13`): `PUSH_TXN_COMPLETED` and `PUSH_TXN_FAILED` (bn/en; `subject` = the push
    title, `body` the text; params txnType, amount, txnId); rules `push-txn-completed` (COMPLETED) and
    `push-txn-failed` (FAILED|REJECTED) to the INITIATOR for P2P, CASH_IN, CASH_OUT, TOPUP, BILL_PAY,
    MERCHANT_PAY, ADD_MONEY_BANK (`txnType matches '...'`). A PUSH template without a title fails
    `MW-VAL-102`.
  - Client (`client/PushClient`, `config/PushProperties` `mw.notify.push.{enabled (PUSH_ENABLED), base-url
    (PUSH_BASE_URL, default http://mw-simulators:8080), api-key (Vault secret/mw-notify, synced from
    PUSH_API_KEY), connect-timeout 2s, timeout 3s}`): `POST /push/v1/messages {messageId, token, platform,
    notification {title, body}, data {txnId}}` with `Authorization: key=<api-key>`; Resilience4j retry
    `push` (default 3 attempts, 200 ms exponential; `PushRefusedException` ignored) inside breaker `push`
    (refusals ignored). 2xx `status DELIVERED` -> DELIVERED, otherwise (accepted, or an unreadable body)
    SENT; 4xx `PushRefusedException` (404 = token unknown there: the token is dropped, FAILED `MW-VAL-103`;
    other 4xx FAILED `MW-SYS-101`); 5xx / 408 / 429 / timeout / I/O / breaker open / switched off
    `PushSendException` (transient).
  - Dispatch (`service/PushDispatchService`, `scheduler/PushDispatchJob`, ShedLock `notify-push-dispatch`,
    every `mw.notify.dispatch.period`): its own job, so a slow push server never holds an SMS
    (`DispatchService` never takes a PUSH row); priorities in order, PROMO held in the quiet hours. The
    token must still be registered at send time (else FAILED `MW-VAL-103`, nothing sent: a re-bind stops
    queued pushes too). A transient failure retries after `retry-backoff` (FAILED final=false
    `MW-SYS-104`), then FAILED `MW-SYS-102`. Status DELIVERED / SENT / FAILED on `mw.notify.status.v1`
    (channel PUSH; providerMessageId = the push server's id).
- Intake (`service/NotificationService`): `mw.notify.request.v1` (`messaging/NotifyRequestListener`)
  and the notification rules, evaluated by `service/RuleEngine` for each `mw.txn.status.v1` record
  (`messaging/TxnStatusListener`, `service/TxnStatusService`). Both are Spring Kafka `@KafkaListener`s
  (ADR D27) with retry topics and DLQ, stopped by `mw.notify.consumers.enabled=false` (the test default). `messageId` is the idempotency key (with the
  channel the PK of `notify.message`, duplicates dropped, event `notify.duplicate`); rule-built ids
  are `<txnId>:<sms|email|push>:<ruleId>`. `expiresAt` defaults by priority (`dto/Priority`: OTP 2 min, TXN 24 h,
  INFO 24 h, PROMO 72 h); an expired message is recorded EXPIRED and never sent. Listeners only
  store; nothing is sent on a Kafka thread.
- Template `CHECKOUT_OTP` (bn/en SMS, `V8`, ticket 31): mw-checkout's OTP, params otp, amount, merchant.
- Templates `DEVICE_BIND_OTP` (param otp, priority OTP) and `DEVICE_BOUND` (no params, TXN) (bn/en SMS, `V9`,
  review A5): the device binding OTP and the "a new device was bound" notice (sent by mw-auth since ticket 68).
- Template `LOGIN_OTP` (param otp, priority OTP, bn/en SMS, `V11`, ticket 69): mw-auth's first-PIN login OTP.
- Templates `PIN_RESET_OTP` (param otp, priority OTP), `PIN_RESET_APPROVED` and `PIN_CHANGED` (no params, TXN) (bn/en SMS,
  `V12`, ticket 70): mw-auth's PIN reset OTP, the approved care reset and the PIN changed / reset notice.
- Template `OPS_CREDENTIAL_EXPIRING` (bn/en EMAIL, `V15`, ticket 131): mw-admin-api's partner credential
  expiry alert to the operations mailbox (priority INFO; params partnerId, credential, type, kid, notAfter,
  daysLeft, threshold; never a secret).
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
  `mw.config.changed.v1` (compacted; `messaging/ConfigChangedListener`, group mw-notify, retry topics then
  DLQ, tombstones skipped) and
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
  From `mw.notify.email.from` / `MAIL_FROM`; SMTP credentials, if any, from Vault): one
  multipart/alternative message, SENT is final (no bounce handling yet), providerMessageId = the
  Message-ID. Same retries as SMS (2/10/30 s, `MW-SYS-104` / `MW-SYS-102`); a 5xx reply fails at
  once `MW-SYS-101`. `mw.notify.email.enabled=false` -> `client/DisabledEmailSender`.
- SMPP (`client/SmppSmsSender`, `client/SmppBind`, jSMPP direct): transceiver binds per priority
  (`mw.notify.smpp.binds`), enquire_link 30 s, reconnect 1-60 s exponential, events
  `smpp.bind.up` / `smpp.bind.down`; submit_sm asks for DLRs. Password from Vault
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
  `secret/mw-notify` (DB and SMPP passwords, the push API key `mw.notify.push.api-key` synced from
  `PUSH_API_KEY`), AppRole `mw-notify`.

## Contracts owned

- `events/mw-notify/mw.notify.request.v1.schema.json` (consumed; `dto/NotificationRequest`).
- `events/mw-notify/mw.notify.status.v1.schema.json` (produced; `dto/NotifyStatusEvent`).
- `events/mw-notify/mw.notify.push-token.v1.schema.json` (consumed, ticket 110; produced by mw-app-gateway;
  `dto/PushTokenEvent`).

## Contracts used

- `events/mw-orchestrator/mw.txn.status.v1.schema.json` (own subset type `dto/TxnStatusEvent`).
- `events/mw-admin-api/mw.config.changed.v1.schema.json` (ticket 39; own type `dto/ConfigChangedEvent`).
- Machine-readable list: `contracts.yml` (produces `mw.notify.status.v1`; consumes `mw.notify.request.v1`,
  `mw.txn.status.v1`, `mw.config.changed.v1` and `mw.notify.push-token.v1`; no APIs), checked by `./gradlew :contractsLint`, tested by
  `contracts/ProducedContractsTest` (events built by `StatusEvents`) / `ConsumedContractsTest` (samples
  through the real listeners); no Spring, no Docker.

## Tests

- `service/SmsEncoderTest`, `TemplateRendererTest`, `RuleEngineTest` (units); `client/PushClientTest` (retry with
  backoff, no retry of a refusal, stand-in server); `service/PushDispatchIT` (ticket 110: a completed / failed
  txn -> the push text stored by a stand-in push server, bn/en; a 5xx retried then FAILED while the SMS goes; a
  re-bind revokes the old token; an unknown token dropped);
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
