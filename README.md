# Bits MFS middleware (MVP)

The integration layer between Bits's channels (customer, agent and merchant apps, USSD, web
checkout, org and SP portals) and the core wallet, built on Spring Boot 4, Apache Camel, Kafka,
APISIX and PostgreSQL 18. The MVP runs the whole system on one Docker Compose host against a
mocked core (`mw-core-mock`) and simulated partners (`mw-simulators`): 14 Spring Boot services in
one Gradle build, the admin console, and one hand-written compose file.

- New to the project: [docs/tutorial/index.html](docs/tutorial/index.html), a guided tour for a new
  developer (the architecture in plain English with diagrams, every feature's flow, a hands-on first day);
  then [docs/concepts/index.html](docs/concepts/index.html), one page per component (APISIX, Keycloak,
  OpenBao, the HSM, the test doubles, Camel, Resilience4j, ...) and how this project uses it.
- Design: [spec/bits-mfs-middleware-spec.md](spec/bits-mfs-middleware-spec.md) (the "MVP scope"
  section wins over sections 1–25 for the MVP).
- Why it is built this way: [docs/adr/README.md](docs/adr/README.md), one record per decision.
- The Upay FRS build (tickets 89–138, ADRs D29–D33): [docs/frs-traceability.md](docs/frs-traceability.md)
  maps every FRS requirement id and name to the repo name, its status and the ticket. Decided there and
  not built yet: Redis replaces Valkey (D29, ticket 90), HashiCorp Vault replaces OpenBao (D30, ticket 91),
  the core over REST (D31), new services `mw-adapter-card`, `-remit`, `-atm`, `-interop`, `-merchant`,
  `mw-compliance`, `mw-recon`, `mw-data-export` (D32) and one mock control API (D33). Until each ticket
  lands, this README describes the stack as it runs (Valkey, OpenBao).
- Running the MVP host: [docs/runbook.md](docs/runbook.md).
- Milestone demos M0–M6: [docs/demo.md](docs/demo.md).
- Testing every feature by hand from a browser (app flows, USSD, bank, failures, checkout, bulk,
  ops console), step by step: [docs/testing-guide.html](docs/testing-guide.html), with the dev/test-only
  test console (`scripts/tester.sh`, https://localhost/tester).
- The same start-up as this README, as a step-by-step page with timings:
  [deploy/RUNNING.html](deploy/RUNNING.html).
- Conventions for contributors and coding agents: [CLAUDE.md](CLAUDE.md) and the `CLAUDE.md` of
  each module.

**Contents:** [Prerequisites](#prerequisites) · [Quick start](#quick-start-clean-host-under-15-minutes) ·
[Rebuild one service](#rebuild-one-service-after-a-code-change) · [Stop and reset](#stop-and-reset) ·
[Build and test](#build-and-test) · [Test suites](#test-suites-against-the-running-stack) ·
[Full test pass](#recommended-full-test-pass) · [Scripts](#scripts) · [Memory knobs](#memory-knobs) ·
[Troubleshooting](#troubleshooting) · [What runs](#what-runs)

## Prerequisites

The quick start needs nothing but this repo and the tools below. Everything else (Kafka,
PostgreSQL, Valkey, Keycloak, OpenBao, APISIX, GreenMail, every service and the admin console)
runs in Docker.

| What | Why | Check |
| --- | --- | --- |
| Linux or macOS host, 4+ CPU cores, 16 GB RAM, 40 GB free disk | the stack's memory limits add up to about 8.0 GB (8176 MiB) (`./gradlew :composeCheck` caps them at 8 GB: a laptop demo footprint, never a load test host); images and Gradle caches take about 15 GB | `nproc` / `sysctl -n hw.ncpu` |
| Docker Engine with the Compose v2 plugin, 2.20 or newer (profiles, `up --wait`, `wait`); on macOS Docker Desktop with at least 8 GB memory for its VM | every container, and the Testcontainers tests of `./gradlew build` | `docker compose version` |
| JDK 25 (Temurin or any other build) on `PATH` or in `JAVA_HOME` | the images do not compile code: they copy `build/libs/mw-<module>.jar`, built on the host. No Gradle install needed: the repo ships `./gradlew` | `java -version` |
| bash, git, curl, jq | `scripts/`; jq for `scripts/smoke.sh`, `scripts/edge-test.sh`, `scripts/security.sh` | `jq --version` |
| Node.js 22+ with npx | `scripts/api-test.sh` (Newman, pinned, fetched by npx; needs access to registry.npmjs.org and cdnjs.cloudflare.com), `scripts/edge-test.sh` (its mw-auth logins, `scripts/lib/mw-auth-token.mjs`), the `contractsLint` and `composeCheck` repo checks of `./gradlew build`; the admin console builds inside Docker | `node --version` |
| Node.js 24 + pnpm (optional) | only the consoles' own tests: `pnpm test` in `ui/mw-admin-ui` / `ui/mw-test-console`, `scripts/ui-e2e.sh` and `scripts/tester-e2e.sh` (Playwright against the running stack; Chromium is installed on first use) | `pnpm --version` |
| Internet access on the first run | images, the Gradle distribution and dependencies, the console's pnpm packages | |
| Host port 443 free, and the direct ports below | APISIX publishes 443 (-> 9443); every service and platform container also publishes on 127.0.0.1 (table under "Host ports") | `lsof -i :443` |

```sh
docker --version && docker compose version
java -version     # must report 25
node --version    # 22 or newer
```

## Quick start (clean host, under 15 minutes)

Acceptance criterion 6 of the MVP (OWN-06): a clean host brings up the full stack with seeded data
in under 15 minutes, from the repo alone. Run Gradle from the repository root and every
`docker compose` command from `deploy/`.

**1. Build and test** (repo root)

```sh
./gradlew clean build
```

The full gate: every module's compile, tests and boot jar, plus the repo checks. With Docker
running the Testcontainers tests run too (a cold run takes about 3 minutes, sized for an 8-core host;
an unchanged rebuild comes from the build cache in seconds). To only get the jars for the images,
`./gradlew bootJar` takes well under a minute.

**2. Copy the dev settings** (first time only)

```sh
cd deploy && cp dev.env.defaults .env
```

`deploy/dev.env.defaults` is committed: fixed, obviously fake dev values (the tester logins, every
other credential, the USSD source lists and the short test timers). Compose reads `deploy/.env` by
itself, so no command names an env file. `scripts/smoke.sh`, `scripts/tester.sh`, `scripts/suite.sh`
and `deploy/reset.sh` copy it on their own when `deploy/.env` is missing. The volumes are
initialised with these values: change one only together with `./reset.sh`. Deleting `deploy/.env`
and copying it again is always safe, as the values are fixed.

**3. Start and seed the stack** (from `deploy/`): one command for infra, services, both consoles,
the seed and the test-only routes

```sh
docker compose --profile core --profile seed --profile tester up -d --build --wait
```

It returns once every container is healthy and `mw-seeder` has exited 0 (a non-zero exit:
`docker compose --profile core --profile seed --profile tester ps -a` names the container, then its `logs`).
Without the test console: `docker compose --profile core --profile seed up -d --build`, then
`docker compose --profile core --profile seed wait mw-seeder` (nothing else waits for the seeder there).

Compose builds the images (`mfs-mw/<service>:local`, both consoles included), then starts everything in dependency order:
the platform, the one-shots (`kafka-init` creates the topics, `etcd-init` and `apisix-init` the
edge config, `provision` every service's OpenBao secret, database role, schema and AppRole), the
15 services (mw-auth and mw-crypto included) and the admin console, then the one-shot `mw-seeder`,
and with `--profile tester` the one-shot `apisix-init-tester` (the test-only routes) and the test console at https://localhost/tester. `provision` runs again on every
`up` and creates what is missing, so a new service's schema appears without dropping volumes.
`deploy/.env` carries the short test timers (core and partner deadlines, status inquiry, reversal,
retry topics, bulk, IPN, and the mw-auth PIN lockout levels `MW_AUTH_LOCK_TRIES`, `MW_AUTH_LOCK_LEVEL1`,
`MW_AUTH_LOCK_LEVEL2`, `MW_AUTH_LOCK_DECAY`: 1 min and 3 min instead of 1 h and 24 h) the API suite needs; for the production timers delete the timer block from
`deploy/.env` and run the start command again (it recreates the services). On a warm Docker cache the command takes 1–2 minutes; the first run pulls the platform
images and builds every local image.

Without the seeder: `docker compose --profile core up -d --build --wait` returns once every
service is healthy. `docker compose --profile minimal up -d --build` starts only the P2P and cash
path, unseeded.

**4. Check it** (repo root)

```sh
cd ..
scripts/smoke.sh          # containers, topics, Kafka, PG, Valkey, Keycloak tokens, OpenBao, APISIX
```

Seeded on start, no extra step: `mw-core-mock` creates 1,000 customer wallets `01711000001..`,
50 agents `01811000001..`, 20 merchants `01911000001..`, 2 orgs `01311000001..` (no PIN at the core
since ticket 72: mw-auth holds the PINs, mw-seeder sets the app wallets' PIN `13579`); Keycloak imports its realms with test users (table below); the simulators, USSD menus,
SMS/email templates and partner configs load their dev data.

`mw-seeder` (profile `seed`, ticket 57) adds, through the services' APIs only (no SQL), the
baseline the API and Playwright suites share: the personas `seed-ops-maker`, `seed-ops-checker`,
`seed-ops-viewer`, `seed-ops-compliance`, `seed-ops-secadmin1`, `seed-ops-secadmin2` (realm ops, with the TOTP secret `KC_OPS_TEST_TOTP_SECRET`),
`seed-org-maker`, `seed-org-checker`, `seed-customer01`/`02`, `seed-agent01`, `seed-merchant01` with
their core-mock wallets (`01712000001`, ...), the OCS partner at its baseline and a first admin
version of the USSD main menu and the P2P received SMS, all through maker-checker. It is
idempotent: running the start command again logs every step `unchanged`. Its manifest (seeded ids
and personas, no secret) is copied to `build/seed/manifest.json` by the suites that need it; how
to log in as the personas: `services/mw-seeder/CLAUDE.md`.

Then:

- **Admin console**: open https://localhost/console (the edge certificate is self-signed: accept
  the browser warning). Sign in as `seed-ops-maker`, password `ops-test-pass`, one-time code from
  the test console page https://localhost/tester/credentials (live, with a countdown) or
  `scripts/totp.sh seed-ops-maker` (no authenticator app needed). A second operator,
  `seed-ops-checker` (code: `scripts/totp.sh seed-ops-checker`), approves what the maker requests:
  use a private window for them. The `ops-*01` users have the same pre-set code
  (`scripts/totp.sh ops-maker01`): no user enrols an authenticator app. Every login: `scripts/creds.sh` and [docs/testing-guide.html](docs/testing-guide.html) section 1.
- **A first transaction**: `scripts/api-test.sh 01` runs the P2P scenarios through APISIX (the
  first run downloads Newman). Then search the console for MSISDN `01711000001`.
- **Test every feature by hand**: `scripts/tester.sh` starts the dev/test-only test console at
  https://localhost/tester (it plays the app, a USSD handset, the partners, the checkout shop and the
  org portal); [docs/testing-guide.html](docs/testing-guide.html) walks through every feature with it.
  Its pages `/tester/credentials` (every login with a live one-time code, the PINs, an OTP lookup, the
  platform secrets), `/tester/seed-data` (everything mw-seeder created, with logins and account tools:
  unlock, reset a password, a one-time code, a PIN, a device or a balance) and `/tester/transactions`
  (search, timeline, partner calls, audit trail) replace the terminal for day-to-day testing.

### Test users (dev only: fixed, documented fake passwords; ADR D9 amendment)

The values come from `deploy/dev.env.defaults`; `scripts/creds.sh` prints the ones in `deploy/.env`, and
the test console pages `/tester/credentials` and `/tester/seed-data` show them with a live one-time code.
Users and wallets carry fake Bangladeshi display names (e.g. `seed-ops-maker` Nusrat Jahan, customer01
Rahim Uddin, merchant01 Karim Traders, the checkout shop); usernames and MSISDNs are what logins and
tests use. The realm-import users' names show only after `deploy/reset.sh` (Keycloak imports a realm once).

| Realm | Users | Password (variable) | Used by |
| --- | --- | --- | --- |
| `ops` | `seed-ops-maker`, `seed-ops-checker`, `seed-ops-viewer`, `seed-ops-compliance`, `seed-ops-secadmin1`, `seed-ops-secadmin2` (security-admin: keys and the key ceremony) (seeded; code: `scripts/totp.sh <user>`) | `ops-test-pass` (`KC_OPS_TEST_PASSWORD`) | admin console, manual tests |
| `ops` | `ops-viewer01`, `ops-maker01`, `ops-checker01`, `ops-admin01`, `ops-compliance01`, `ops-auditor01` (pre-set TOTP, `scripts/totp.sh`) | `ops-test-pass` | admin console |
| `ops` | `ops-e2e-maker01`, `ops-e2e-checker01` (TOTP preset from `KC_OPS_TEST_TOTP_SECRET`) | `ops-test-pass` | API suite |
| mw-auth (no realm, ticket 69) | the app wallets `01711000001` (customer01), `01711000002`, `01811000001` (agent01), `01911000001` (merchant01), `01911000002` and mw-seeder's app wallets: log in with MSISDN + app PIN + a bound device; transactions (app, USSD, checkout) take the same PIN, checked by mw-auth (ticket 72) | app PIN `13579` (`MW_SEED_APP_PIN`, set by mw-seeder through mw-auth's dev-only seed API, `MW_AUTH_SEED_API_ENABLED=true`) | app API, API suite, test console, `scripts/edge-test.sh` |
| `portals` | `seed-org-maker`, `seed-org-checker` (seeded), `org-maker01`, `org-checker01`, `org-checker02`, `org-makerchecker01`, `sp-viewer01`: the realm demands TOTP (ticket 73), every user has the pre-set secret `KC_PORTALS_TEST_TOTP_SECRET` (code: `scripts/totp.sh <user>`) | `portal-test-pass` (`KC_PORTALS_TEST_PASSWORD`) | org / SP portal API |
| `master` | `kc-admin` (kcadm.sh in the container, or the test console's account tools through a test-only route) | `kc-admin-test-pass` (`KC_BOOTSTRAP_ADMIN_PASSWORD`) | Keycloak admin |
| OpenBao | the tester token (root policy), created by `deploy/openbao/dev-unseal.sh` when the `openbao-data` volume is first initialised | `dev-root-token` (`OPENBAO_DEV_ROOT_TOKEN`; change it only together with `./reset.sh`) | `scripts/creds.sh`, test console, `bao` in the container |

The `softhsm` dev/test PKCS#11 tokens (`deploy/softhsm/README.md`, ticket 61) use fixed, documented
fake values too:

| Token label (variable) | User PIN (variable) | SO PIN (variable) | Port | Used by |
| --- | --- | --- | --- | --- |
| `mw-crypto` (`SOFTHSM_MWCRYPTO_LABEL`) | `dev-mwcrypto-user-pin` (`SOFTHSM_MWCRYPTO_USER_PIN`, copied to OpenBao `secret/mw-crypto`) | `dev-mwcrypto-so-pin` (`SOFTHSM_MWCRYPTO_SO_PIN`) | 5657 | `mw-crypto` |
| `paysim` (`SOFTHSM_PAYSIM_LABEL`) | `dev-paysim-user-pin` (`SOFTHSM_PAYSIM_USER_PIN`, copied to OpenBao `secret/mw-simulators`) | `dev-paysim-so-pin` (`SOFTHSM_PAYSIM_SO_PIN`) | 5658 | payShield simulator (`mw-simulators`) |

The payShield 10K simulator (ticket 64) listens for host commands on `mw-simulators:1500` (internal
network only, not published) and keeps its LMK in the `paysim` token; `mw-crypto`'s payShield provider
uses it in the dev stack. Its control API is `/sim/payshield/scenario` and the test helper
`/sim/payshield/pinblock` (`services/mw-simulators/CLAUDE.md`).

## Rebuild one service after a code change

From `deploy/`:

```sh
(cd .. && ./gradlew :mw-notify:build)       # its tests and jar; :mw-notify:bootJar for the jar only
docker compose --profile core up -d --build mw-notify
docker compose restart apisix               # the edge still points at the old container's IP
```

After an admin console change: `docker compose --profile core up -d --build mw-admin-ui`.

## Stop and reset

From `deploy/`:

```sh
docker compose --profile core --profile seed stop    # stop, keep containers and data
docker compose --profile core --profile seed down    # remove containers, keep volumes (seeded data survives)
./reset.sh                                           # wipe ALL state: every container and volume of every profile
```

Never reset a single store. PostgreSQL, Kafka, Valkey, OpenBao, Keycloak and the core-mock ledger
describe the same books, so `deploy/reset.sh` always resets them together. After a reset, repeat
step 3.

## Build and test

There is no CI/CD in the MVP and no Makefile: the root Gradle build is the gate. The root is one
Gradle multi-module build; every `services/*` folder with a `build.gradle.kts` is a subproject
(`settings.gradle.kts` finds it), each with its own self-contained build file and inline versions.

| Command (repo root) | What it does |
| --- | --- |
| `./gradlew clean build` | the full gate: every module's compile, `test` and `bootJar` (`services/mw-*/build/libs/mw-<module>.jar`), plus the repo checks below under the root `check`. Compile and jars run 8 tasks at once (`org.gradle.workers.max`), module tests at most 4 at once (`mwDockerTests`, a Docker and CPU budget for an 8-core host; `-PmwDockerTests=6` on a bigger one); unchanged modules come from the build cache; `--rerun-tasks` forces a full run; `-PwithSlow` adds the `slow-wait` tests (ADR D19), which a milestone gate runs |
| `./gradlew :mw-orchestrator:build` | one module: compile, `test`, `bootJar` |
| `./gradlew :mw-orchestrator:test` | one module's tests; `--tests '*OutboxIT'` for one class |
| `./gradlew bootJar` | every module's jar, no tests: the fastest way to images |
| `./gradlew :checkIsolation` | module independence (`scripts/check-isolation.sh`): no cross-module dependency, settings file, `includeBuild`, `allprojects`/`subprojects`, convention plugin, version catalog or path outside the module except `contracts/`; one plugin version across all modules |
| `./gradlew :checkFieldInjection` | no `@Autowired` field anywhere, tests included (`scripts/check-field-injection.sh`) |
| `./gradlew :contractsLint` | lint `contracts/` and every module's `contracts.yml`, the derived body schemas, and the API suite's acceptance traceability (`scripts/contracts-lint.sh`) |
| `./gradlew :composeCheck` | fail when a long-running service of `deploy/docker-compose.yml` has no memory limit or the limits pass 12 GB (`scripts/compose-check.sh`; ADR D32) |
| `./gradlew :scriptTests` | the shell tests of `scripts/` and of the repo layout and docs, this README's commands included (`scripts/test/run.sh`; files run side by side, `SCRIPT_TESTS_JOBS=3` by default) |

Each module has one `test` task: unit, slice, contract, Camel route, Testcontainers `*IT` and
`@Conformance` tests in one JVM. The `*IT` tests share one PostgreSQL 18 and one Kafka per test JVM
(never reused across runs, removed by Testcontainers' Ryuk) with a fresh database per Spring
context. Without Docker they are skipped and the build still passes, so a gate run needs Docker up.
There is no coverage gate.

Contract manifests (ticket 53): each `services/mw-*/contracts.yml` lists the event and API
contracts the module produces and consumes; `ProducedContractsTest` and `ConsumedContractsTest`
(part of `test`) check them against the root `contracts/` folder. Keep the manifest in step with
every message change. The admin console is tested with `pnpm test` in `ui/mw-admin-ui` (no stack)
and `scripts/ui-e2e.sh` (`ui/mw-admin-ui/CLAUDE.md`).

## Test suites against the running stack

Start and seed the stack first (quick start step 3; `deploy/.env` carries the short test timers). All run from the repo root.

```sh
scripts/smoke.sh                  # live checks of the platform
scripts/edge-test.sh              # APISIX policies: 401/400/413/429 envelopes, header stripping, correlation ID, quotas
scripts/api-test.sh "00, 01"      # Newman API suite: health and seed baseline, P2P
scripts/api-test.sh ""            # every folder but 90 Slow (under 10 minutes; same as no argument)
scripts/api-test.sh "" 1          # every folder, 90 Slow included
scripts/ui-e2e.sh                 # admin console Playwright suite; scripts/ui-e2e.sh @approvals for one tag
scripts/tester.sh && scripts/tester-e2e.sh   # test console, then its Playwright suite
scripts/log-scan.sh               # no PIN or unmasked MSISDN / NID in any container log
```

API suite folders (`tests/api/src`, details in `tests/api/README.md`): `00` edge and seed
baseline, `01`–`04` P2P, device binding, cash in / out, merchant payment, `05`–`08` top-up, bill
pay, add money from bank, `09` checkout and IPN, `10`–`11` notifications, `12` reversal, `13`–`17`
admin (maker-checker, console search, partner config, wallet unlock, menus and templates),
`18`–`20` USSD over HTTP and SMPP, `21`–`23` bulk disbursement and SP query, and since ticket 76 the
security folders: `24` mw-auth (OTP, device binding, first PIN, login, logout, key documents), `25`
the PIN lifecycle (policy, change, self-service and care reset, lockout, ops unlock), `26` the
transaction assertion (PIN before the saga, reuse, USSD, checkout), `27` the payShield PVV through
mw-crypto's dev-only test API; `90` the 10,000-row bulk payout, the retry-to-DLQ path, the PIN
lockout escalation and the assertion expiry (slow). Folders 24-26 use fresh wallets of their own
(core-mock customers `01711000500..01711000999` without an app PIN), never the seeded users.

No load tests on a laptop: `scripts/chaos.sh` and `scripts/suite.sh perf` need a bigger host and a
fresh stack.

## Recommended full test pass

The API suite and the Playwright suites spend the same seeded balances and quotas. For the
milestone gate run them on a fresh stack:

```sh
./gradlew clean build                                  # Docker up: every test, every repo check
cd deploy && ./reset.sh                                # also copies dev.env.defaults to .env if missing
docker compose --profile core --profile seed --profile tester up -d --build --wait   # healthy and seeded
cd .. && scripts/smoke.sh && scripts/edge-test.sh
scripts/api-test.sh ""                                 # the whole API suite (but 90 Slow)
scripts/ui-e2e.sh
scripts/tester-e2e.sh
SECURITY_LIVE=1 scripts/security.sh                    # gitleaks, SBOM + trivy, ZAP baseline, log scan
```

`scripts/chaos.sh` and `scripts/suite.sh perf` complete the MVP acceptance run on a host sized for
them ([docs/demo.md](docs/demo.md), M6).

## Scripts

Every script prints its usage in its header comment. They read the running stack through
`docker compose` on `deploy/docker-compose.yml` (project `mfs-mw`, or `COMPOSE_PROJECT_NAME`).

| Script | What it does |
| --- | --- |
| `scripts/smoke.sh` | live checks: every platform container healthy and the one-shots exited 0, topics, Kafka produce/consume, PostgreSQL, Valkey, Keycloak tokens for every test user, OpenBao, APISIX `/healthz` |
| `scripts/edge-test.sh` | APISIX edge policies on the running stack: 401/400/413/429 envelopes, header stripping, correlation ID, quotas, mw-auth's `/auth/v1` and its tokens on `app-v1` (app logins by `scripts/lib/mw-auth-token.mjs`), partner and checkout callbacks, USSD credentials, idempotent apply |
| `scripts/api-test.sh [FOLDER] [ALL]` | Newman API suite in `tests/api` against the running, seeded stack (with the test timers of `deploy/.env`): every folder but `90 Slow`, or the given ones (numbers or names, comma-separated, e.g. `scripts/api-test.sh "01, 03"`); `1` as the second argument adds `90 Slow`. Re-applies the test-only gateway routes first; JUnit report `tests/api/build/newman-report.xml` |
| `scripts/ui-e2e.sh [GREP]` | admin console Playwright suite (`ui/mw-admin-ui/e2e`) on the running, seeded stack: real Keycloak logins as the seeded personas, the real mw-admin-api behind APISIX; tags `@smoke`, `@console`, `@approvals`, `@partners`, `@ussd`, `@templates`, `@keys`; installs pnpm packages and Chromium when needed |
| `scripts/tester.sh` | start the dev/test-only test console (`ui/mw-test-console`, ADR D17): re-applies the test-only gateway routes with `/tester`, starts `mw-test-console` (profile `tester`, about 192 MiB outside the core budget), prints https://localhost/tester; a later `docker compose up` removes the routes again (run it once more) |
| `scripts/tester-e2e.sh [GREP]` | the test console's Playwright suite (`ui/mw-test-console/e2e`) after `scripts/tester.sh`: app flows with balances, USSD over HTTP and SMPP, a lost core reply resolved by the status inquiry, checkout with IPN, a bulk batch, wallet authentication, the Logins & secrets and Seed data pages (a live code signs in), the account tools, the transaction explorer (tags `@smoke`, `@app`, `@ussd`, `@failures`, `@checkout`, `@bulk`, `@auth`, `@transactions`) |
| `scripts/creds.sh` | print every login and key a tester needs (ops, app and portal users, Keycloak admin, the OpenBao root token while the stack is up, APISIX keys, partner and merchant signing secrets, USSD gateway passwords), from `deploy/.env`; first the test console pages `/tester/credentials` and `/tester/seed-data` that show the same in the browser |
| `scripts/totp.sh [PERSONA]` | the current one-time code of an ops or portals persona with a pre-set secret (default `seed-ops-maker`; also `seed-ops-checker`, `seed-ops-viewer`, `seed-ops-compliance`, `seed-ops-secadmin1`, `seed-ops-secadmin2`, `ops-e2e-*`; portals `seed-org-*`, `org-*`, `sp-viewer01`) |
| `scripts/log-scan.sh [FILE...]` | scan every container log of the stack (or the given files) for a PIN, PIN ciphertext or an unmasked MSISDN / NID; findings printed masked |
| `scripts/security.sh [MODULE]` | gitleaks, CycloneDX SBOM + trivy per module (builds its boot jar), trivy on the built and pinned images; with the stack up also the ZAP baseline and the log scan (`SECURITY_LIVE=1 scripts/security.sh` makes them mandatory). Decisions in `docs/security-findings.md` |
| `scripts/chaos.sh` | kill every `mw-*` container, then `kafka` and `postgres`, mid-flow under load; fails on a transaction left non-terminal or core-mock ledger damage (criterion 3); report in `build/chaos/`; knobs such as `CHAOS_TARGETS="mw-orchestrator kafka" scripts/chaos.sh` in its header. About 15–20 min per target |
| `scripts/suite.sh perf [MODULE]` | Gatling mix at 100 TPS with the p95 middleware-overhead check (criterion 7, `tests/perf/README.md`); report under `tests/perf/build/reports/`; extra `-D` settings in `MW_PERF_ARGS` |
| `scripts/new-service.sh mw-<name>` | scaffold a service from `templates/mw-service-template` (the root build picks it up as `:mw-<name>`); prints the `deploy/docker-compose.yml` block to paste |
| `scripts/versions-check.sh [MODULE]` | report version drift between the modules' build files (versions are inline; report only, not in the build) |
| `scripts/check-isolation.sh [MODULE]` | module independence (`./gradlew :checkIsolation` runs it for all) |
| `scripts/check-field-injection.sh [MODULE]` | no `@Autowired` field injection (`./gradlew :checkFieldInjection`) |
| `scripts/contracts-lint.sh` | contracts lint, derived body schemas (`scripts/derived-schemas.sh --check`, `--write` regenerates them), API suite traceability (`scripts/check-api-traceability.sh`) (`./gradlew :contractsLint`) |
| `scripts/compose-check.sh` | the memory budget of `deploy/docker-compose.yml` (`./gradlew :composeCheck`) |
| `deploy/reset.sh` | drop every container, volume and orphan of every profile (a fresh start next time) |

## Memory knobs

Every long-running memory limit in `deploy/docker-compose.yml` is an optional knob: set it in the
shell or in `deploy/.env`, e.g. `APP_MEM_LIMIT=512M APP_XMX=192m docker compose --profile core up -d`.
`./gradlew :composeCheck` sums the values it sees (shell, then `deploy/.env`, then the defaults)
against the stack budget (`MW_RAM_BUDGET_MIB`, default 12288: 12 GB for the full FRS stack, ADR D32;
the stack as built today stays within the old 8 GB laptop budget, and `--profile minimal` stays the laptop demo). The full table, with measured
peaks, is the header of `deploy/docker-compose.yml`.

| Knob (default) | Containers |
| --- | --- |
| `APP_MEM_LIMIT` (384M) | every `mw-*` service except the ones below (was 416M; trimmed in ticket 67 to free memory for `mw-auth`: measured peaks 313-362 MiB) |
| `APP_MEM_LIMIT_LARGE` (448M) | `mw-orchestrator`, `mw-audit-dw` |
| `SIM_MEM_LIMIT` (384M) | `mw-simulators` |
| `CRYPTO_MEM_LIMIT` (320M), `CRYPTO_XMX` (112m) | `mw-crypto` (its own limit and heap: no Kafka consumers, a pool of 3; measured 275 MiB) |
| `AUTH_MEM_LIMIT` (320M), `AUTH_XMX` (112m) | `mw-auth` (its own limit and heap, sized like `mw-crypto`; not measured on a stack yet, ticket 67) |
| `APP_XMX` (160m) | heap (`-Xms` = `-Xmx`) of every `mw-*` service except `mw-crypto` and `mw-auth` |
| `KAFKA_MEM` (576M), `KAFKA_HEAP` (256m) | `kafka` |
| `KEYCLOAK_MEM` (608M) | `keycloak` |
| `APISIX_MEM` (384M) | `apisix` |
| `POSTGRES_MEM` (320M) | `postgres` |
| `GREENMAIL_MEM` (160M) | `greenmail` |
| `OPENBAO_MEM` (128M), `ETCD_MEM` (64M), `VALKEY_MEM` (64M) | `openbao`, `etcd`, `valkey` |
| `SOFTHSM_MEM` (48M) | `softhsm` (profiles `minimal` and `core`) |
| `ADMIN_UI_MEM` (64M) | `mw-admin-ui` (was 160M; trimmed in ticket 62 to free memory for `mw-crypto`: measured peak 46 MiB x 1.3, rounded up to 32 MiB) |

With the defaults the long-running limits add up to 8176 MiB, inside the old 8192 MiB laptop guard (ticket 62 added
`mw-crypto` at 320M and trimmed `ADMIN_UI_MEM` 160M -> 64M; ticket 67 added `mw-auth` at 320M and
trimmed `APP_MEM_LIMIT` 416M -> 384M on its ten services). Ticket 72 (channels on mw-auth's
assertion, the core PIN scheme removed) adds no container and no limit: mw-ussd and mw-checkout gain
an mTLS client (a few MiB inside their 384M) and mw-core-mock loses bcrypt, so the core profile stays
at 8176 of 8192 MiB; no stack was measured for it, so no limit was cut. Raise `APP_MEM_LIMIT` in `deploy/.env` if a
service is killed for memory (exit 137). Raise `ADMIN_UI_MEM` in `deploy/.env` if
the admin console is killed for memory under a long Playwright run.

Other settings: `MW_HTTPS_PORT` (published edge port, default 443), `MW_PORT_<NAME>` (the direct
host ports below), `COMPOSE_PROJECT_NAME` (a second stack on one host).

## Host ports

APISIX is the edge on https://localhost (443, `MW_HTTPS_PORT`). For debugging, every service and
platform container also publishes on 127.0.0.1 only; each port moves with its `MW_PORT_<NAME>`
knob (in the shell or `deploy/.env`). etcd and the APISIX Admin API stay unpublished. A direct
port skips the edge policies (JWT, HMAC, quotas), and the console login still needs
https://localhost on 443. Production stays edge-only (ADR D3 amendment).

| Port | Container (knob) |
| --- | --- |
| 8081 | `mw-app-gateway` (`MW_PORT_APP_GATEWAY`) |
| 8082 | `mw-ussd` (`MW_PORT_USSD`) |
| 8083 | `mw-orchestrator` (`MW_PORT_ORCHESTRATOR`) |
| 8084 | `mw-core-mock` (`MW_PORT_CORE_MOCK`) |
| 8085 | `mw-simulators` (`MW_PORT_SIMULATORS`) |
| 8086 | `mw-notify` (`MW_PORT_NOTIFY`) |
| 8087 | `mw-audit-dw` (`MW_PORT_AUDIT_DW`) |
| 8088 | `mw-admin-api` (`MW_PORT_ADMIN_API`) |
| 8089 | `mw-adapter-telco` (`MW_PORT_ADAPTER_TELCO`) |
| 8090 | `mw-adapter-billpay` (`MW_PORT_ADAPTER_BILLPAY`) |
| 8091 | `mw-adapter-bank` (`MW_PORT_ADAPTER_BANK`) |
| 8092 | `mw-checkout` (`MW_PORT_CHECKOUT`) |
| 8093 | `mw-portal-api` (`MW_PORT_PORTAL_API`) |
| 8094 | `mw-crypto`, the HSM gateway (`MW_PORT_CRYPTO`) |
| 8095 | `mw-auth`, wallet authentication: its public port (`MW_PORT_AUTH`; the mTLS internal API 8443 is not published) |
| 3000 | `mw-admin-ui`, path `/console` (`MW_PORT_ADMIN_UI`) |
| 3001 | `mw-test-console`, profile `tester` (`MW_PORT_TEST_CONSOLE`) |
| 5432 | `postgres` (`MW_PORT_POSTGRES`) |
| 6379 | `valkey` (`MW_PORT_VALKEY`) |
| 9094 | `kafka`, host listener `localhost:9094` (`MW_PORT_KAFKA`) |
| 8180 | `keycloak` (`MW_PORT_KEYCLOAK`) |
| 8200 | `openbao` (`MW_PORT_OPENBAO`) |
| 8025 / 3025 | `greenmail` API / SMTP (`MW_PORT_GREENMAIL`, `MW_PORT_GREENMAIL_SMTP`) |
| 5657 / 5658 | `softhsm` PKCS#11 tokens `mw-crypto` / `paysim`, p11-kit RPC (`MW_PORT_SOFTHSM_MWCRYPTO`, `MW_PORT_SOFTHSM_PAYSIM`) |

Readiness of one service: `curl -s localhost:8081/actuator/health/readiness` (the actuator listens
on 8080 inside the container).

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `COPY build/libs/mw-….jar` fails during `docker compose … up --build` | No jar yet: run `./gradlew clean build` (or `./gradlew bootJar`) from the repo root first. |
| `required variable … is missing a value` | `deploy/.env` is missing: `cd deploy && cp dev.env.defaults .env`. |
| `./gradlew build` passes but says the `*IT` tests were skipped | Docker was not reachable from Gradle. Start Docker and run `./gradlew clean build --rerun-tasks` for the gate. |
| `./gradlew build` fails with containers OOM-killed or timing out | Four modules' test PostgreSQL and Kafka run at once. Stop the stack while building (`docker compose … stop`), or run with `--max-workers 2`. |
| `port is already allocated` | Another process holds that port (`lsof -i :443`, or the port named). Stop it, or move ours: `MW_HTTPS_PORT=8443` for the edge (the APIs work; the console login needs 443), `MW_PORT_<NAME>` for a direct port (e.g. `MW_PORT_POSTGRES=15432`), in the shell or `deploy/.env`. |
| Containers restart or exit with code 137 | Docker is out of memory. Give Docker Desktop 8 GB, lower a knob above, or use `--profile minimal`. |
| A one-shot (`provision`, `kafka-init`, `etcd-init`, `apisix-init`) exits non-zero | `docker compose logs provision` (it names the service and step), or that one-shot. Usually volumes created with other credentials (a changed value in `deploy/.env`): `./reset.sh`, then start again. |
| A service stays `starting` | `docker compose logs <service>`. It is usually waiting on postgres, kafka, keycloak, openbao or provision. |
| `up --wait` or `wait mw-seeder` exits non-zero on the seeder | `docker compose --profile core --profile seed logs mw-seeder`: the line `seed.step.failed` names the step. An open change request left in the console blocks it: approve or reject it, then start again. |
| Every console or suite sign-in fails | The seeder has not finished, or `deploy/.env` holds other values than the volumes: `scripts/creds.sh` prints the current values; `./reset.sh` and copy `dev.env.defaults` again. |
| 404 on `/test/...` routes or `/tester` | The stack was started without `--profile tester`: start it with the three profiles again, or `scripts/tester.sh`; `scripts/api-test.sh` re-applies the test-only routes itself. |
| API folder 24-26 fails on "a fresh wallet without an app PIN" | Earlier runs gave every core-mock customer `01711000500..01711000999` a PIN (about a dozen per run): `deploy/reset.sh` and start again. |
| API folder 27 answers 404 or 403 | mw-crypto's dev-only payShield test API is off: `deploy/.env` lacks `MW_CRYPTO_TEST_API_ENABLED=true`, `MW_CRYPTO_AUTHZ_PURPOSES_TPK` and `_PVK` (`mw-e2e-crypto`) from `dev.env.defaults`; copy them and recreate `mw-crypto`. Never set them outside dev. |
| An app login answers 423 `MW-AUTH-041` / `042` | The wallet's PIN is locked at mw-auth (`MW_AUTH_LOCK_*`): wait the `retryAfter`, or unlock it in the ops console (maker asks UNLOCK, checker approves); `docs/runbook.md` "Wallet PIN lock". |
| API suite timer scenarios fail | `deploy/.env` lacks the test timer block (deleted for production timers, or an old file). Copy `dev.env.defaults` to `.env` again and rerun the start command (it recreates the services). |
| 502 from the edge right after rebuilding one service | APISIX still points at the old container: `docker compose restart apisix`. |
| `scripts/smoke.sh` reports a partition mismatch | The Kafka volume is from a stack with more partitions per topic: `deploy/reset.sh` once. |

Recovery and operations in depth: [docs/runbook.md](docs/runbook.md).

## What runs

| Group | Containers |
| --- | --- |
| Edge | `apisix`, `etcd` (TLS on 443, JWT / HMAC / basic auth, quotas, error envelope) |
| Channels | `mw-app-gateway` (apps), `mw-ussd` (USSD over SMPP and HTTP), `mw-checkout` (web checkout, IPN), `mw-portal-api` (org bulk disbursement, SP query) |
| Core flow | `mw-orchestrator` (saga, idempotency, TSQ, reversal), `mw-core-mock` (ledger, fault injection) |
| Partners | `mw-adapter-telco`, `mw-adapter-billpay`, `mw-adapter-bank` |
| Security | `mw-auth` (wallet authentication: OTP, device binding, login and RS256 tokens, PIN custody and lockout, resets, the ES256 transaction assertion; public `/auth/v1`, mTLS internal API), `mw-crypto` (the HSM gateway: the only process that talks to an HSM, SoftHSM over PKCS#11 and the payShield simulator in dev; mTLS internal API only), `softhsm` |
| Support | `mw-notify` (SMS over SMPP, email), `mw-audit-dw` (hash-chained audit trail, signed daily anchor), `mw-admin-api` (also key management and the key ceremony), `mw-admin-ui` (console at `/console`) |
| Platform | `kafka` (KRaft), `postgres` (18), `valkey`, `keycloak`, `openbao`, one-shots `kafka-init` and `provision` (every service's secret, database role and AppRole, `deploy/provision`), `softhsm` (dev/test PKCS#11 tokens, profiles `minimal` and `core`, `deploy/softhsm`) |
| Test | `mw-simulators` (OCS, bill aggregator, C2W bank, SMSC, USSD gateway), `greenmail`, `mw-seeder` (profile `seed`), `mw-test-console` (profile `tester`) |

The compose profiles: no profile = the platform (always starts), `minimal` = the P2P and cash path,
`core` = everything, `seed` = the seeder, `tester` = the test console (ADR
[D16](docs/adr/D16-single-compose-file.md)).

Repository layout: `contracts/` (OpenAPI and JSON Schema files, owner per file in
`contracts/OWNERS.yml`), `services/mw-*` (16 subprojects of the root Gradle build), `ui/mw-admin-ui`
(Next.js 16.4.1), `ui/mw-test-console`, `templates/mw-service-template`, `deploy/` (the stack file
`deploy/docker-compose.yml`, APISIX, Kafka topics, provisioning, the softhsm image, ZAP decisions), `tests/api`
(Newman API suite), `tests/perf` (Gatling, its own build), `scripts/` (the repo checks and operator
scripts, with shell self-tests in `scripts/test/`), `docs/`.
