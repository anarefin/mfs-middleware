# mw-adapter-merchant

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.adapter.merchant`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Merchant system adapter (ticket 115a, FRS UC-11, EXT-11, MCK-01 SOAP, CON-02; ADR D32 decision 5: one
  service per party). Copied from mw-adapter-bank (callback path left out: merchant systems send no
  callbacks). Consumes `mw.partner.request.merchant.v1` (`messaging/MerchantRequestHandler`, a Spring
  Kafka listener, retry topics + DLQ, `mw.merchant.request-consumer.enabled`), checks the request
  (`mapper/PartnerCallMapper`: family `merchant`, txnType `MERCHANT_PAY`, a partner, credit party
  `{type: MERCHANT, id}`, a positive amount except for INQUIRY; `attributes.tillId` names the till),
  calls the merchant system once through the partner engine with our stable reference and publishes
  `mw.partner.reply.v1` through the outbox (inbox-deduplicated on `requestId`):
  - **VALIDATE** = `INQUIRY` (before the hold): the merchant exists, is active, the till id is valid.
    SUCCESS (`partnerRef` = the merchant system's merchant reference) or FAILED
    `MW-PTR-<PARTNER>-MERCHANT_NOT_FOUND|MERCHANT_INACTIVE|INVALID_TILL`.
  - **NOTIFY** = `PAY` (after COMMIT, best effort): the payment notify. A failed notify never reverses
    the payment: the orchestrator retries it (a new requestId per attempt), then dead-letters it as the audit record
    `merchant.notify.failed` (ticket 115b).
  - **REFUND** = `REVERSE`: the refund of the original reference, idempotent on the engine's
    `reversalReference` (`reverse:<txnId>`).
  - `STATUS_QUERY` (ticket 115b) = `QueryNotify {merchantId, reference}` by our reference: `RECEIVED` ->
    SUCCESS (`partnerRef` = the notifyRef), `NOT_FOUND` -> NOT_FOUND.
  `service/MerchantPartnerService` is the copy of bank's debit service; outcomes and safety rules are
  telco's/billpay's (see mw-adapter-billpay CLAUDE.md): FAILED `MW-PTR-<PARTNER>-<suffix>` from the
  record's `error-map` (unlisted `UNMAPPED`; partner segment upper-cased, `-` -> `_`, so
  `MW-PTR-MERCHANT_SOAP-*`), FAILED `MW-SYS-PARTNER_*` when nothing left, UNKNOWN `MW-TMO-PARTNER_*`, a
  PAY past its `deadlineAt` refused unsent, FAILED `MW-VAL-INVALID_PARTNER_REQUEST` for requests that
  cannot be mapped, `MW-PTR-UNKNOWN-PARTNER` for an unknown partner id (no aliases).
- Stable partner reference (`service/PartnerReferenceService`, table `partner_reference` in own schema
  `merchant_ref`): `BITS` + txnId hex, stored on first use.
- The partner call runs on a virtual-thread executor (`merchantCallExecutor`), never on the Kafka
  consumer thread.
- Partner-call audit (S6): `PartnerAudit.publishing` -> `messaging/PartnerAuditPublisher` ->
  `mw.audit.event.v1` through the outbox, as billpay; full masked bodies in `partner_payload`.
- Dev provisioning (`provision_mw_adapter_merchant` in `deploy/provision/services.sh`, idempotent, dev
  only): role `adapter_merchant_rw` and schema `merchant_ref`, `secret/mw-adapter-merchant` (DB
  password, never overwritten), `secret/mw-adapter-merchant/merchant-soap` (field `ws-password`, synced
  from `MERCHANT_SOAP_WS_PASSWORD`) and the AppRole `mw-adapter-merchant`. Compose service
  `mw-adapter-merchant` (profile `core`, 384 MiB, `MW_PORT_ADAPTER_MERCHANT` 8098, `MERCHANT_SOAP_BASE_URL`).

## Partner engine (`partner` package; ADR D28: the copy of mw-adapter-billpay's)

The engine, its guardrails and its Kamelets are billpay's (mw-adapter-billpay CLAUDE.md "Partner
engine", "SOAP partners"). The main `partner/` tree, `src/main/resources/kamelets` and `partner/`
resources are identical in the four adapters apart from the package
(`scripts/test/test_partner_engine_copies.sh`): a fix goes into all four. The engine's callback support
(`PartnerGateway.inbound`, `CallbackNonces`, Flyway V7) stays in the copy unused.

- **Seed** `merchant-soap` (`application-dev.yml`, dev profile only; the only dev seed): SOAP 1.1
  `POST <base-url>/merchant-soap/v1/soap`, namespace `urn:merchant:v1`, one branch per operation:
  INQUIRY `ValidateMerchant {merchantId, tillId}`, PAY `NotifyPayment {merchantId, tillId, amount,
  reference}`, STATUS_QUERY `QueryNotify {merchantId, reference}`, REVERSE `Refund {merchantId, amount, reference, refundId}` (`mw-map` -> `mw-soap-call`
  with `soap.request.xsl` / `soap.response.xsl` and a WS-Security UsernameToken user `bits`, password field
  `ws-password` -> `mw-classify` `definiteStatuses=200` -> `mw-audit`). Answers: `ValidateMerchantResponse
  {status ACTIVE, merchantRef}`, `NotifyPaymentResponse {status RECEIVED, notifyRef, code}`,
  `QueryNotifyResponse {status RECEIVED|NOT_FOUND, notifyRef}`, `RefundResponse {status REFUNDED, refundRef, code}`; a `soap:Client` fault with a code is FAILED through
  the error map (`5001` MERCHANT_NOT_FOUND, `5002` MERCHANT_INACTIVE, `5003` INVALID_TILL, `5004`
  REFUND_REFUSED; `9876` ambiguous), a `soap:Server` fault or anything else UNKNOWN. Base URL
  `MERCHANT_SOAP_BASE_URL`, hosts `MW_MERCHANT_SOAP_ALLOWED_HOSTS` (floor in `application.yml`), timeout
  10 s, resilience4j instance `merchant-soap` (8 s slow call, bulkhead 100). The simulator side is
  mw-simulators `MerchantSoapMock` (party `merchant-soap`, ticket 115b; its WS-Security password
  `sim.merchant-soap.ws-password` synced from `MERCHANT_SOAP_WS_PASSWORD` by `provision_mw_simulators`);
  mw-admin-api holds the same record as its dev seed `partner-seeds/dev/merchant-soap.json` (keep both identical,
  the URL written out). Orchestrator side: mw-orchestrator CLAUDE.md "Merchant system leg". Test:
  `route/MerchantSoapRouteIT` (WireMock).
- **Test-only partner** `c2wbank` (`src/test/resources/config/application-dev.yml`): the REST
  async-callback record of mw-adapter-bank, kept so the shared engine tests and `MerchantRequestRouteIT`
  (request handling, outcomes, circuit breaker, audit) run unchanged.
- **Family** `merchant` (mw-admin-api accepts it since ticket 115, Flyway V17); **route status** on
  `mw.partner.route.status.v1`, batch SFTP routes on `mw.partner.recon.v1`, as billpay.

## Contracts owned

- None.

## Contracts used

- Machine-readable list: `contracts.yml` (checked by `./gradlew :contractsLint`, tested by
  `contracts/ProducedContractsTest` / `ConsumedContractsTest`). No APIs.
- `events/mw-orchestrator/mw.partner.request.v1` (family `merchant`, shared payload; samples
  `samples/mw.partner.request.v1/merchant`) in; own type `dto/PartnerRequest`. Produced by mw-orchestrator
  since ticket 115b (VALIDATE and NOTIFY).
- `events/mw-orchestrator/mw.partner.reply.v1` (family `merchant`) out; own type `dto/PartnerReply`.
- `events/mw-audit-dw/mw.audit.event.v1` out (`partner.call.completed`).
- `events/mw-admin-api/mw.config.changed.v1` in (PARTNER route records, family `merchant`).
- `events/mw-adapter-billpay/mw.partner.route.status.v1` and `mw.partner.recon.v1` out (owner
  mw-adapter-billpay; family `merchant` added to their enums in ticket 115).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (written inline in
  `build.gradle.kts`; drop once the Boot / Camel BOMs ship them); the shared dev provisioning image `deploy/provision/Dockerfile` runs `apk upgrade` and removes gosu. `scripts/security.sh <this module>` scans the SBOM and the
  `mfs-mw/*:local` images (HIGH/CRITICAL fail; accepted ones in `.trivyignore.yaml` with a reason and expiry).
- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3 (+ `PARTY_ID`, above).
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
- `config/MwServiceProperties`: config binding; secrets from Vault when `VAULT_ENABLED=true`.
- Spring Kafka (ADR D27, the billpay copy): `config/KafkaConfig` (retry topics `<topic>.<group>.retry.1..3`
  10 s / 1 min / 10 min then `.dlq`), `messaging/EventPublisher`, outbox/inbox, `scheduler/OutboxRelayScheduler`,
  `partner/config/PartnerConfigKafkaListener` (replays `mw.config.changed.v1` on every start).
- `application.yml`: Resilience4j defaults (19.2), own schema `merchant_ref` migrated by Flyway.

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-adapter-merchant:test` | every test in one JVM: unit, slice and contract tests (`contracts/ProducedContractsTest`, `ConsumedContractsTest`), the Testcontainers `*IT` classes and the `@Conformance` suite; one PostgreSQL and one Kafka per test JVM, a fresh database per Spring context; the `*IT` and conformance classes are skipped without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-adapter-merchant:build` | `test`, then the boot jar `build/libs/mw-adapter-merchant.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-adapter-merchant:bootJar && docker build -t mw-adapter-merchant:dev services/mw-adapter-merchant` | image, as spec 4.3 rule 1 |
| `./gradlew clean build` | the full gate: every module, plus the repo checks (`:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`); unchanged modules come from the build cache |
| `./gradlew :mw-adapter-merchant:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-adapter-merchant` | rebuild this service's image (`mfs-mw/mw-adapter-merchant:local`) and restart it with what it depends on (dev timers from `deploy/.env`) |
