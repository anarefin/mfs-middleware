# mw-simulators

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.simulators`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Partner simulators for tests only (spec 22.2, WP12), one generic contract per partner type.
  Today: OCS (prepaid top-up). State is in memory (a restart resets it); never deployed to
  production projects.
- Shared scenario mechanics (`service/ScenarioStore`, `RequestRecorder`, `CallbackService`):
  - Magic values (OCS): MSISDN ending `999` -> business failure; amount fraction `.13` ->
    timeout (top-up applied, reply held `sim.timeout-delay`, default 30 s), `.77` -> 500 after
    send (applied), `.99` -> unknown code `9876`, `.55` -> malformed body (applied).
  - Control API per partner (`sim.partners`): `POST|GET|DELETE /sim/{partner}/scenario`
    (`forceMode`, `latencyMs`, `errorRate`, `outage`, `timeoutDelayMs`, `callbackMode`
    NONE|ONCE|DUPLICATE|LATE, `callbackUrl`, `callbackDelayMs`), `GET|DELETE
    /sim/{partner}/requests[?path=]`, `POST /sim/{partner}/callbacks/{reference}/replay`
    (optional body `{callbackUrl}`).
  - `filter/RecordingFilter` records every request to `/{partner}/**` (raw bodies, test data;
    Authorization and Cookie headers dropped), last `sim.record-limit` per partner.
- OCS wire format: `POST /ocs/v1/topup {transactionId, msisdn, amount:"100.00"}`; `GET
  /ocs/v1/topup/{transactionId}` returns the real outcome (200, or 404 NOT_FOUND). Replies:
  `{transactionId, status SUCCESS|FAILED|NOT_FOUND|UNAVAILABLE|ERROR, code, message, ocsRef}`;
  success 200 code `0000`, failure 422 (`2001` business, `9876` unknown), outage / error rate 503
  (not applied). A repeated `transactionId` returns the original outcome.
- Adding a simulator: add its name to `sim.partners`, a controller under `/<partner>/...`, reuse
  the store, recorder and callback service.
- Known gap: the chassis still migrates a schema on start-up (Flyway); in the Compose stack the
  `simulators_rw` role is not provisioned by the platform yet (same gap as every service).

## Contracts owned

- None. The control API and partner wire formats are simulator-owned and not in `contracts/`.

## Contracts used

- None. (Later tickets add the core stub: `core.*.command` in, `core.*.event` out, spec 22.2.)

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
- `application.yml`: Resilience4j defaults (19.2), own schema `simulators` migrated by Flyway on
  start from `db/migration` (16.4); rename the schema to the spec 16.2 name if it has one.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | unit, slice and Testcontainers tests (Docker needed for `*IT`), JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-simulators:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-simulators` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make up M=mw-simulators` | run this service's `compose.yml` (platform project must be up) |
