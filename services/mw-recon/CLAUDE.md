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
- Reports (ticket 127, REC-05, REC-06): `service/ReportService` totals count and amount per class from
  `match_result` for a partner and Asia/Dhaka business dates (DAILY = MT940 and partner-file runs, INTRADAY =
  MT942 runs), rendered as CSV (formula cells defused), XLSX (Apache POI) or PDF (OpenPDF);
  `GET /internal/v1/recon/reports?partner=&from=&to=&kind=&format=`. `scheduler/DailyReportJob`
  (`mw.recon.report-cron`, default 02:00) stores yesterday's report per partner in `recon.report`; a later
  DAILY run for that day re-stores it (`ReportService.refreshStored`, REC-06).
- Trust position (REC-07): the MT940 closing balance (62F) is stored on `source_file` with its 62F date
  (`V7` `closing_date`; the position is keyed by that date, never by the lines' value dates);
  `service/TrustPositionService` compares it with the e-money total, stores `recon.trust_position` and
  raises `AMOUNT_MISMATCH` detail `TRUST_POSITION` (owner `finance`) on a gap. A re-run for the same day reuses
  the day's OPEN or EXPLAINED exception (an EXPLAINED one whose gap changed goes back to OPEN without its
  explanation) and a clean re-run resolves it as superseded; its event's `amountMinor` is the gap. `scheduler/TrustPositionJob`
  (ticket 127c; `mw.recon.trust.position-cron`, default 00:45 Asia/Dhaka) computes yesterday's position when its
  MT940 closing balance is in (WARN `recon.trust_position.no_statement` when not, and
  `recon.trust_position.missed` for every day without a position since the last one, at most 31 days: reported, not
  computed, since the core's e-money total is a current figure; ShedLock `lockAtLeastFor` PT5M), with the core's `GET /core/v1/reports/emoney-total` read by `client/CoreEmoneyClient`
  (read only; `client/ServiceTokenClient`: realm `services` client `mw-recon`, mapper `aud-mw-core-mock`,
  `mw.recon.core.*`, secret from Vault `mw.recon.core.client-secret` / `KC_MW_RECON_SECRET`; renewed once on 401
  `MW-AUTH-001`, anything else 503 `MW-SYS-002`). Dev/test `POST /test/v1/trust-position?date=` runs it (default
  the latest closing date; none 404 `MW-VAL-003`). Open item for Upay: the real core must serve the endpoint.
- Work queue (UC-22): `GET /internal/v1/recon/exceptions` (class, partner, state, minAgeHours, limit),
  `GET .../{id}` with a suggested action (advice only; FORCE_COMMIT, REVERSE, RAISE_DISPUTE, ACCEPT_AS_EXPLAINED;
  the MT940 `:86:` narrative and the explanation are scrubbed by `MaskingPolicy` and cut to 255 in the view and the
  events), `POST .../{id}/explanations` (OPEN or EXPLAINED -> EXPLAINED,
  audited on `mw.audit.event.v1` as `recon.exception.explained`; ACTIONED or RESOLVED is refused with 409
  `MW-VAL-006`; FRS 10.4 #7: every difference resolved or explained). The operator comes from
  `X-Mw-Operator-Id` as for reprocess, asserted by mw-admin-api. Exception states: OPEN, EXPLAINED, ACTIONED,
  RESOLVED. An action is never taken here: it is a maker-checker change request in mw-admin-api.
- Exception actions (ticket 127b): mw-admin-api's `recon_exception` change request, once a recon-checker
  approved it and mw-admin-api sent the ticket 37 message (FORCE_COMMIT on `mw.txn.action.v1`, REVERSE as a
  REVERSAL on `mw.txn.request.v1`), calls `POST /internal/v1/recon/exceptions/{id}/actions {action,
  changeRequestId}` (operator header = the checker; idempotent on changeRequestId): OPEN or EXPLAINED with a
  transaction -> ACTIONED (`V6`: action, change_request_id, actioned_by/at, expected_status COMPLETED for
  FORCE_COMMIT, REVERSED for REVERSE); anything else 409 `MW-VAL-006`. `ReconService.resolveAwaiting`: the
  transaction reaching the expected status on `mw.txn.status.v1` (or already projected so when the action
  arrives) -> RESOLVED (`resolved_at`). A newer status event with another final status (COMPLETED, FAILED,
  REJECTED, REVERSED, MANUAL_REVIEW; `ReconService.reopenFailed`) means the action failed: ACTIONED -> OPEN (WARN
  `recon.exception.action_failed`; action and change request kept as history), so it can be explained or actioned
  again. An intermediate status (COMMITTING, REVERSING) leaves it ACTIONED.
- Events (ticket 127b, FRS DAT-01 `recon.exception`): `service/ExceptionEvents` writes one
  `mw.recon.exception.v1` per state change (OPEN when raised by a run or the trust position, EXPLAINED,
  ACTIONED, RESOLVED) to the outbox in the same transaction, key the exception id. Consumers mw-audit-dw (audit
  trail) and mw-data-export (DW, local shape).

- Caller authentication (ticket 127b security review; `config/SecurityConfig`, `config/InternalAuthProperties`
  `mw.internal-auth.*`, copied from mw-core-mock): `/internal/**` needs a realm `services` client-credentials JWT
  (JWKS `MW_INTERNAL_JWK_SET_URI`, issuer `MW_INTERNAL_ISSUER`, `aud` containing mw-recon: mapper `aud-mw-recon` on
  client mw-admin-api in `deploy/keycloak/realms/services-realm.json`) whose `azp` is in `allowed-clients`
  (mw-admin-api); none or bad 401 `MW-AUTH-001`, another client 403 `MW-AUTH-002`. The actuator and the dev-only
  `/test/v1/**` stay open. Tests `config/InternalApiSecurityIT`, tokens from `security/InternalTokens`.

## Contracts owned

- `contracts/events/mw-recon/mw.recon.result.v1.schema.json`
- `contracts/events/mw-recon/mw.recon.exception.v1.schema.json` (ticket 127b)
- `contracts/api/mw-recon/recon.v1.openapi.yaml`
- `contracts/api/mw-recon/recon-test.v1.openapi.yaml`

## Contracts used

- `contracts/events/mw-adapter-billpay/mw.partner.recon.v1.schema.json` (family stays the adapter's enum;
  a future family is accepted as text)
- `contracts/events/mw-orchestrator/mw.txn.status.v1.schema.json`
- `contracts/events/mw-core-mock/core.event.v1.schema.json` (`#TransferPosted`, `#WalletCredited`,
  `#HoldCommitted`, `#TransactionReversed`)
- `contracts/events/mw-audit-dw/mw.audit.event.v1.schema.json` (produced on reprocess; mw-audit-dw consumes it)
- `contracts/api/mw-core-mock/core-wallet.v1.openapi.yaml`: only `GET /core/v1/reports/emoney-total` (ticket 127c)

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
