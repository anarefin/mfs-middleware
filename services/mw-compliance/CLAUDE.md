# mw-compliance

Service module (spec 4.2, 4.3), a subproject of the root build. Package `mfs.mw.compliance`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

Compliance domain service (ADR D32; no Camel: outbound calls are `RestClient` + Resilience4j in `client/`).

- Customer eKYC (ticket 124, FRS UC-01, EXT-07, FL-01, FL-02, CMP-04, CMP-05): `service/KycApplicationService`.
  Create = the consent row (CMP-04) and the application in one transaction; documents = NID front, back and
  selfie (JPEG/PNG, magic bytes checked, 2 MB); submit = EC_CALLED in one transaction (only with every image
  and a committed consent: `ConsentBeforeCallTest`), the EC verify call outside any transaction carrying the
  consent id, then the outcome in a second one. A match (MATCHED) then sets the level on the core
  (`client/CoreWalletClient`, `PUT /core/v1/accounts/{walletId}/kyc-level`, ticket 124b) and only after that,
  in a third transaction under the row lock, becomes APPROVED with `mw.kyc.completed.v1` through the outbox.
  EC timeout / open breaker / EC or mw-crypto failure: FAILED, retryable, submit again (an EC_CALLED left
  for 2 min after a crash may be submitted again too). Core down: stays MATCHED (`error_code` set), 503
  retryable; a resubmit of a MATCHED application goes straight to the core, no second EC call.
- Match rules (`service/KycMatchService`, `util/NameNormaliser`): exact DOB, then the better of English and
  Bangla name similarity (NFC, zero-width joiners and punctuation dropped, case folded, words sorted,
  1 - Levenshtein/length) >= `mw.compliance.kyc.name-threshold` (0.85) is FULL; below it (DOB only) is
  MANUAL_REVIEW, reason NAME_MISMATCH, no level, no event (product decision, ticket 124b); another DOB or
  an NID the EC does not know is MISMATCHED (`reason` DOB_MISMATCH / NID_NOT_FOUND; manual review ticket 134).
- PII at rest (FL-02, CMP-05): NID, DOB, claimed and EC names and the images are mw-crypto envelopes under
  `KEK_COLUMN` (`service/PiiCipher`, AAD `<table>/<id>/<column>`), the NID searched by its `TOKENISE_NID`
  token; only the last 3 NID digits are clear. The API never returns names, DOB or images.
- Schema `compliance` (V4): `consent`, `kyc_application`, `kyc_document`, plus chassis `outbox`, `inbox`,
  `shedlock`.
- Internal API `/internal/**`: realm `services` JWT (`aud` mw-compliance; `azp` mw-app-gateway,
  mw-admin-api: `config/SecurityConfig`), and mTLS when `mw.tls.enabled` (server `client-auth: need`).
- Scope (security review): `dto/Caller`. A client outside `mw.compliance.unscoped-clients` (mw-admin-api)
  must send `X-Mw-Subject-Type` WALLET|AGENT and `X-Mw-Subject-Id` (the app token's customer wallet or
  assisting agent), else 403; it sees only that wallet's or that agent's applications, any other id 404
  (`KycApplicationService.visible`, `KycAccessTest`). A customer applies for their own wallet on APP only; an
  agent's id replaces `agentId`. The unscoped client reads (GET) only: every write needs a subject, there is
  no operator write path (`KycAccessTest.theUnscopedCallerMayOnlyRead`).
- NID reuse: in the submit transaction, under an advisory lock on the token, an NID another wallet holds
  (EC_CALLED, MATCHED, APPROVED) makes the application MANUAL_REVIEW, reason NID_IN_USE: no EC call, no level.
- Planned: ticket 124b/124c (channel routes in mw-app-gateway, EC mock in mw-simulators,
  compose/provision/Keycloak/mw-crypto authz wiring, Newman folder 64); ticket 125 the AML feed (parties
  HMAC-SHA256-tokenised under a Vault key: add `/internal/v1/hmac/sign` to `client/CryptoClient`).

## Contracts owned

- `api/mw-compliance/compliance.v1.openapi.yaml` (internal eKYC API; `contracts/ContractDriftTest`).
- `events/mw-compliance/mw.kyc.completed.v1.schema.json` (key walletId; no NID, name or DOB;
  `contracts/ProducedContractsTest`). Consumer mw-data-export (DW); mw-notify and mw-audit-dw in 124b.

## Contracts used

- `api/mw-core-mock/core-wallet.v1.openapi.yaml`: `PUT /core/v1/accounts/{walletId}/kyc-level` `{kycLevel,
  applicationId}` (only mw-compliance may call it: core-mock `kyc-clients`); token from `client/ServiceTokenClient`
  (the realm client needs an `aud-mw-core-mock` mapper next to `aud-mw-crypto`); breaker `core-wallet`; failure
  `MW-SYS-002` (503, retryable), a refusal `MW-CORE-KYC_LEVEL_REFUSED` (422).
- `api/mw-crypto/crypto-internal.v1.openapi.yaml`: `/tokens` (TOKENISE_NID), `/envelope/encrypt|decrypt`
  (KEK_COLUMN); own DTOs `dto/Crypto*`.
- EC NID service (no contract file yet; 124b adds it with the mock): `POST /ec/v1/nid/verify`
  `{nid, dob, consentId, requestId}` with HTTP Basic, 200 `{nid, dob, nameEn, nameBn, fatherName,
  motherName, address}`, 404 unknown NID (`client/EcNidClient`). Read timeout `mw.compliance.ec.timeout`;
  retry `ec-nid` only on connection failure or 5xx (never after a timeout); breaker `ec-nid`. Timeout is
  `MW-TMO-EC_NID` (504), open breaker `MW-SYS-PARTNER_CIRCUIT_OPEN` (503).
- `contracts.yml` is the machine-readable list (produces/consumes, events and apis, paths relative to
  `contracts/`, `#<Msg>` for one message type), checked by `./gradlew :contractsLint`; keep it in sync and
  add `contracts/ProducedContractsTest` / `ConsumedContractsTest` once the service has events.

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
- `application.yml`: Resilience4j defaults (19.2), own schema `compliance` migrated by Flyway on
  start from `db/migration` (16.4); rename the schema to the spec 16.2 name if it has one.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew :mw-compliance:test` | every test in one JVM: unit, slice, contract, Testcontainers `*IT` and `@Conformance` (`*IT` skipped without Docker) |
| `./gradlew :mw-compliance:build` | the tests plus the boot jar `build/libs/mw-compliance.jar` |
| `./gradlew :mw-compliance:bootJar && docker build -t mfs-mw/mw-compliance:local services/mw-compliance` | image, as spec 4.3 rule 1 |
