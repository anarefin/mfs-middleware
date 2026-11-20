# mw-recon

Statement and settlement reconciliation (spec 13, ticket 126, FRS REC-01..04, REC-08, EXT-06). Package
`mfs.mw.recon`. It reports a class. It never posts money and never changes a transaction.

## Responsibilities

- Pull the trust bank's daily MT940 and intraday MT942 from SFTP (sshj, Resilience4j `trust-sftp`) and
  parse them with Prowide Core. Partner settlement files stay with the adapters (D28) and arrive on
  `mw.partner.recon.v1`.
- Project `mw.txn.status.v1` and the core posting events (`TransferPosted`, `WalletCredited`,
  `HoldCommitted`, `TransactionReversed`) into schema `recon`. Never read another service's schema.
- Match on partner ref, then reference (a statement line's reference is the txn id). Tolerance is
  absolute poisha, default 0. Date window is ± N days. Classes: `MATCHED`, `MISSING_IN_PARTNER`
  (a completed partner file only; an MT942 does not raise it), `MISSING_IN_UPAY`, `AMOUNT_MISMATCH`,
  `STATUS_MISMATCH`. A completed transaction with no posting is `STATUS_MISMATCH` detail
  `LEDGER_MISSING`. A statement does not raise `MISSING_IN_PARTNER` for every completed transaction:
  core events do not say which wallet moved, and the file is the settlement wallet.
- Publish one `mw.recon.result.v1` per run (key `partnerId`). A file is registered by filename and
  sha256 and skipped the second time. `POST /internal/v1/recon/files/{id}/reprocess` with
  `X-Mw-Operator-Id` runs it again and writes `mw.audit.event.v1`.

## Contracts owned

- `contracts/events/mw-recon/mw.recon.result.v1.schema.json`
- `contracts/api/mw-recon/recon.v1.openapi.yaml`
- `contracts/api/mw-recon/recon-test.v1.openapi.yaml`

## Contracts used

- `contracts/events/mw-adapter-billpay/mw.partner.recon.v1.schema.json` (family stays the adapter's enum;
  a future family is accepted as text)
- `contracts/events/mw-orchestrator/mw.txn.status.v1.schema.json`
- `contracts/events/mw-core-mock/core.event.v1.schema.json` (`#TransferPosted`, `#WalletCredited`,
  `#HoldCommitted`, `#TransactionReversed`)
- `contracts/events/mw-audit-dw/mw.audit.event.v1.schema.json` (produced on reprocess; mw-audit-dw consumes it)

`contracts.yml` lists no command topic and no core write API.

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
- `application.yml`: Resilience4j defaults (19.2), own schema `recon` migrated by Flyway on
  start from `db/migration` (16.4); rename the schema to the spec 16.2 name if it has one.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew :mw-recon:test` | every test in one JVM: unit, slice, contract, Testcontainers `*IT` and `@Conformance` (`*IT` skipped without Docker) |
| `./gradlew :mw-recon:build` | the tests plus the boot jar `build/libs/mw-recon.jar` |
| `./gradlew :mw-recon:bootJar && docker build -t mfs-mw/mw-recon:local services/mw-recon` | image, as spec 4.3 rule 1 |
