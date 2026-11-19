# 137: FRS end-to-end completion: T1–T12 for every financial use case, collections per channel, masked test data (FRS 10.2, 10.3)

**What to build:** Ticket 104 builds the harness and P2P, and each flow ticket brings its own folder. This ticket
completes FRS 10.2 for **every** financial use case and delivers the FRS 10.3 test assets.

**Principles:**
- Each scenario is forced through the unified `/mock-control` API (tickets 102/103) and asserted on core-mock balances
  and ledger (no double debit, zero-sum per txnId).
- Data triggers are not used where a scenario must be scoped.
- `/mock-control/reset` runs before each folder, so folders are independent and repeatable on one stack.
- Scenarios whose time is a real wait (T5 MANUAL_REVIEW, T3/T4 on long inquiry steps, T12 next-day recon) go in
  `90-slow` or are tagged slow (ADR D19 amendment) and run only with `ALL=1` at the gate.

**Scope:**
- **Matrix:** in `tests/api/TRACEABILITY.md`, every financial use case × T1–T12. Each cell is a request name with its
  requirement tags, or `n/a` with a reason; for example T7 has no callback on P2P. `scripts/check-api-traceability.sh`
  fails on an empty cell. Use cases: UC-04, 05, 06, 07, 08, 09, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20.
- **Missing cells:**
  - T8 circuit open: fallback where configured, else EXT-503 → `MW-SYS-PARTNER_CIRCUIT_OPEN`;
  - T9 insufficient balance and limit for every flow;
  - T10 restart mid-saga (Newman: `docker compose restart` hook via `tests/api/run.sh`, or `scripts/chaos.sh`
    single-target mode; chaos is not run at gates, user decision);
  - T11 AML HOLD on each flow family;
  - T12 recon next day;
  - T2 bn and en error text checked on every flow.
- **Collections:** `node tests/api/build_collection.mjs --per-channel` writes one Postman collection per channel
  (customer, agent, merchant, distributor, DSO, partner apps; USSD; checkout; org/SP portals; partner inbound; admin),
  so testers can run them by hand. No Bruno.
- **Masked test-data generator:** `services/mw-seeder` gains a `generate` mode (ENV-12) that writes versioned CSV/JSON
  seed sets with fake but well-formed MSISDNs, NIDs and accounts. It is deterministic by seed and covers every wallet
  type and KYC level, and is used by ticket 103's reset.
- **Coverage report:** JaCoCo report per module (`./gradlew :mw-x:jacocoTestReport`). It is reported, not gated
  (ADR D19 kept, FRS NFR-08 target 80 % noted). The summary goes in `docs/testing-guide.html`.

**FRS IDs covered:** 10.2 T1–T12 for all financial use cases, 10.3 (e2e, collections, masked generator, coverage report), 10.4 criterion 2

**Blocked by:** 104, 106, 112, 114, 115, 116, 117, 118, 119, 120, 121, 122, 125, 127, 129

**Status:** open

- [ ] `scripts/check-api-traceability.sh` extended first: fails on any UC×T cell left empty; currently red, then green
- [ ] Every financial use case's folder passes T1, T2, T3, T4, T6, T7 (where a callback exists), T8, T9 and T11 on a fresh seeded stack (`scripts/api-test.sh`)
- [ ] T5, T10 and T12 pass under `scripts/api-test.sh "" 1` (slow); core-mock ledger invariant check passes after the run
- [ ] `mw-seeder generate` output is identical for the same seed and passes the masking check (`scripts/log-scan.sh` rules applied to the files)
- [ ] One collection per channel is generated and each runs green in Newman
- [ ] `./gradlew :mw-seeder:build :scriptTests :contractsLint` green with Docker
- [ ] API suite folder `71-frs-matrix` (cross-flow idempotency and duplicate-callback sweep) passes (`scripts/api-test.sh 71`)
