# mw-adapter-telco

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.adapter.telco`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Thin telco adapter (spec WP14 part, 7.3 rule 5, 7.4, 11): consumes `mw.partner.request.telco.v1`,
  maps a `PAY` / `TOPUP` request to the OCS REST/JSON wire format of the OCS simulator
  (`POST /ocs/v1/topup {transactionId, msisdn, amount:"20.00"}`) and publishes
  `mw.partner.reply.v1` through the outbox (inbox-deduplicated on `requestId`).
- Outcomes (`mapper/PartnerReplyMapper`): SUCCESS (`partnerRef` = OCS ref, `attributes.partnerTxnRef`
  = our reference); FAILED with `MW-PTR-<PARTNER>-<suffix>` (OCS code map in `mw.telco.ocs.error-codes`,
  unlisted -> `UNMAPPED`); FAILED `MW-SYS-PARTNER_{UNAVAILABLE,CIRCUIT_OPEN,BUSY}` when the call never left
  (retryable); UNKNOWN `MW-TMO-PARTNER_{TIMEOUT,CONNECTION_RESET,HTTP_5XX,MALFORMED_REPLY,AMBIGUOUS_CODE}`
  (never retryable by itself: status inquiry resolves it); FAILED `MW-VAL-INVALID_PARTNER_REQUEST`
  for requests that cannot be mapped (wrong family/operation, amount <= 0, credit party not an MSISDN).
- Stable partner reference (`service/PartnerReferenceService`, table `partner_reference` in schema
  `partner_ref`, spec 4.1): `UPAY` + txnId hex, upper case, stored on first use. Every request for the same
  txnId sends the same reference; the OCS returns the original outcome for a repeated one. The
  orchestrator's `partnerTxnRef` is ignored.
- `client/OcsClient`: Resilience4j instance `ocs` (bulkhead 100, circuit breaker, time limiter 20 s;
  see `application.yml`). Exactly one HTTP attempt per call: never add `@Retry` to the top-up. Only
  transport errors, 5xx, timeouts and unreadable replies count towards the circuit; business rejections do not.
- The OCS call runs on a virtual-thread executor (`telcoCallExecutor`), never on the Kafka consumer thread.
  The offset is committed when the request is handed over; a crash before the reply is written leaves
  the transaction without a reply and the orchestrator's deadline turns it UNKNOWN (TSQ, spec 7.4).
- Not yet: status inquiry (`STATUS_QUERY`), callbacks, per-partner config (ticket 20, adapter framework).
  Any `partnerId` in the request is served by the single static OCS endpoint.

## Contracts owned

- None. (The partner topics belong to mw-orchestrator; the OCS wire format belongs to mw-simulators.)

## Contracts used

- `events/mw-orchestrator/mw.partner.request.v1` (family `telco`, shared payload) in; own type
  `dto/PartnerRequest`.
- `events/mw-orchestrator/mw.partner.reply.v1` (family `telco`, shared payload) out; own type
  `dto/PartnerReply`. `dto/PartnerMessagesSchemaTest` keeps both aligned with the samples.

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
- `application.yml`: Resilience4j defaults (19.2), own schema `partner_ref` migrated by Flyway on
  start from `db/migration` (16.4); rename the schema to the spec 16.2 name if it has one.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | unit, slice and Testcontainers tests (Docker needed for `*IT`), JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-adapter-telco:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-adapter-telco` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make up M=mw-adapter-telco` | run this service's `compose.yml` (platform project must be up) |
