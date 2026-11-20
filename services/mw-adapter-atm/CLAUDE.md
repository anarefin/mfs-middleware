# mw-adapter-atm

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.adapter.atm`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Token (FRS FL-11)

The one-time ATM token is a secret. This adapter never stores it and never logs it. Verify reads
the element only so the schema can require it; the check (HMAC, expiry, single use, lockout) is
ticket 120b. An MSISDN on the SOAP body is not written to the inbound table and is not logged.

## Responsibilities

- UCB ATM adapter (ticket 120a, FRS UC-17, EXT-16, PLT-025, PLT-050). Family `atm`. Inbound SOAP
  on Spring WS (CXF is the outbound partner engine's, not this servlet): `VerifyTokenAndDebit`,
  `ConfirmDispense`, `ReversalAdvice`, `CashIn`. The contract is
  `contracts/api/mw-adapter-atm/atm-soap.v1.xsd`; the WSDL is generated at
  `/partner/ucbatm/ws/v1/atm.wsdl`. Every call needs a WS-Security UsernameToken (PasswordText);
  TLS is the edge's (ticket 100). A bad token, a bad document or an unknown cash-out is a SOAP
  fault whose detail is the error envelope (`MW-AUTH-001`, `MW-VAL-001`, `MW-VAL-ATM_UNKNOWN_TXN`).
- Each call is recorded once per (operation, `atmTxnRef`) in `atm_inbound` (own schema `atm_ref`).
  A repeat gets the first `txnId` again. VERIFY mints the cash-out `txnId`; CONFIRM and REVERSAL
  of that `atmTxnRef` reuse it. The answer is `ACCEPTED`.
- **Contract gap (ticket 120b).** The call is not published. `mw.partner.inbound.v1` (owner
  mw-orchestrator) only allows kinds `CALLBACK`, `REMITTANCE` and `NPSB_CREDIT` and has no debit
  party, so these four operations do not fit. `dto/PartnerInbound` is the shape 120b should
  publish once that contract grows a family `atm` branch. Token issue, `ATM_CASH_OUT` /
  `ATM_CASH_IN` and the hold live there too.
- Outbound, today, is only `STATUS_QUERY` of a cash-out (`messaging/AtmRequestHandler` on
  `mw.partner.request.atm.v1`, family `atm`, txnType `ATM_CASH_OUT` or `ATM_CASH_IN`). The dev
  seed `ucbatm` signs hmac-sha256 and calls `GET /ucbatm/v1/dispenses/{reference}` on
  mw-simulators' `UcbAtmMock`. DISPENSED is SUCCESS, NOT_DISPENSED is FAILED
  `MW-PTR-UCBATM-NOT_DISPENSED`, 404 is NOT_FOUND, a 5xx is UNKNOWN. The reply is
  `mw.partner.reply.v1` through the outbox.
- Stable partner reference (`service/PartnerReferenceService`, table `partner_reference`):
  `BITS` + txnId hex.
- Dev provisioning (`provision_mw_adapter_atm` in `deploy/provision/services.sh`): role
  `adapter_atm_rw`, schema `atm_ref`, `secret/mw-adapter-atm` (DB password and
  `mw.atm.inbound.ws-password` from `UCBATM_WS_PASSWORD`), `secret/mw-adapter-atm/ucbatm`
  (`hmac-secret` from `UCBATM_HMAC_SECRET`, the same value as mw-simulators'
  `sim.callback-secrets.ucbatm`). Compose service `mw-adapter-atm` (profile `core`, 384 MiB,
  Hikari pool 2, `MW_PORT_ADAPTER_ATM` 8100).

## Partner engine (`partner` package; ADR D28)

The engine, its guardrails and its Kamelets are billpay's (mw-adapter-billpay CLAUDE.md
"Partner engine"). The main `partner/` tree, `src/main/resources/kamelets` and `partner/`
resources are identical in the adapters apart from the package
(`scripts/test/test_partner_engine_copies.sh`).

- **Family** `atm` (mw-admin-api accepts it since ticket 120a, Flyway V20, keeping merchant and
  card). Route status on `mw.partner.route.status.v1`, batch SFTP routes on `mw.partner.recon.v1`.

## Contracts owned

- `contracts/api/mw-adapter-atm/atm-soap.v1.xsd` (the WSDL is generated from it).

## Contracts used

- Machine-readable list: `contracts.yml`. No inbound event: see the contract gap above.
- `events/mw-orchestrator/mw.partner.request.v1` (family `atm`) in; own type `dto/PartnerRequest`.
- `events/mw-orchestrator/mw.partner.reply.v1` (family `atm`) out; own type `dto/PartnerReply`.
- `events/mw-audit-dw/mw.audit.event.v1` out (`partner.call.completed`).
- `events/mw-admin-api/mw.config.changed.v1` in (PARTNER route records, family `atm`).
- `events/mw-adapter-billpay/mw.partner.route.status.v1` and `mw.partner.recon.v1` out.

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-adapter-atm:test` | every test in one JVM; `*IT` classes skip without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-adapter-atm:build` | `test`, then the boot jar `build/libs/mw-adapter-atm.jar` |
| `./gradlew :mw-adapter-atm:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-adapter-atm` | rebuild this service's image (`mfs-mw/mw-adapter-atm:local`) and restart it |
