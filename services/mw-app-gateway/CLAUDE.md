# mw-app-gateway

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.app.gateway`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- TODO: what this service owns (spec section).

## Contracts owned

- TODO: files under `contracts/` this service produces or serves (see `contracts/OWNERS.yml`).

## Contracts used

- TODO: files under `contracts/` this service consumes. Write your own types for them.

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
- `application.yml`: Resilience4j defaults (19.2), own schema `app_gateway` migrated by Flyway on
  start from `db/migration` (16.4); rename the schema to the spec 16.2 name if it has one.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | unit, slice and Testcontainers tests (Docker needed for `*IT`), JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-app-gateway:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-app-gateway` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make up M=mw-app-gateway` | run this service's `compose.yml` (platform project must be up) |
