# mw-adapter-remit

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.adapter.remit`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Inbound remittance adapter (ticket 119a, FRS UC-16, EXT-15, FL-10 push, PLT-014, PLT-008). The partner
  (UCB IMT) calls this service; beneficiary, KYC, limits, AML and the core credit are mw-orchestrator's
  REMIT_IN flow (ticket 119b). Copied from the partner engine (package `mfs.mw.adapter.remit`).
- Inbound API (`contracts/api/mw-adapter-remit/remit-inbound.v1.openapi.yaml`,
  `controller/RemitInboundController`, `service/RemitInboundService`): `POST /remit/v1/credits` and
  `GET /remit/v1/credits/{partnerRef}`. Every request is `X-Partner-Id` plus hmac-sha256 over method, path,
  `X-Timestamp` (at most 300 s off), `X-Nonce` (once only) and the body hash. A bad signature, a replayed
  nonce, a stale timestamp or an unknown partner is 401 `MW-AUTH-001`. A credit is deduplicated on
  (partner, partnerRef): the first is 202 once the row and the outbox row of `mw.partner.inbound.v1` (kind REMITTANCE, txnType
  REMIT_IN, family `remit`) committed together (M9 review: no dual write; the relay publishes it); a re-send is 200 with the same Upay reference; a
  re-send that changes beneficiary or amount is 409 `MW-IDEM-002`. An invalid field is 400 `MW-VAL-001`.
  Logs carry no MSISDN and no sender name (`sensitive-fields` on the seed). The re-send comparison is
  `remittance.fingerprint` = HMAC-SHA256 of beneficiary, amount and currency under `mw.remit.fingerprint-key`
  (Vault `secret/mw-adapter-remit`, created once by the provision; >= 32 chars; M9 review: rows written before
  119b hold the old plain SHA-256, so a re-send of one of those is 409). The orchestrator's REMIT_IN outcome
  (ticket 119b) arrives as a PAY with `attributes.outcome` CREDITED / REJECTED / MANUAL_REVIEW:
  `RemitPartnerService` records it on the row first (the status API answers it), then confirms to the partner.
  Edge: APISIX route `partner-remit-v1` (`/remit/v1/*`, `deploy/apisix/README.md`).
- Outbound (`messaging/RemitRequestHandler` on `mw.partner.request.remit.v1`, off in tests with
  `mw.remit.request-consumer.enabled=false`): PAY confirms the outcome back to the partner and
  STATUS_QUERY reads it (`service/RemitPartnerService`). The call runs on `remitCallExecutor`, never on
  the Kafka consumer thread. Outcomes follow billpay: a mapped partner code is FAILED from the record's
  `error-map` (`4001` UNKNOWN_REMITTANCE), and anything else that is not a definite failure is UNKNOWN.
- Stable partner reference (`service/PartnerReferenceService`, schema `remit_ref`): `BITS` + txnId hex.
- Dev provisioning (`provision_mw_adapter_remit`): role `adapter_remit_rw`, schema `remit_ref`,
  `secret/mw-adapter-remit/ucb-imt` field `hmac-secret` from `UCB_IMT_HMAC_SECRET` (the same value is
  mw-simulators' `sim.callback-secrets.ucb-imt`). Compose service `mw-adapter-remit` (profile `core`,
  384 MiB, Hikari pool 2, `MW_PORT_ADAPTER_REMIT` 8101, `UCB_IMT_BASE_URL`).

## Partner engine (`partner` package; ADR D28: the copy of mw-adapter-billpay's)

The engine, its guardrails and its Kamelets are billpay's (mw-adapter-billpay CLAUDE.md "Partner engine").
The main `partner/` tree, `src/main/resources/kamelets` and `partner/` resources are identical apart from
the package (`scripts/test/test_partner_engine_copies.sh`): a fix goes into every copy.

- **Seed** `ucb-imt` (`application-dev.yml`, dev profile only): hmac-sha256, base URL `UCB_IMT_BASE_URL`
  (default `http://mw-simulators:8080`), hosts `MW_UCB_IMT_ALLOWED_HOSTS`. CALLBACK.response reads the
  inbound credit. PAY `POST /ucb-imt/v1/remittances/{reference}/confirmation`, STATUS_QUERY `GET
  /ucb-imt/v1/remittances/{reference}`. A 404/422 with a code is FAILED (`4001` UNKNOWN_REMITTANCE);
  anything else UNKNOWN. Test: `controller/RemitInboundIT` (signed credit, replayed nonce, duplicate
  reference) and `route/RemitRequestRouteIT`.
- **Test-only partner** `c2wbank` (`src/test/resources/config/application-dev.yml`): kept so the shared
  engine tests run unchanged.
- **Family** `remit` (mw-admin-api Flyway `V19__partner_family_remit.sql`; merchant and card stay). Route
  status on `mw.partner.route.status.v1`, batch SFTP on `mw.partner.recon.v1`.

## Contracts owned

- `api/mw-adapter-remit/remit-inbound.v1.openapi.yaml` (the partner's inbound credit and status API).

## Contracts used

- Machine-readable list: `contracts.yml` (checked by `./gradlew :contractsLint`, tested by
  `contracts/ProducedContractsTest` / `ConsumedContractsTest`).
- `events/mw-orchestrator/mw.partner.inbound.v1` (family `remit`, kind REMITTANCE) out; own type
  `dto/PartnerInbound`. Samples `samples/mw.partner.inbound.v1/remit`.
- `events/mw-orchestrator/mw.partner.request.v1` (family `remit`) in; own type `dto/PartnerRequest`.
  Samples `samples/mw.partner.request.v1/remit`. The orchestrator produces the requests in ticket 119b.
- `events/mw-orchestrator/mw.partner.reply.v1` (family `remit`) out; own type `dto/PartnerReply`.
- `events/mw-audit-dw/mw.audit.event.v1` out (`partner.call.completed`).
- `events/mw-admin-api/mw.config.changed.v1` in (PARTNER route records, family `remit`).
- `events/mw-adapter-billpay/mw.partner.route.status.v1` and `mw.partner.recon.v1` out (owner
  mw-adapter-billpay; family `remit` added to their enums).

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
