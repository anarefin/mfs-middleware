# 124: mw-compliance: eKYC against the EC NID service, consent log, onboarding (FRS UC-01, EXT-07, FL-01, FL-02, CMP-04, CMP-05)

**What to build:** A new domain service `mw-compliance` (scaffolded with `scripts/new-service.sh mw-compliance`) that
owns customer eKYC: it records consent, calls the Election Commission NID service, compares name and date of birth,
stores the match score and the encrypted NID images and selfie, and sets the wallet's KYC level on the core. It also
adds a stateful EC NID mock and the onboarding APIs for the Customer, DSO and Agent apps. AML (ticket 125) is added to
the same service later.

**Principles:**
- **Consent before call** (CMP-04): no EC request is sent unless a consent row (customer, purpose, channel, time, agent
  id where relevant) committed first; the EC call carries the consent id.
- **PII at rest is encrypted** (FL-02, CMP-05): NID number, DOB, names from EC and the images go through the
  `mw-crypto` envelope (`KEK_COLUMN`) and `TOKENISE_NID` (search by token, never by clear NID). EC responses are masked
  in logs and audit (`MaskingPolicy`: NID last 3 digits, names first initial).
- No Camel (not an adapter): the EC client is `client/EcNidClient` (Spring `RestClient` + Resilience4j timeout, retry
  only on the idempotent verify call, circuit breaker); base URL and credentials from Vault, so mock → real is config.
- mw-compliance never writes another schema: KYC level is set through core REST (ticket 94), the result is published
  as an event.

**Scope:**
- **Schema `compliance`** (Flyway): `consent`, `kyc_application` (wallet, channel, agent id, status SUBMITTED / EC_CALLED
  / MATCHED / MISMATCHED / FAILED / APPROVED, match score, kyc level), `kyc_document` (type NID_FRONT / NID_BACK /
  SELFIE, encrypted blob or object key, sha256), plus chassis `outbox`, `inbox`, `shedlock`.
- **API** (`contracts/api/mw-compliance/compliance.v1.openapi.yaml`): internal `POST /internal/v1/kyc/applications`,
  `POST .../{id}/documents`, `POST .../{id}/submit`, `GET .../{id}`; mTLS + service JWT (ticket 66 pattern).
- **Channels:** mw-app-gateway routes `POST /app/v1/onboarding/...` for the Customer app (self), DSO and Agent apps
  (assisted, agent id from the token), forwarding to mw-compliance; images up to 2 MB each, content-type checked.
- **Event:** `mw.kyc.completed.v1` (contract under `contracts/events/mw-compliance/`, owner in `contracts/OWNERS.yml`;
  consumer mw-notify for the SMS, mw-audit-dw for audit; DW in ticket 128). Maps to FRS `kyc.completed` (DAT-01).
- **Match rules** (config): exact DOB, normalised Bangla/English name similarity ≥ threshold → `FULL`; DOB only →
  `BASIC`; else MISMATCHED (manual review in the console, ticket 134).
- **EC NID mock** in mw-simulators, party `ec`, SOAP and REST both (FRS EXT-07 says SOAP / REST), seed
  `seed/ec/nid.json` (≥ 200 records with Bangla and English names, father/mother, address, a photo placeholder);
  triggers: NID ending `000` not found, `999` timeout, DOB mismatch for NIDs ending `111`.
- **Compose:** `mw-compliance` block (profile `core`) with a memory limit; `:composeCheck` within 12 GB.
- **Docs:** service `CLAUDE.md`, `contracts.yml`, README service list, runbook entry, traceability rows.

**FRS IDs covered:** UC-01, EXT-07, FL-01, FL-02, CMP-04, CMP-05 (storage side; the permissioned unmask screen is 134),
DAT-01 `kyc.completed`.

**Blocked by:** 94, 102, 106

**Status:** open

- [ ] `ConsentBeforeCallTest`: without a committed consent the EC client is never invoked
- [ ] `KycMatchServiceTest`: FULL / BASIC / MISMATCHED per rule table, Bangla and English names
- [ ] `KycApplicationIT` (Testcontainers + EC mock): submit → EC call → KYC level set on core-mock REST → `mw.kyc.completed.v1` published once (outbox), redelivery deduped (inbox)
- [ ] `PiiAtRestIT`: the `compliance` tables hold no clear NID, DOB or name (column scan), documents are envelope-encrypted
- [ ] Log test: no clear NID or name in any log line (`MaskingPolicy` + appender filter)
- [ ] EC timeout and circuit-open paths return `MW-TMO-*` / `MW-SYS-PARTNER_CIRCUIT_OPEN` and leave the application retryable
- [ ] `./gradlew :mw-compliance:build :mw-app-gateway:build :mw-simulators:build :contractsLint :composeCheck :checkIsolation :checkFieldInjection` green with Docker
- [ ] API suite folder `58-ekyc`: self onboarding, DSO-assisted onboarding, mismatch, EC down; `scripts/log-scan.sh` clean afterwards
