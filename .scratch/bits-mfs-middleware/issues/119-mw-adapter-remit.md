# 119: mw-adapter-remit: UCB IMT inbound remittance (FRS UC-16, EXT-15, FL-10)

**What to build:** A new adapter service `mw-adapter-remit` for inbound international remittance from UCB IMT. It
implements the FRS 5.3 inbound-credit template:
- the partner calls the middleware (mTLS or signature checked at the edge);
- partner-reference dedup;
- beneficiary validation;
- an AML screening hook;
- a wallet credit from the UCB settlement wallet;
- a reply carrying the Upay reference.

It supports both lookup (beneficiary name by MSISDN before sending) and push credit (FL-10), plus status.

**Principles:**
- Once accepted, never silently dropped: a credit failure after acceptance goes to MANUAL_REVIEW (5.3 step 6).
- Today `REMITTANCE` inbound callbacks are dropped by `PartnerInboundService`; this ticket makes the orchestrator
  handle them.
- The AML screen calls mw-compliance (ticket 125) when it exists. Until then it is a configurable pass-through
  (`mw.remit.aml-screen=off`) that ticket 125 switches on.

**Scope:**
- `scripts/new-service.sh mw-adapter-remit`, then copy the `partner/` engine (package `mfs.mw.adapter.remit.partner`).
  Family `remit`.
- Inbound REST API (`contracts/api/mw-adapter-remit/remit-inbound.v1.openapi.yaml`):
  `POST /remit/v1/beneficiary-lookup`, `POST /remit/v1/credits`, `GET /remit/v1/credits/{partnerRef}`.
  - The APISIX route uses mTLS or signature per ticket 100's client registry.
  - Requests carry nonce + timestamp (PLT-008).
- The adapter publishes `mw.partner.inbound.v1` (kind REMITTANCE, existing contract).
- Orchestrator: a `RemitInFlow` (`REMIT_IN`):
  - beneficiary check: exists, active, KYC level allows the amount, inbound limits (core WAL-01/05);
  - then the AML hook, then core `POST /credits`, then reply;
  - a status inquiry by partnerRef.
- Lookup returns the masked beneficiary name only (first initial + length) to the partner.
- `services/mw-simulators`: `UcbImtMock` calls the middleware on demand (`POST /mock-control/ucb-imt/trigger` with
  amount, MSISDN, sender, country), with duplicate and late re-sends (MCK-06).
  - Test console page "Send remittance".
- Compose block with a memory limit; 12 GB budget.
- Docs: service `CLAUDE.md`, `contracts.yml`, README.

**FRS IDs covered:** UC-16, EXT-15, FL-10, PLT-014, 5.3 inbound template

**Blocked by:** 95, 97, 102

**Status:** open

- [ ] `@WebMvcTest` for the inbound controller (validation, nonce replay refused)
- [ ] `RemitInFlowIT`: credit succeeds and the Upay ref is returned; an inactive or limit-exceeded beneficiary is refused with no credit
- [ ] A duplicate partner reference is processed once (T7); a credit failure after acceptance goes to MANUAL_REVIEW (alert from ticket 96)
- [ ] T1, T2, T5, T6, T7, T9, T10, T12 (recon later in 126) for REMIT_IN; T3/T4/T8 not applicable to an inbound flow: note this in TRACEABILITY
- [ ] `./gradlew :mw-adapter-remit:build :mw-orchestrator:build :mw-simulators:build :contractsLint :composeCheck :checkIsolation` green with Docker
- [ ] API suite folder `53-remittance-in` passes
