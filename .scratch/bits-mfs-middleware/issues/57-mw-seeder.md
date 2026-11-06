# 57: mw-seeder: one-shot API seeder for the dev and test stack

**What to build:** A new module `mw-seeder` (`make new-service NAME=mw-seeder`, package `mfs.mw.seeder`), modelled on BitsPay's `~/Desktop/BitsPay/services/seeder` (`SeedRunner.java:52-63`, `SeedServiceImpl.java:509-533`). It seeds the stack once, through the services' public and internal APIs only, so the Newman (ticket 58) and Playwright (ticket 59) suites share one known baseline. Tests still make their own unique data on top of it. The user decided this on 2026-11-06 (plan `check-the-project-deployment-ancient-possum`, Phase B).

- **Runner:** a Spring Boot app with no web server (`spring.main.web-application-type=none`). A `scheduler/SeedRunner` (`ApplicationRunner`) runs ordered steps, then the JVM exits with code 0, or 1 on failure (`SpringApplication.exit`).
  - Each step is a `service/` class with an `id`, a timeout (default 10 min, configurable) and retries on 503/504/connect errors with backoff.
  - The steps are idempotent: they look up before they create, or use idempotency keys (`Idempotency-Key` on money calls). Running the seeder again changes nothing and exits 0.
- **Steps** (check what each target API offers; the current e2e `Given` steps in `tests/e2e/src/test/java/**/steps/*` show what data the scenarios need):
  1. `await-platform-ready`: APISIX, Keycloak and the health of every mw-* service.
  2. `seed-identities`: Keycloak users and roles for the ops/admin personas (maker, checker, viewer), portal org users, and the test app clients, through the Keycloak admin REST API. Credentials come from `deploy/.env`.
  3. `seed-core-accounts`: customers, agents, merchants, billers and opening balances in mw-core-mock through its API. If core-mock has no create API, add a test-only one owned by mw-core-mock, behind a profile, and add it to its OpenAPI contract and `contracts.yml`.
  4. `seed-partner-configs`: through mw-admin-api, creating the change request and approving it with the second persona.
  5. `seed-admin-content`: USSD menus and SMS templates through mw-admin-api.
  6. `write-manifest`: writes the seeded ids and personas (no secrets, masked MSISDNs in logs) to a JSON manifest, served or written to a volume that `tests/api` and Playwright read (e.g. the `seed-manifest` named volume, also printed as a masked log line). Choose the simplest path that the Newman runner and Playwright can both read, and document it.
- **Compose:** a `mw-seeder` service under `profiles: [seed]` in `deploy/docker-compose.yml`, with `restart: "no"`. It `depends_on` every core service being healthy.
- **Rules:**
  - no SQL into other schemas;
  - fake values only;
  - masking through `MaskingPolicy`;
  - constructor injection;
  - `contracts.yml` lists every API it consumes;
  - `ConsumedContractsTest` checks the request DTOs against the OpenAPI schemas.
- **Tests:**
  - unit tests for the runner (order, retry, timeout, fail-fast, exit code) and for each step against fake clients;
  - one `SeedRunnerIT` with WireMock stubs for the APIs, which runs twice and checks the second run makes no create calls.
  - JaCoCo gate as usual.
- **Docs:** the module's `CLAUDE.md`, `MAP.md`, the README quick start (`make seed`), and `docs/runbook.md`.

**Blocked by:** 56

**Status:** done

- [x] `./gradlew test integrationTest` passes in `services/mw-seeder`; `make check-isolation M=mw-seeder` and `make contracts-lint` pass
- [x] On a fresh stack, `make seed` exits 0 and the manifest is readable. A second `make seed` exits 0 and its log shows every step skipped or unchanged
- [x] `make log-scan` is clean after a seed run
- [x] The full `make e2e` still passes on a seeded stack

## Evidence (run 2026-11-06, a5962796)
- mw-seeder test+integrationTest 96/96, JaCoCo gate pass; mw-core-mock tests pass (new test-only `PUT /core-mock/v1/wallets/{walletId}`, off unless `MW_COREMOCK_SEED_API_ENABLED=true`).
- Fresh stack: seed 10 s exit 0 (9 personas, 5 wallets); second seed 8 s, all 6 steps unchanged; log-scan clean (now covers every profile).
- Full `make e2e` replaced by `@p2p` on the seeded stack: 13/13 (user 2026-11-06).
- Manifest: `seed-manifest` volume, copied to `build/seed/manifest.json` by `make seed`. Ops personas need password + TOTP (HmacSHA256, raw UTF-8 secret), see services/mw-seeder/CLAUDE.md.
