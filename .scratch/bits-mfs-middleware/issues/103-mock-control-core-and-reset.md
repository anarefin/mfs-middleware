# 103: Mock control in mw-core-mock, global reset, versioned seeds, prod exclusion (FRS MCK-08, MCT-07, MCT-08, 7.3)

**What to build:** Bring mw-core-mock onto the unified mock-control API from ticket 102 (engine **copied**, module
isolation), add one-call reset across all mocks, and make seed data versioned files. Today mw-core-mock has
`POST|DELETE /core-mock/v1/faults` (DELAY, DROP_REPLY, DUPLICATE_REPLY, REJECT, scoped by commandType/txnId, `times`),
seed generated in code (`WalletSeeder`), and nothing switches mock control off by build profile.

**Scope:**
- mw-core-mock: copy `service/mockcontrol/` from mw-simulators (package renamed); party `core`, operations = the WAL
  operations of ticket 93 (hold, commit, release, transfer, credit, reversal, query, charges, limits). Existing `/faults`
  becomes an alias for one release.
- Seeds: `src/main/resources/seed/v1/{wallets,tariffs,limits,system-wallets}.csv|json` (counts from ticket 94:
  1,000 customers, 50 agents, 10 distributors, 20 merchants, 3 orgs, system and settlement wallets); loader replaces
  `WalletSeeder` generation; `mw-seeder` personas stay consistent with these files (`build/seed/manifest.json`).
- mw-simulators seeds (bills, bank accounts, NID records later) also move to `src/main/resources/seed/v1/`.
- Global reset (MCT-07): `POST /mock-control/reset` on mw-simulators fans out to every local party and to mw-core-mock
  (`POST /mock-control/reset` there: truncate ledger/holds/command log, reload seed), journal cleared; returns per-party
  result. Test-only APISIX route `/test/mock-control/*` (`MW_E2E_ROUTES=1`).
- MCT-08: mock-control beans only under profile `mock-control`; production compose/profile never sets it; a startup
  guard fails if `mock-control` is active with `MW_ENV=prod`; `scripts/check-mock-control-prod.sh` (+ shell test in
  `scripts/test/`) fails when a non-test compose service enables the profile; added under root `check`.
- Docs: `README.md` (new script), mw-core-mock and mw-simulators `CLAUDE.md`, `docs/testing-guide.html` reset step.

**FRS IDs covered:** MCK-08, MCT-07, MCT-08, MCK-04 (core part), 7.3 "same scenario controls"

**Blocked by:** 102, 94

**Status:** open

- [ ] `CoreMockScenarioIT`: TIMEOUT on hold for nextN=1, then success; BUSINESS_ERROR(INSUFFICIENT_BALANCE) for one MSISDN
- [ ] `SeedLoaderTest`: counts per type; a changed file version reloads on reset
- [ ] `GlobalResetIT`: after reset every party's journal is empty, scenarios cleared, core balances equal the seed
- [ ] `ProdGuardTest`: profile `mock-control` + `MW_ENV=prod` fails to start; `scripts/test/test_check_mock_control_prod.sh`
- [ ] `./gradlew :mw-core-mock:build :mw-simulators:build :mw-seeder:build :contractsLint :scriptTests` green with Docker
- [ ] With the stack up: `39-mock-control` reset requests; full API suite still green after a reset at its start
