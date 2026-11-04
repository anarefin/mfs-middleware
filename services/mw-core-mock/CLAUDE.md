# mw-core-mock

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.core.mock`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Test double of the core wallet (spec "Core mock", 6.3, WP06): never ships to production, replaced by
  the real core with no change to the orchestrator.
- Consumes `core.wallet.command.v1` (`route/CoreWalletCommandRoute`, retry topics + DLQ). Handled:
  `ValidateTransaction` (fee, limits, PIN, account status; moves no money) and `PostTransfer`
  (double-entry debit/credit plus fee). Other command types are logged and ignored until their
  tickets land; `core.payment.command.v1` is not consumed yet.
- Emits `TransactionValidated`/`TransactionRejected`/`TransferPosted`/`TransferRejected` on
  `core.wallet.event.v1` through the transactional outbox, in the same transaction as the ledger
  change and the `command_log` row. Idempotent on `commandId`: the first result event is stored
  verbatim and a repeated command gets the same event again (a second outbox row, one ledger effect).
  A command whose `expiresAt` passed before it was first seen is dropped without an event.
- Ledger in schema `coremock` (role `coremock_rw`): `wallet`, `hold`, `entry`, `command_log`.
  Seed (on start, idempotent, `WalletSeeder`): 1,000 customers `01711000001..`, 50 agents
  `01811000001..`, 20 merchants `01911000001..`, 2 orgs `01311000001..` (wallet id = MSISDN) and the
  system wallet `FEE-POOL`. All share the fake seed PIN `1234` (bcrypt). Balances, fees and limits
  are `mw.coremock.*` in `application.yml`; fee is a flat amount per `txnType`, debited from the payer
  on top of the amount; limits are per transaction and per UTC day (sum of transfer debits).
- Reason codes (core reasons, mapped by the orchestrator): `WALLET_NOT_FOUND`,
  `CREDIT_WALLET_NOT_FOUND`, `ACCOUNT_BLOCKED`, `CREDIT_ACCOUNT_BLOCKED`, `SAME_WALLET`,
  `INVALID_PIN`, `INVALID_PIN_BLOCK`, `INVALID_AMOUNT`, `LIMIT_EXCEEDED_PER_TXN`,
  `LIMIT_EXCEEDED_DAILY`, `INSUFFICIENT_BALANCE`. Check order: wallets, status, PIN, amount, limits, funds.
- PIN: RSA-OAEP with SHA-256 (hash and MGF1), PKCS#8 private key from OpenBao
  (`mw.coremock.pin.private-key`), checked against the wallet's bcrypt hash. The plaintext is never
  logged or stored. `GET /core-mock/v1/pin-public-key` exports `{keyId, algorithm, publicKeyPem}`.
- Init (`mw-core-mock-init`, label `mw.oneshot`, `init/init.sh`, idempotent, dev only): creates role
  and schema `coremock`, `secret/mw-core-mock` (DB password, PIN key pair, created once and never
  overwritten) and the AppRole `mw-core-mock`; role-id/secret-id land in a volume the service reads
  as a Spring configtree (`/run/mw/approle`).

## Contracts owned

- `events/mw-core-mock/core.event.v1.schema.json` and its per-event payload schemas (topics
  `core.wallet.event.v1`, `core.payment.event.v1`). Tests validate every emitted event against it.

## Contracts used

- `events/mw-orchestrator/core.command.v1.schema.json` (+ `ValidateTransaction` / `PostTransfer`
  payloads), own types in `dto/CoreCommand`, `dto/TransferPayload`; `common/defs.v1` shapes.

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
- `application.yml`: Resilience4j defaults (19.2), own schema `core_mock` migrated by Flyway on
  start from `db/migration` (16.4); rename the schema to the spec 16.2 name if it has one.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | unit, slice and Testcontainers tests (Docker needed for `*IT`), JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-core-mock:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-core-mock` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make up M=mw-core-mock` | run this service's `compose.yml` (platform project must be up) |
