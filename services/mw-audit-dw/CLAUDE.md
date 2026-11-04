# mw-audit-dw

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.audit.dw`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Audit trail only in the MVP (spec 14.3, WP10; no DW stream, SFTP export, `audit.payload` or
  search API yet). Consumes `mw.audit.event.v1` (`route/AuditEventRoute` ->
  `messaging/AuditEventListener` -> `service/AuditService.record`) with retry topics and DLQ; an
  unreadable event or one missing a required field throws and ends on the DLQ.
- `audit.event`: append-only, RANGE-partitioned by month on `recorded_at` (the writer's clock,
  UTC), partitions `event_yyyy_MM` created on the month's first event by the SECURITY DEFINER
  function `ensure_month_partition` (unique `seq` index + `month` check per partition). A
  trigger refuses UPDATE/DELETE even for the owner.
- Hash chain per partition (`service/HashChain`): `seq` from 1, `prev_hash` (first row: 32 zero
  bytes), `hash` = SHA-256 over the JSON array `[month, seq, auditId, recordedAt, at, txnId,
  correlationId, actorType, actorId, action, resource, outcome, service, sourceIp, payloadRef,
  before, after, prevHashHex]` (instants ISO-8601 UTC truncated to microseconds; before/after
  in PostgreSQL's `jsonb::text` form, obtained from PostgreSQL before hashing). `HashChainTest`
  pins the format with a golden vector. `audit.chain_head` (month, last seq, last hash) is
  locked `FOR UPDATE` to append (one writer per month at a time) and anchors the tail, so a
  deleted last row is detected too. Daily anchor export to WORM is not built (spec: TBC).
- Verification: `service/AuditChainVerifier.verify(month)` -> `ChainVerification` (intact, or the
  first broken `seq` and why). Not a bean: the service role cannot read `audit.event`; construct
  it with a reading role's `JdbcTemplate` (owner, auditor).
- Dedup: `audit.event_key` (auditId primary key, never purged, INSERT-only for the writer) in the
  same transaction, so a redelivery is refused however late it comes (the chassis inbox is
  purged after 7 d, so it is not used for this).
- Masking (18.3): producers mask; before storing, `MaskingPolicy.scrub` runs again over
  `correlationId`, `actor.id`, `resource`, `payloadRef` and every string in `before`/`after`
  (numbers are not touched). The hash covers the masked values.
- Roles (16.4): the service runs as `audit_writer` (INSERT only on `event` and `event_key`; SELECT/INSERT/UPDATE
  on `chain_head`; EXECUTE `ensure_month_partition`; DML on the chassis `inbox`/`outbox`/
  `shedlock`). Flyway runs as the schema owner `audit_dw_rw` (`SPRING_FLYWAY_USER` +
  `spring.flyway.password` from OpenBao); V4 grants the writer. Nobody holds UPDATE/DELETE on
  `event`. A superuser can still bypass everything, which the chain detects (`AuditTrailIT`).
- Init (`mw-audit-dw-init`, label `mw.oneshot`, `init/init.sh`, idempotent, dev only): roles
  `audit_dw_rw` and `audit_writer`, schema `audit`, `secret/mw-audit-dw` (both DB passwords,
  never overwritten) and the AppRole `mw-audit-dw` (configtree volume). On a fresh stack it waits
  for OpenBao's `dev-init.json` and retries the database step when another init races it.

## Contracts owned

- `events/mw-audit-dw/mw.audit.event.v1.schema.json` (+ samples): own type `dto/AuditEvent`,
  checked by `AuditEventSchemaTest`. Produced by every service, consumed here.

## Contracts used

- None besides its own `mw.audit.event.v1`.

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
- `application.yml`: Resilience4j defaults (19.2), own schema `audit` migrated by Flyway on
  start from `db/migration` (16.4).

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | unit, slice and Testcontainers tests (Docker needed for `*IT`), JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-audit-dw:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-audit-dw` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make up M=mw-audit-dw` | run this service's `compose.yml` (platform project must be up) |
