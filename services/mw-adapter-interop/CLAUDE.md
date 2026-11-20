# mw-adapter-interop

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.adapter.interop`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Status (ticket 121 split, `.scratch/bits-mfs-middleware/issues/121-split-decisions.md`)

- **121a (this module as built):** the adapter module, the partner engine copy, family `interop`, the request
  check, Kafka topic, compose, provision, mw-admin-api family, and the participant list of mw-simulators' `npsb`
  party. No dev seed partner yet: a request for `npsb` is answered FAILED `MW-PTR-UNKNOWN-PARTNER`.
- **121b:** the ISO 20022 wire (Prowide `pw-iso20022`, XSDs under `src/main/resources/iso20022/`, `mapping/`),
  XML-DSig sign and verify through mw-crypto, the `npsb` seed (PAY pacs.008, STATUS_QUERY pacs.028, REVERSE
  camt.056), the inbound `POST /partner/v1/npsb/inbound` (APISIX mTLS + IP allow-list, kind `NPSB_IN` on
  `mw.partner.inbound.v1`) and the stateful NPSB mock.
- **121c:** mw-orchestrator `NpsbOutFlow` / `NpsbInFlow`, routing rows, mw-app-gateway
  `/app/v1/npsb-transfers/preview|confirm`, Newman folder `57-npsb`.

## Responsibilities

- NPSB interop adapter (ticket 121, FRS EXT-13, UC-19, FL-12 off-us leg; ADR D32 decision 5: one service per
  party). Copied from mw-adapter-card (itself the merchant/bank copy; the engine's callback support stays in
  the copy unused). Consumes `mw.partner.request.interop.v1` (`messaging/InteropRequestHandler`, a Spring
  Kafka listener, retry topics + DLQ, `mw.interop.request-consumer.enabled`), checks the request
  (`mapper/PartnerCallMapper`), calls the switch once through the partner engine with our stable reference
  and publishes `mw.partner.reply.v1` through the outbox (inbox-deduplicated on `requestId`).
- Request check (`mapper/PartnerCallMapper`): family `interop`; operations PAY (the fund transfer, pacs.008),
  STATUS_QUERY (pacs.028, by our reference only) and REVERSE (the return, camt.056; needs
  `originalPartnerRef` and a positive amount). INQUIRY and SCREEN are refused. txnTypes `NPSB_OUT` (debit the
  customer's `WALLET`, credit a `BANK_ACCOUNT` or `WALLET` at the participant `attributes.participantId`) and
  `QR_PAY_INTEROP` (the off-us BanglaQR leg of ticket 122: credit a `MERCHANT`, no participant). A refused
  request is answered FAILED `MW-VAL-INVALID_PARTNER_REQUEST`, nothing sent.
- `service/InteropPartnerService` is the card copy: FAILED `MW-PTR-NPSB-<suffix>` from the record's
  `error-map`, FAILED `MW-SYS-PARTNER_*` when nothing left, UNKNOWN `MW-TMO-PARTNER_*`, a PAY past its
  `deadlineAt` refused unsent, `MW-PTR-UNKNOWN-PARTNER` for an unknown partner id (no aliases).
- Stable partner reference (`service/PartnerReferenceService`, table `partner_reference` in own schema
  `interop_ref`): `BITS` + txnId hex, stored on first use.
- The partner call runs on a virtual-thread executor (`interopCallExecutor`), never on the Kafka consumer thread.
- Partner-call audit (S6): `PartnerAudit.publishing` -> `messaging/PartnerAuditPublisher` ->
  `mw.audit.event.v1` through the outbox, as billpay; full masked bodies in `partner_payload`.
- Dev provisioning (`provision_mw_adapter_interop` in `deploy/provision/services.sh`, idempotent, dev only):
  role `adapter_interop_rw` and schema `interop_ref`, `secret/mw-adapter-interop` (DB password, never
  overwritten) and the AppRole `mw-adapter-interop`. No partner secret: the messages are signed through
  mw-crypto (121b). Compose service `mw-adapter-interop` (profile `core`, 384 MiB, Hikari pool 2,
  `MW_PORT_ADAPTER_INTEROP` 8102, `NPSB_BASE_URL`).

## Partner engine (`partner` package; ADR D28: the copy of mw-adapter-billpay's)

The engine, its guardrails and its Kamelets are billpay's (mw-adapter-billpay CLAUDE.md "Partner engine").
The main `partner/` tree, `src/main/resources/kamelets` and `partner/` resources are identical in every
adapter apart from the package (`scripts/test/test_partner_engine_copies.sh`): a fix goes into every copy.

- **Seed**: none in 121a (`application-dev.yml` `instances: {}`); `npsb` comes with 121b. The static host
  floor `mw.partners.security.allowed-hosts.npsb` (`MW_NPSB_ALLOWED_HOSTS`, default `mw-simulators`) and the
  resilience4j instance `npsb` (8 s slow call, bulkhead 100) are in `application.yml` already.
- **Test-only partner** `c2wbank` (`src/test/resources/config/application-dev.yml`): the REST
  async-callback record of mw-adapter-bank, kept so the shared engine tests and `route/InteropRequestRouteIT`
  (request handling, outcomes, circuit breaker, audit) run unchanged.
- **Family** `interop` (mw-admin-api accepts it since ticket 121, Flyway V21); **route status** on
  `mw.partner.route.status.v1`, batch SFTP routes on `mw.partner.recon.v1`, as billpay.

## Contracts owned

- None.

## Contracts used

- Machine-readable list: `contracts.yml` (checked by `./gradlew :contractsLint`, tested by
  `contracts/ProducedContractsTest` / `ConsumedContractsTest`). No APIs yet.
- `events/mw-orchestrator/mw.partner.request.v1` (family `interop`, shared payload; samples
  `samples/mw.partner.request.v1/interop`, NPSB ones added in 121a) in; own type `dto/PartnerRequest`. The
  orchestrator produces it with 121c.
- `events/mw-orchestrator/mw.partner.reply.v1` (family `interop`) out; own type `dto/PartnerReply`.
- `events/mw-audit-dw/mw.audit.event.v1` out (`partner.call.completed`).
- `events/mw-admin-api/mw.config.changed.v1` in (PARTNER route records, family `interop`).
- `events/mw-adapter-billpay/mw.partner.route.status.v1` and `mw.partner.recon.v1` out (owner
  mw-adapter-billpay; family `interop` added to their enums in ticket 121).

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
- `application.yml`: Resilience4j defaults (19.2), own schema `interop_ref` migrated by Flyway.

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-adapter-interop:test` | every test in one JVM: unit, slice and contract tests, the Testcontainers `*IT` classes and the `@Conformance` suite; the `*IT` and conformance classes are skipped without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-adapter-interop:build` | `test`, then the boot jar `build/libs/mw-adapter-interop.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-adapter-interop:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-adapter-interop` | rebuild this service's image (`mfs-mw/mw-adapter-interop:local`) and restart it with what it depends on (dev timers from `deploy/.env`) |
