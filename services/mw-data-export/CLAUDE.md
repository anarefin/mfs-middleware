# mw-data-export

Service module (spec 4.2, 4.3), a subproject of the root build. Package `mfs.mw.data.export`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Warehouse stream and the daily SFTP batch (ticket 128, spec 17, FRS EXT-17, DAT-01..04). No Camel:
  sshj uploads to `dw/in`, Resilience4j retries the upload and the core snapshot read.
- PII is deny-by-default (`service/PiiPolicy`). CLEAR, MASK or TOKENISE per field. Tokenise calls
  mw-crypto. A clear MSISDN is not published and not stored.
- Online facts older than `mw.archive.online.*` are gzipped under `/var/lib/mw/dw-archive` and deleted.
  Terminal saga rows and the audit transaction index are purged by the owning services from
  `mw.retention.*` (one duration per type). Audit events and payloads stay append-only.

## Contracts owned

- `contracts/events/mw-data-export/dw.*.schema.json` and
  `contracts/api/mw-data-export/data-export.v1.openapi.yaml`,
  `data-export-test.v1.openapi.yaml`.

## Contracts used

- `mw.txn.status.v1`, `mw.notify.status.v1`, `mw.audit.event.v1`, and mw-crypto
  `POST /internal/v1/tokens`. `mw.recon.exception.v1` and `mw.kyc.completed.v1` are consumed with a
  local shape; their schemas arrive with tickets 127 and 124.
- `contracts.yml` is the machine-readable list, checked by `./gradlew :contractsLint`.

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field.
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
  Add context with `log.atInfo().addKeyValue("event", "...")` or the MDC.
- `config/MwServiceProperties`: config binding; secrets from Vault when `VAULT_ENABLED=true`.
- `messaging/EventPublisher`: Kafka records with the 6.1 headers through Spring Kafka's `KafkaTemplate`,
  idempotent `acks=all` producer (ADR D27).
- `config/KafkaConfig`: Spring Kafka retry topics for every `@KafkaListener`: `<topic>.<group>.retry.1..3`
  (10 s / 1 min / 10 min) then `<topic>.<group>.dlq` (19.2, declare them in `deploy/kafka/topics.txt`);
  listeners take `ConsumerRecord<String, String>` and `Acknowledgment` and acknowledge after handling
  (per-record commit); no sleeps or long work on Kafka threads.
- `scheduler/OutboxRelayScheduler`: relays the transactional outbox (`messaging/OutboxRelay`, 6.4).
- `application.yml`: Resilience4j defaults (19.2), own schema `data_export` migrated by Flyway on
  start from `db/migration` (16.4); rename the schema to the spec 16.2 name if it has one.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew :mw-data-export:test` | every test in one JVM: unit, slice, contract, Testcontainers `*IT` and `@Conformance` (`*IT` skipped without Docker) |
| `./gradlew :mw-data-export:build` | the tests plus the boot jar `build/libs/mw-data-export.jar` |
| `./gradlew :mw-data-export:bootJar && docker build -t mfs-mw/mw-data-export:local services/mw-data-export` | image, as spec 4.3 rule 1 |
