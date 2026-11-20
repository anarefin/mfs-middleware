# mw-adapter-interop

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.adapter.interop`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Status (ticket 121 split, `.scratch/bits-mfs-middleware/issues/121-split-decisions.md`)

- **121a (this module as built):** the adapter module, the partner engine copy, family `interop`, the request
  check, Kafka topic, compose, provision, mw-admin-api family, and the participant list of mw-simulators' `npsb`
  party. No dev seed partner yet: a request for `npsb` is answered FAILED `MW-PTR-UNKNOWN-PARTNER`.
- **121b (built, part 1):** the ISO 20022 wire (Prowide `pw-iso20022`, the ISO XSDs under
  `src/main/resources/iso20022/`, `mapping/`) and XML-DSig sign / verify through mw-crypto
  (`client/CryptoXmlSigner`; mw-crypto binds purpose XMLDSIG to this client). See "ISO 20022 wire" below.
- **121b (built, part 2):** the `npsb` seed partner with the ISO 20022 step in the service layer around the
  engine (`service/NpsbWireService`), the inbound `POST /partner/v1/npsb/inbound` (`service/NpsbInboundService`,
  APISIX route `partner-npsb-inbound`), the service certificate (mTLS to mw-crypto) and the stateful NPSB mock
  in mw-simulators. See "NPSB seed and inbound" below. **Open:** the dev switch signing key pair (the mock does
  not sign and `npsb-certificate-pem` is not provisioned, so in the stack every switch answer is UNKNOWN and
  every pushed pacs.008 401) and edge mTLS for the switch (APISIX has no client-certificate check yet).
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

## ISO 20022 wire (ticket 121b)

- `mapping/Iso20022Mapper`: canonical records (`dto/iso/`: `NpsbTransfer`, `NpsbStatus`, `NpsbStatusRequest`,
  `NpsbReturnRequest`, `NpsbResolution`, `NpsbReturn`) <-> pacs.008.001.08, pacs.002.001.10, pacs.028.001.03,
  camt.056.001.08, camt.029.001.09, pacs.004.001.09 with Prowide `pw-iso20022` (never string templates); one
  transaction per message; amounts integer poisha <-> ISO decimal with two places; the `Document` only
  (default namespace, no prefix). Every message written is validated before it leaves and every message
  read before Prowide parses it.
- `mapping/Iso20022Schemas`: the official ISO 20022 XSDs (`src/main/resources/iso20022/<message>.xsd`,
  downloaded unchanged from iso20022.org), compiled once; safe parse first (no DOCTYPE, no external DTD /
  schema, secure processing, at most 1 MiB), root must be that message's `Document`; any refusal is
  `MW-PTR-NPSB-INVALID_MESSAGE` (the validator's text, which quotes values, is never passed on).
- ISO `Max35Text`: `EndToEndId`, `TxId`, `MsgId` hold at most 35 characters. The wire id is our stable
  reference without its `BITS` prefix: the txnId's 32 hex digits (`NpsbWireService.endToEndId` /
  `referenceOf`, reversible in the adapter; mw-orchestrator unchanged). pacs.008 `MsgId` = `TxId` = that id,
  pacs.028 `MsgId` `Q` + id, camt.056 case `R` + id.
- `Iso20022Schemas.unsigned`: the document without its enveloped `ds:Signature` (the ISO XSD has no place for
  it), used only after the signature verified.

## NPSB seed and inbound (ticket 121b part 2)

- Seed `npsb` (`application-dev.yml`, copy `mw-admin-api` `partner-seeds/dev/npsb.json`): one `ISO.request`
  mapping `{messageType, document}` and one `ISO.response` (200 `{msgId, document}` -> SUCCESS with the attribute
  `document`, 404 NOT_FOUND, 400 FAILED `HTTP_400`, else UNKNOWN) on `/npsb/v1/transfers | status | returns`;
  auth none (the message is signed); `error-map` = ISO reason codes (AC01, AC04, AC06, AG01, AM02, AM04, DUPL,
  RC01, RJCR). The signed XML travels as a JSON string, so the audit's free-text scrub still masks it.
- `service/NpsbWireService` (`InteropPartnerService` calls it for partner `npsb` only; the engine copies stay
  identical): builds pacs.008 (debtor = our wallet, agent `mw.interop.npsb.own-participant` BITSBDDH; creditor
  agent = `attributes.participantId`, else `switch-participant`), pacs.028, camt.056 (reason
  `attributes.reasonCode`, default CUST), signs it (signing off or mw-crypto down: NOT_SENT, nothing sent), then
  verifies and reads the answer: pacs.002 ACSP / ACSC / ACCC SUCCESS (partnerRef = ClrSysRef), RJCT FAILED
  `MW-PTR-NPSB-<error-map suffix>`, PDNG / another EndToEndId / bad signature / unreadable UNKNOWN; camt.029 CNCL
  SUCCESS, RJCR FAILED. Test: `route/NpsbOutRouteIT` (ACSC, RJCT, bad signature, no reply -> UNKNOWN -> pacs.028,
  404).
- Inbound `controller/NpsbInboundController` `POST /partner/v1/npsb/inbound` (application/xml, the signed
  pacs.008 as sent) -> `service/NpsbInboundService`: XML-DSig verified through mw-crypto (401
  `MW-PTR-NPSB-SIGNATURE`; mw-crypto down 503), XSD (400 `MW-PTR-NPSB-INVALID_MESSAGE`), then RJCT (signed
  pacs.002, nothing published): `DT01` CreDtTm older than `mw.interop.npsb.inbound-max-age` (24 h) or 5 min ahead,
  or settlement date before that window (replay defence, security review); `RC01` creditor agent not us;
  `AM03` not a positive BDT amount; `AC01` creditor account not a wallet number. Accepted: dedup on (debtor
  agent, EndToEndId) in table `npsb_inbound` (V8, never purged, unlike the 7 d inbox) and
  `mw.partner.inbound.v1` (family `interop`, kind `NPSB_CREDIT`, txnType `NPSB_IN`, partnerRef = EndToEndId,
  inboundId a name-based UUID of agent + EndToEndId, the ISO ids and the debtor in attributes; contract sample
  `valid-npsb-in.json`) through the outbox in one transaction; answer signed pacs.002 ACSP (also for a re-send).
  The beneficiary check, the core credit and MANUAL_REVIEW are mw-orchestrator's NpsbInFlow (121c). Test:
  `controller/NpsbInboundIT` (once on re-send, replay after the inbox purge, stale, tampered, RC01, XSD).
- mTLS to mw-crypto: `config/ServiceCertificateManager` + `TlsProperties` (the mw-data-export copy, `mw.tls.*`,
  `MW_TLS_ENABLED`), SSL bundle `mw-service` = `MW_INTEROP_CRYPTO_SSL_BUNDLE`; `PKI_SERVICES` entry, realm
  services client `mw-adapter-interop` (aud mw-crypto, role internal-api, `KC_MW_ADAPTER_INTEROP_SECRET` ->
  Vault `crypto-client-secret`). Compose does not override mw-crypto's bootstrap purposes, so the XMLDSIG key
  exists. Test: `client/MtlsClientIT`.
- `client/CryptoXmlSigner` (`mw.interop.crypto.*`, `config/CryptoClientProperties`, off unless
  `MW_INTEROP_CRYPTO_ENABLED=true`): `sign` -> mw-crypto `POST /internal/v1/xmldsig/sign {keyId: xmldsig,
  xml: base64}`; `verifyFromSwitch` -> `/verify {xml, certificatePem: <switch certificate>}` (Vault field
  `npsb-certificate-pem`; never the document's KeyInfo). `valid: false` = `MW-PTR-NPSB-SIGNATURE` (WARN
  `security.partner_signature_invalid`); mw-crypto not answering = `MW-SYS-CRYPTO_UNAVAILABLE` (fail
  closed). Client-credentials token (`client/ServiceTokenClient`, the mw-data-export copy; secret Vault
  field `crypto-client-secret`), mTLS through the Spring SSL bundle `mw.interop.crypto.ssl-bundle`.
- Tests: `mapping/Pacs008MappingTest`, `Pacs002MappingTest`, `Camt056MappingTest` (XSD-valid, lossless round
  trip, schema break, DOCTYPE, wrong message type, a hand-written switch pacs.002), `client/XmlSignatureIT`
  (WireMock as mw-crypto: signed outbound, valid and tampered inbound, mw-crypto down).

## Partner engine (`partner` package; ADR D28: the copy of mw-adapter-billpay's)

The engine, its guardrails and its Kamelets are billpay's (mw-adapter-billpay CLAUDE.md "Partner engine").
The main `partner/` tree, `src/main/resources/kamelets` and `partner/` resources are identical in every
adapter apart from the package (`scripts/test/test_partner_engine_copies.sh`): a fix goes into every copy.

- **Seed**: `npsb` (121b part 2, "NPSB seed and inbound" above). The static host
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
  `contracts/ProducedContractsTest` / `ConsumedContractsTest`). API used:
  `api/mw-crypto/crypto-internal.v1.openapi.yaml` (`/xmldsig/sign`, `/xmldsig/verify`, ticket 121b).
- `events/mw-orchestrator/mw.partner.request.v1` (family `interop`, shared payload; samples
  `samples/mw.partner.request.v1/interop`, NPSB ones added in 121a) in; own type `dto/PartnerRequest`. The
  orchestrator produces it with 121c.
- `events/mw-orchestrator/mw.partner.reply.v1` (family `interop`) out; own type `dto/PartnerReply`.
- `events/mw-orchestrator/mw.partner.inbound.v1` (family `interop`, kind `NPSB_CREDIT`, txnType `NPSB_IN`) out;
  own type `dto/PartnerInbound` (ticket 121b part 2).
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
