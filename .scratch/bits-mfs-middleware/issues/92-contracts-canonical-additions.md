# 92: Canonical model additions for the FRS (FRS 2.2, 3.7)

**What to build:** The few additions to the shared contracts that the FRS needs and the repo lacks (plan decision 3).
No renames: everything existing keeps its name.

**Scope:**
- `contracts/common/defs.v1.schema.json` (owner mw-orchestrator): `TxnType` + `NPSB_OUT`, `NPSB_IN`, `B2B_TRANSFER`;
  `Amount`-typed optional `vat` and `commission` beside `fee`; `Party.type` unchanged.
- `contracts/events/mw-orchestrator/mw.txn.status.v1.schema.json`: optional `completedAt`, `partnerStatusCode`, `vat`, `commission`.
- `mw.txn.request.v1`: document `metadata.mno` (GP, ROBI, TELETALK, BL) as the USSD MNO carrier (FRS USSD_GP..BL channels).
- Error envelope: optional `timestamp` (UTC ISO-8601) in every service's `dto/ErrorEnvelope` + `exception/GlobalExceptionHandler`
  and in the envelope schema; root `CLAUDE.md` error-model line.
- Samples under `contracts/events/.../samples/` (valid/invalid) for each new field and enum value.
- Each service's DTO enums/records that mirror `TxnType` (`mw-orchestrator`, `mw-app-gateway`, `mw-ussd`, `mw-checkout`,
  `mw-portal-api`, `mw-admin-api`, `mw-notify`, `mw-audit-dw`) accept the new values (rejected as unsupported by the orchestrator
  until their flows exist: `MW-VAL-010`).

**FRS IDs covered:** 2.2 (txnType, fee/vat/commission, externalRef status code, timestamps), 3.7 (envelope timestamp)

**Blocked by:** 89

**Status:** open

- [ ] Schema tests first: new samples valid, an unknown enum value invalid; `ProducedContractsTest` / `ConsumedContractsTest` green in each module
- [ ] Unit test per service: an error response carries `timestamp`; the orchestrator rejects `NPSB_OUT` with `MW-VAL-010`
- [ ] `./gradlew :contractsLint` and `./gradlew build` green with Docker (contract change re-runs every consumer module)
- [ ] `scripts/api-test.sh "00, 01"` pass on a fresh stack
