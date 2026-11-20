# 124 split decisions (2026-11-20, implementer, 1 h cap)

Ticket 124 is larger than the 1 h cap (new module + EC mock in mw-simulators + app-gateway routes + core KYC
level + stack wiring + Newman). Split as the orchestrator suggested:

## 124a (delivered, branch `ticket/124-mw-compliance-ekyc`)

- `services/mw-compliance` scaffolded; schema `compliance` V4 (`consent`, `kyc_application`, `kyc_document`).
- Internal API `contracts/api/mw-compliance/compliance.v1.openapi.yaml`: create (consent + application),
  documents, submit, get. Realm `services` JWT (azp mw-app-gateway, mw-admin-api); server mTLS when
  `mw.tls.enabled`.
- `client/EcNidClient` (RestClient, Resilience4j timeout/retry/breaker `ec-nid`), `client/CryptoClient`
  (TOKENISE_NID, KEK_COLUMN envelope), `service/KycMatchService` (rule table), `service/PiiCipher`.
- Event `contracts/events/mw-compliance/mw.kyc.completed.v1.schema.json` + samples, OWNERS entry; consumer
  manifest line added to mw-data-export (its listener already read the topic).
- Tests: `KycMatchServiceTest`, `ConsentBeforeCallTest`, `KycApplicationIT` (WireMock EC, fake crypto:
  match/publish once, mismatch, not found, timeout -> MW-TMO-EC_NID retryable then resubmit, breaker open ->
  MW-SYS-PARTNER_CIRCUIT_OPEN, images missing, wrong image type, 401, PII-at-rest column scan, log scan),
  `ProducedContractsTest`.

## 124b (open)

1. **Core KYC level.** `contracts/api/mw-core-mock/core-wallet.v1.openapi.yaml` has `Account.kycLevel` but no
   endpoint to set it. Needs a core-mock contract change (owner mw-core-mock; the ticket says "through core
   REST (ticket 94)", which does not exist): e.g. `PUT /core/v1/accounts/{walletId}/kyc-level` `{kycLevel,
   applicationId}`, allowed client mw-compliance. Then MATCHED -> core call -> APPROVED, and the event moves
   after the core call (today it is published at MATCHED).
2. **EC NID mock** in mw-simulators (party `ec`, REST `POST /ec/v1/nid/verify` as `client/EcNidClient` calls it,
   plus SOAP), seed `seed/ec/nid.json` (>= 200 records, Bangla/English names), triggers `000` not found, `999`
   timeout, `111` DOB mismatch; a contract file for it (`contracts/api/mw-simulators/ec-nid.v1.openapi.yaml`).
3. **Stack wiring**: compose block (profile `core`, memory limit, `MW_TLS_ENABLED=true`,
   `MANAGEMENT_SERVER_PORT=8081`, `MANAGEMENT_SERVER_SSL_ENABLED=false`, healthcheck on 8081 as mw-crypto),
   `deploy/provision/services.sh` (`SERVICES`, `PKI_SERVICES`, `provision_mw_compliance`: db role,
   `mw.compliance.crypto.client-secret` <- `KC_MW_COMPLIANCE_SECRET`, `mw.compliance.ec.password`), Keycloak
   realm `services` client `mw-compliance` (aud mapper `mw-crypto`) and an `aud-mw-compliance` mapper on
   mw-app-gateway's client, `KC_MW_COMPLIANCE_SECRET` in dev.env.defaults + the shell tests that list the
   secrets, mw-crypto `mw.crypto.authz.purposes` TOKENISE_NID and KEK_COLUMN += mw-compliance, port knob
   `MW_PORT_COMPLIANCE`, README ports table, runbook entry, `:composeCheck`. Kept together in 124b: a
   compose block alone would not start (no Keycloak client, no crypto binding, no EC mock).
4. **Channels**: mw-app-gateway `POST /app/v1/onboarding/...` for the Customer, DSO and Agent apps (agent id from
   the token), images <= 2 MB with content type checked, forwarding to mw-compliance; APISIX route.
5. **Consumers**: mw-notify (SMS on `mw.kyc.completed.v1`) and mw-audit-dw (audit); topics.txt retry groups.
6. **Newman folder 64** `64-ekyc` (self, DSO-assisted, mismatch, EC down) + TRACEABILITY; log-scan.

Ticket 125 (AML feed) builds on this module: parties HMAC-SHA256-tokenised under a Vault key; `client/CryptoClient`
is where `/internal/v1/hmac/sign` (purpose `HMAC_<use>`) goes.

## Security review follow-up (2026-11-20, in 124a)

- IDOR fixed: channel callers name the end user in `X-Mw-Subject-Type` / `X-Mw-Subject-Id`; other ids are 404; only
  mw-admin-api (`mw.compliance.unscoped-clients`) reads unscoped. **124b must make mw-app-gateway send these headers**
  from the app token (customer wallet, or DSO/agent id).
- NID reuse: another wallet holding the NID (EC_CALLED/MATCHED/APPROVED) -> MANUAL_REVIEW, reason NID_IN_USE, no EC
  call, no level. Checked at submit (the gate before the EC call), not at create.
- **Open product decision**: a DOB-only match auto-issues BASIC (ticket rule table). Left as is.
