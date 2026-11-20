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

## 124b (items 1-4 done 2026-11-20 on ticket/124b-compliance-wiring; 5-6 moved to 124c)

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
- ~~Open product decision: a DOB-only match auto-issues BASIC.~~ Decided in 124b (user): a DOB-only match goes to
  MANUAL_REVIEW (reason NAME_MISMATCH, no level, no event); only a FULL match is issued. Done in 124b.
- Subject headers from the app token: done in 124b (mw-app-gateway `service/OnboardingService`).

## 124c (open; split from 124b at the time cap)

124b delivered items 1-4 above (core-mock `PUT /core/v1/accounts/{walletId}/kyc-level` + MATCHED -> core -> APPROVED
with the event after the core call; DOB-only -> MANUAL_REVIEW; EC NID mock `ec` in mw-simulators with contract
`ec-nid.v1`; compose/provision/Keycloak/mw-crypto wiring; mw-app-gateway `/app/v1/onboarding/*` + APISIX route
`app-v1-onboarding`). Left:

1. **mw-notify consumer** of `mw.kyc.completed.v1` (owner mw-compliance): SMS to the wallet on APPROVED
   ("your account is verified", Bangla + English templates, seeded like the other templates), Spring Kafka
   listener in `messaging/`, inbox dedup on eventId, retry topics + DLQ; consumer manifest in mw-notify
   `contracts.yml` with a fixture; retry groups in `deploy/kafka/topics.txt`.
2. **mw-audit-dw consumer** of `mw.kyc.completed.v1`: an audit row per decision (applicationId, walletId masked,
   channel, agentId, kycLevel, decidedAt), consumer manifest, topics.txt retry group.
3. **Newman folder 64-ekyc** (`tests/api`, `build_collection.mjs`, `TRACEABILITY.md` with UC-01 / CMP-04 tags):
   self-onboarding (customer app: create, three images, submit -> APPROVED, core `kycLevel` FULL), DSO-assisted
   (subject AGENT), mismatch (NID ending 111 -> MISMATCHED), not found (000), EC down/timeout (999 -> 504
   MW-TMO-EC_NID, then resubmit), DOB-only -> MANUAL_REVIEW, another subject's id -> 404; then `scripts/log-scan.sh`
   (no NID / name in any log). Needs the stack, so it was not run in 124b; nothing in 124b was run live.
4. **Seeder**: the onboarding test wallets (a customer without KYC whose MSISDN the app tokens carry, a DSO and an
   agent) if the existing seeded personas do not fit the Newman flows.
5. Follow-ups found in 124b (not blocking):
   - mw-compliance `ApplicationView.retryable` is true only for FAILED; a MATCHED application whose core call
     failed (errorCode set) can be resubmitted but reports `retryable: false`. Make it true for MATCHED with an
     errorCode (compliance.v1 description to match).
   - `KycLevel.BASIC` stays in the enum and in the `mw.kyc.completed.v1` schema, but nothing issues it any more.
   - The real core must provide the kyc-level endpoint (ADR D31 core over REST); only core-mock has it.
