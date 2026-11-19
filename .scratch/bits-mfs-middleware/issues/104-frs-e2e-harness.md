# 104: FRS e2e harness and P2P T1–T12 (M7 exit, FRS 10.2, 10.5 phase 1)

**What to build:** The test harness every later use case reuses, and the M7 exit criterion: P2P send money passes all
twelve mandatory scenarios through the app API. Today `tests/api` covers T1, T3, T4, T6, T7 per flow; T5 only by
force-committing a MANUAL_REVIEW txn (folder 13); T8, T11, T12 missing; T10 only in `scripts/chaos.sh`.

**Scope:**
- `tests/api/src/lib.js`: helpers `mockScenario(party, op, behaviour, scope)`, `mockSwitch`, `mockReset`,
  `mockJournal`, `coreBalance(walletId)`, `waitForState(txnId, states, timeoutMs)`; a pre-request reset per folder.
- `tests/api/TRACEABILITY.md`: a UC × T1–T12 matrix (rows UC-01..UC-22, cells = request name or `n/a` with reason);
  `scripts/check-api-traceability.sh` checks that every non-`n/a` cell names an existing request.
- New folder `40-p2p-frs` (P2P is core-only, so partner-centric scenarios use the core as the "external" party):
  - T1 happy path with balance, fee, VAT and commission checked against the seed tariff (ticket 94), SMS in SMSC journal;
  - T2 core business rejection, bn and en text; T3/T4 core timeout (mock control nextN) then inquiry commit/release;
  - T5 core never answers → MANUAL_REVIEW + alert event (ticket 96) (in `90-slow` if it waits ≥ 2 s);
  - T6 same Idempotency-Key twice; T7 duplicate core event (DUPLICATE_CALLBACK); T8 core circuit open → fast fail;
  - T9 insufficient balance and limit → FAILED, journal shows no external call; T10 restart: test-only orchestrator
    hook or documented `scripts/chaos.sh CHAOS_TARGETS=mw-orchestrator` run with ledger check;
  - T11 and T12 recorded `n/a` until tickets 125 and 127, with the ticket number in the matrix.
- Re-run `node tests/api/build_collection.mjs`; `tests/api/README.md` documents the helpers.

**FRS IDs covered:** 10.2 T1–T12 (P2P), 10.3 (e2e suite part), 10.5 phase 1 exit

**Blocked by:** 96, 98, 103

**Status:** open

- [ ] `scripts/test/test_check_api_traceability.sh` covers the matrix rule (a missing request name fails)
- [ ] `./gradlew :scriptTests :contractsLint` green
- [ ] With a fresh stack (`deploy/reset.sh`, `up` with seed, default `dev.env.defaults` timers): `scripts/api-test.sh "40"` green, and `"40" 1` for the slow T5
- [ ] Whole API suite green (M7 gate) after the folder runs (it resets mocks first)
- [ ] **M7 gate:** `./gradlew clean build`, fresh stack, `scripts/api-test.sh`, `scripts/ui-e2e.sh`, `scripts/tester-e2e.sh`, `scripts/log-scan.sh` green
