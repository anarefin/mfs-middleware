# 138: FRS acceptance gate for the mock build (FRS 10.4 criteria 1–7, phase 7 hardening)

**What to build:** The closing gate of run 89–138. It proves each FRS 10.4 acceptance criterion on a fresh stack. It
also fixes what the run turns up (each fix test-first and committed on its own), updates the perf scripts (**not
run**: user decision, no load tests), runs the security pass and brings the docs in line.

**Principles:**
- Fresh stack only:
  1. `deploy/reset.sh`;
  2. `cp dev.env.defaults .env` if missing;
  3. `./gradlew clean build`;
  4. `docker compose --profile core --profile seed --profile tester up -d --build --wait` from `deploy/`.
  The `:composeCheck` budget is the 12 GB from ADR D32.
- Ask the user before any run expected to take over 30 minutes (user memory). Slow-wait tests (`-PwithSlow`) run only
  if the user says so.
- Evidence (command, result, counts) goes in a "Run 89–138 gate" row of `issues/DEPENDENCIES.md`.

**Scope:**
- Run every gate command and fix failures.
- Update `tests/perf` (Gatling) scenarios for top-up, send money and bill pay against the new REST core and realistic
  mock latency (SLOW scenario), compile only.
- `scripts/security.sh`, covering the new modules and images, and `scripts/log-scan.sh` extended with the NID and full
  PAN patterns.
- Docs: `README.md` (new services, profiles, knobs), `docs/runbook.md`, `docs/demo.md` (one demo per milestone
  M7–M13), `docs/tutorial/`, `docs/concepts/` (Redis, Vault, new adapters), `docs/frs-traceability.md` final pass
  (every FRS ID: done / deviation with ADR).

**FRS IDs covered:** 10.4 criteria 1–7, 10.5 phase 7

**Blocked by:** 137

**Status:** open

- [ ] **Criterion 1** (every UC-01..22 end to end on local Docker Compose): `scripts/api-test.sh` all folders green, `scripts/tester-e2e.sh` green (one walk per UC including the ATM, IMT and card mock pages), UC-22 proven by folder `72-frs-gate` (recon day plus DW batch file validated by the DW drop validator)
- [ ] **Criterion 2** (T1–T12 for every financial UC): ticket 137's matrix has no empty cell, and `scripts/check-api-traceability.sh` plus the full API suite (`ALL=1` only if the user agrees) are green
- [ ] **Criterion 3** (any connector switched mock → another endpoint by console only): Playwright `@partners @credentials` switches one connector of each family (telco, billpay, bank, card, remit, atm, interop, merchant) to a second simulator base URL; the next txn goes there; no restart
- [ ] **Criterion 4** (new REST biller by the Upay team from console + guide): ticket 136's `e2e/94-add-biller.spec.ts` green on the fresh stack, and the guide followed by hand by someone not on the team (user's box; recorded, not automated)
- [ ] **Criterion 5** (OpenAPI/WSDL for every exposed endpoint; mock triggers documented): `docs/api-catalogue.md` check and `MockTriggerDocsTest` green; Swagger UI and WSDL listing reachable in the dev stack
- [ ] **Criterion 6** (no PIN, full PAN or unmasked NID in any log or audit view): `scripts/log-scan.sh` clean after the full suite; an audit-viewer and txn-detail sweep (Playwright `@audit`) finds no unmasked NID; `scripts/security.sh` with no open HIGH/CRITICAL beyond accepted entries with expiry
- [ ] **Criterion 7** (one full mock business day reconciles with zero unexplained differences): run the day (seeded traffic, then MT940 and partner settlement files from ticket 123 with **no** injected mismatches); `mw-recon` shows only MATCHED, and the trust position (REC-07) equals the sum of e-money balances on core-mock
- [ ] `./gradlew clean build` green (cold run time recorded); `:composeCheck`, `:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:scriptTests` green
- [ ] `scripts/smoke.sh`, `scripts/edge-test.sh`, `scripts/ui-e2e.sh` green
- [ ] `tests/perf` compiles (`./gradlew -p tests/perf compileGatlingJava` or its equivalent task); **not run** (user: no load tests); chaos not run
- [ ] Gate row with evidence added to `issues/DEPENDENCIES.md`; open items listed for the user
