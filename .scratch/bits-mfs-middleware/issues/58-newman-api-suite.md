# 58: Newman API suite replaces the Cucumber e2e suite

**What to build:** A Postman collection run with Newman under `tests/api/`, modelled on BitsPay's `~/Desktop/BitsPay/tools/postman/` (`build_collection.py`, `lib.js`, `run.sh`, environment file). It replaces the 132-scenario Cucumber suite in `tests/e2e`, which is then deleted. With the stack on short timers (`make up TIMERS=fast`, ticket 56) and the seed baseline (ticket 57), the default run should finish in under 10 minutes. The user decided this on 2026-11-06 (plan `check-the-project-deployment-ancient-possum`, Phase C).

- **Layout under `tests/api/`:**
  - `build_collection.mjs` (Node; no new runtime) generates `mfs-mw.postman_collection.json` (committed) from source files under `src/<NN-folder>/*.js`. Each source file holds the requests with their pre-request and test scripts;
  - `lib.js` is embedded as the collection variable `lib`, and every script starts with `const MW = eval(pm.collectionVariables.get('lib'))`;
  - `local.postman_environment.json`;
  - `run.sh`;
  - `README.md`;
  - `.local.postman_environment.json` (the run state) is gitignored.
- **`lib.js`** brings over the logic of the Cucumber `support/` helpers:
  - `Http` (APISIX base URL, TLS, correlation ID);
  - per-principal login with a token cache: Keycloak tokens, app-gateway device binding and signature, OTP sandbox code;
  - RSA-OAEP SHA-256 PIN encryption with the core public key. node-forge is pinned by URL and SHA-256 in the collection, as BitsPay's `lib.js` does; or ask the user;
  - HMAC signing for partner callbacks and checkout IPN checks;
  - `poll(fn, tries, delayMs)`;
  - unique data from the clock (references, MSISDNs inside the sandbox ranges, NIDs).
- **Folders:**
  - `00 Health & seed check`;
  - one folder per current feature file (23, same order, names from the `Feature:` lines);
  - `90 Slow`: what is still slow with short timers, such as the large bulk batch and the full retry-to-DLQ path. Skipped unless `ALL=1`;
  - `99 Destructive`, if needed.

  The former `@tsq` scenarios run in the normal folders.
- **Steps that used the Docker CLI:**
  - the audit reads through `docker compose exec postgres psql` (`tests/e2e/.../support/Stack.java:85-121`, `AuditTrail`) move to the audit query API (mw-audit-dw, or through mw-admin-api);
  - REVERSAL through `kafka-console-producer` (`support/TxnRequests`) moves to a test-only HTTP endpoint on the owning service that publishes the same command. It is enabled only by a profile or env flag and exposed in APISIX only with `MW_E2E_ROUTES=1`. The owning service adds it to its OpenAPI contract and `contracts.yml`, and tests it with `@WebMvcTest`.
  - No Newman request may need Docker.
- **Fixed sleeps:** replace the Cucumber ones (`AdminSteps.java:82`, `PartnerConfigSteps.java:61,109`, `PortalSteps.java:160`, the 15 s "no further IPN" check) with `poll()` on an observable state, or with short negative windows under `TIMERS=fast`.
- **Runner** `run.sh [--folder "…"] [--all]`:
  - reads `deploy/.env`;
  - re-applies the test-only APISIX routes (as `scripts/suite.sh enable_test_routes` does today) once;
  - runs `npx --yes newman@<pinned> run … -e .local… --export-environment … --delay-request 0 --reporters cli,junit --reporter-junit-export build/newman-report.xml`;
  - blanks the secrets in the state file.
- **Traceability:** each request name starts with the requirement IDs it covers, using the same IDs as the Cucumber tags. `scripts/check-api-traceability.sh` replaces `tests/e2e`'s `TraceabilityTest`, with the same rule and data source. It runs in `make contracts-lint`, and its self-test is `scripts/test/test_api_traceability.sh`.
- **Parity:** before deleting `tests/e2e`, add `tests/api/PARITY.md`, mapping each of the 132 scenarios (plus outline rows) to a Newman request, or marking it dropped with a reason. Every scenario must be mapped or dropped. Only then delete `tests/e2e`.
- **Make and docs:**
  - `make api-test [FOLDER=] [ALL=1]` replaces `make e2e` and `make e2e-changed`. Drop or rework the `e2e-changed` knobs (`E2E_SCOPE`, `MW_UP_ONLY`);
  - `make chaos` load becomes Newman folders (`CHAOS_LOAD_FOLDERS`);
  - `make security` with `SECURITY_LIVE=1` runs after `make api-test`;
  - update the root `CLAUDE.md` (test table, definition of done), `README.md`, `docs/demo.md` (the milestone demos use folders), `docs/runbook.md`, `MAP.md`, every service `CLAUDE.md` that names `make e2e`, `scripts/test/test_repo_docs.sh` expectations (tags become folders), and the memory note on the fast gate policy.

**Blocked by:** 57

**Status:** todo

- [ ] `make reset && make build && make up TIMERS=fast && make seed && make api-test` passes, and the default run takes under 10 minutes (paste the time)
- [ ] A second `make api-test` on the same stack, without reset, passes (no leftover data clashes)
- [ ] `make api-test ALL=1` passes
- [ ] `PARITY.md` accounts for every scenario. `scripts/check-api-traceability.sh` fails when a request's requirement tag is removed (show it once, then revert)
- [ ] `make log-scan` is clean after the run; `make test-scripts` and `make contracts-lint` pass; `tests/e2e/` is gone and `git grep 'make e2e'` finds nothing outside the ADRs
