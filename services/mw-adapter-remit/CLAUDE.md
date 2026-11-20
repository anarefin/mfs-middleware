# mw-adapter-remit

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.adapter.remit`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## PCI rules (FRS FL-07; read first)

- **Remit data never enters the middleware.** The customer types the remit on the acquirer's hosted page
  (dev: mw-simulators `CyberSourceMock`, `/cybersource/hp/<sessionId>`). This adapter, its tables, logs,
  audit and every contract it uses carry only the acquirer's session id and transient token. No field named
  like a remit number, CVV or expiry may appear in a contract or DTO: `contracts/NoCardDataTest` fails the
  build otherwise, and `mapper/PartnerCallMapper` refuses (FAILED `MW-VAL-INVALID_PARTNER_REQUEST`, nothing
  sent) a request whose `attributes` name one (`pan`, `remitNumber`, `cvv`, `expiry...`).
- The transient token is a secret in the partner audit (`sensitive-fields: transientToken: SECRET`).
- The wallet is credited only after a SUCCESS of AUTHORISE_CAPTURE (the orchestrator flow, ticket 118b). A
  decline or a 3-D Secure failure is FAILED (no money moved); a capture timeout, a 5xx or an unreadable
  answer is UNKNOWN, resolved by STATUS_QUERY, never by an automatic credit.

## Responsibilities

- Remit acquiring adapter (ticket 118a, FRS UC-13, EXT-14, FL-07, PLT-014; ADR D32 decision 5: one service
  per party). Copied from mw-adapter-merchant (itself the bank copy; the engine's callback support stays in
  the copy unused: the 3-D Secure webhook endpoint comes with ticket 118b). Consumes
  `mw.partner.request.remit.v1` (`messaging/RemitRequestHandler`, a Spring Kafka listener, retry topics +
  DLQ, `mw.remit.request-consumer.enabled`), checks the request (`mapper/PartnerCallMapper`: family `remit`,
  txnType `REMIT_IN`, a partner, credit party the customer's `WALLET`, a positive amount except for
  STATUS_QUERY, no remit data), calls the acquirer once through the partner engine with our stable
  reference and publishes `mw.partner.reply.v1` through the outbox (inbox-deduplicated on `requestId`).
  The family's operations sit on the canonical ones of `mw.partner.request.v1`:
  - **CREATE_SESSION** = `INQUIRY`: the hosted page session for the amount (the wallet is not sent).
    SUCCESS `partnerRef` = session id, `attributes {sessionId, hostedPageUrl}`.
  - **AUTHORISE_CAPTURE** = `PAY`: `attributes {sessionId, transientToken}` (from the 3-D Secure webhook),
    authorise + capture in one call. SUCCESS `partnerRef` = the payment id.
  - **STATUS_QUERY**: the true outcome of our reference (NOT_FOUND on the acquirer's 404).
  - **REFUND** = `REVERSE`: refund of the captured payment, idempotent on the engine's `reversalReference`
    (`reverse:<txnId>`).
  `service/RemitPartnerService` is the copy of bank's debit service; outcomes and safety rules are
  telco's/billpay's (see mw-adapter-billpay CLAUDE.md): FAILED `MW-PTR-CYBERSOURCE-<suffix>` from the
  record's `error-map`, FAILED `MW-SYS-PARTNER_*` when nothing left, UNKNOWN `MW-TMO-PARTNER_*`, a PAY past
  its `deadlineAt` refused unsent, `MW-PTR-UNKNOWN-PARTNER` for an unknown partner id (no aliases).
- Stable partner reference (`service/PartnerReferenceService`, table `partner_reference` in own schema
  `remit_ref`): `BITS` + txnId hex, stored on first use (the acquirer's `clientReference`).
- The partner call runs on a virtual-thread executor (`remitCallExecutor`), never on the Kafka consumer thread.
- Partner-call audit (S6): `PartnerAudit.publishing` -> `messaging/PartnerAuditPublisher` ->
  `mw.audit.event.v1` through the outbox, as billpay; full masked bodies in `partner_payload`.
- Dev provisioning (`provision_mw_adapter_remit` in `deploy/provision/services.sh`, idempotent, dev only):
  role `adapter_remit_rw` and schema `remit_ref`, `secret/mw-adapter-remit` (DB password, never overwritten),
  `secret/mw-adapter-remit/cybersource` (field `hmac-secret`, synced from `CYBERSOURCE_HMAC_SECRET`; the same
  value is mw-simulators' `sim.callback-secrets.cybersource`) and the AppRole `mw-adapter-remit`. Compose
  service `mw-adapter-remit` (profile `core`, 384 MiB, Hikari pool 2, `MW_PORT_ADAPTER_REMIT` 8099,
  `CYBERSOURCE_BASE_URL`).

## Partner engine (`partner` package; ADR D28: the copy of mw-adapter-billpay's)

The engine, its guardrails and its Kamelets are billpay's (mw-adapter-billpay CLAUDE.md "Partner engine").
The main `partner/` tree, `src/main/resources/kamelets` and `partner/` resources are identical in the five
adapters apart from the package (`scripts/test/test_partner_engine_copies.sh`): a fix goes into all five.

- **Seed** `cybersource` (`application-dev.yml`, dev profile only; the only dev seed): REST/JSON, every call
  `hmac-sha256` (`X-Signature`, `X-Timestamp`, secret field `hmac-secret`), one branch per operation:
  INQUIRY `POST /cybersource/v1/sessions {clientReference, amount, currency}` -> 201 `{status CREATED,
  sessionId, hostedPageUrl}`; PAY `POST /cybersource/v1/payments {clientReference, sessionId,
  transientToken, amount, currency}` -> 201 `{status CAPTURED, paymentId, code}`; STATUS_QUERY `GET
  /cybersource/v1/payments/{reference}` -> 200 `{status CAPTURED|DECLINED|PENDING, ...}` or 404 `NOT_FOUND`;
  REVERSE `POST /cybersource/v1/payments/{reference}/refunds {refundId, amount, currency}` -> 201 `{status
  REFUNDED, refundId}`. A 400/422 with a code is FAILED through the error map (`4001` CARD_DECLINED, `4002`
  THREE_DS_FAILED, `4003` INSUFFICIENT_FUNDS, `4004` REFUND_REFUSED, `4005` SESSION_EXPIRED; `9876`
  ambiguous); anything else UNKNOWN. Base URL `CYBERSOURCE_BASE_URL`, hosts `MW_CYBERSOURCE_ALLOWED_HOSTS`
  (floor in `application.yml`), timeout 10 s, resilience4j instance `cybersource` (8 s slow call, bulkhead
  100). Test: `route/CybersourceRouteIT` (WireMock). mw-admin-api's copy of the seed comes with 118b.
- **Test-only partner** `c2wbank` (`src/test/resources/config/application-dev.yml`): the REST
  async-callback record of mw-adapter-bank, kept so the shared engine tests and `RemitRequestRouteIT`
  (request handling, outcomes, circuit breaker, audit) run unchanged.
- **Family** `remit` (mw-admin-api accepts it since ticket 118, Flyway V18); **route status** on
  `mw.partner.route.status.v1`, batch SFTP routes on `mw.partner.recon.v1`, as billpay.

## Contracts owned

- None.

## Contracts used

- Machine-readable list: `contracts.yml` (checked by `./gradlew :contractsLint`, tested by
  `contracts/ProducedContractsTest` / `ConsumedContractsTest`, and `contracts/NoCardDataTest`). No APIs.
- `events/mw-orchestrator/mw.partner.request.v1` (family `remit`, shared payload; samples
  `samples/mw.partner.request.v1/remit`) in; own type `dto/PartnerRequest`. The orchestrator produces it
  with ticket 118b.
- `events/mw-orchestrator/mw.partner.reply.v1` (family `remit`) out; own type `dto/PartnerReply`.
- `events/mw-audit-dw/mw.audit.event.v1` out (`partner.call.completed`).
- `events/mw-admin-api/mw.config.changed.v1` in (PARTNER route records, family `remit`).
- `events/mw-adapter-billpay/mw.partner.route.status.v1` and `mw.partner.recon.v1` out (owner
  mw-adapter-billpay; family `remit` added to their enums in ticket 118).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (written inline in
  `build.gradle.kts`; drop once the Boot / Camel BOMs ship them). `scripts/security.sh <this module>` scans the SBOM and the
  `mfs-mw/*:local` images (HIGH/CRITICAL fail; accepted ones in `.trivyignore.yaml` with a reason and expiry).
- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3 (+ `PARTY_ID`).
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
- `config/MwServiceProperties`: config binding; secrets from Vault when `VAULT_ENABLED=true`.
- Spring Kafka (ADR D27, the billpay copy): `config/KafkaConfig` (retry topics `<topic>.<group>.retry.1..3`
  10 s / 1 min / 10 min then `.dlq`), `messaging/EventPublisher`, outbox/inbox, `scheduler/OutboxRelayScheduler`,
  `partner/config/PartnerConfigKafkaListener` (replays `mw.config.changed.v1` on every start).
- `application.yml`: Resilience4j defaults (19.2), own schema `remit_ref` migrated by Flyway.

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-adapter-remit:test` | every test in one JVM: unit, slice and contract tests, the Testcontainers `*IT` classes and the `@Conformance` suite; the `*IT` and conformance classes are skipped without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-adapter-remit:build` | `test`, then the boot jar `build/libs/mw-adapter-remit.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-adapter-remit:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-adapter-remit` | rebuild this service's image (`mfs-mw/mw-adapter-remit:local`) and restart it with what it depends on (dev timers from `deploy/.env`) |
