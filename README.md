# Bits MFS middleware (MVP)

The integration layer between Bits's channels (customer, agent and merchant apps, USSD, web
checkout, org and SP portals) and the core wallet, built on Spring Boot 4, Apache Camel, Kafka,
APISIX and PostgreSQL 18. The MVP runs the whole system on one Docker Compose host against a
mocked core (`mw-core-mock`) and simulated partners (`mw-simulators`).

- Design: [spec/bits-mfs-middleware-spec.md](spec/bits-mfs-middleware-spec.md) (the "MVP scope"
  section wins over sections 1–25 for the MVP).
- Why it is built this way: [docs/adr/README.md](docs/adr/README.md), one record per decision
  D1–D15 and M1–M13.
- Running the MVP host: [docs/runbook.md](docs/runbook.md).
- Milestone demos M0–M6: [docs/demo.md](docs/demo.md).
- Testing every feature by hand from a browser (app flows, USSD, bank, failures, checkout, bulk,
  ops console), step by step: [docs/testing-guide.html](docs/testing-guide.html), with the dev/test-only
  test console (`make tester`, https://localhost/tester).
- Running the full stack on a laptop with plain Docker Compose and seed data, then testing it
  (BitsPay pattern): [deploy/RUNNING.html](deploy/RUNNING.html).
- Conventions for contributors and coding agents: [CLAUDE.md](CLAUDE.md) and the `CLAUDE.md` of
  each module.

## Prerequisites

The quick start needs nothing but this repo and the tools below. Everything else (Kafka,
PostgreSQL, Valkey, Keycloak, OpenBao, APISIX, GreenMail, every service and the admin console)
runs in Docker.

| What | Why | Check |
| --- | --- | --- |
| Linux or macOS host, 4+ CPU cores, 16 GB RAM, 40 GB free disk | the stack's memory limits add up to about 7.9 GB (`make compose-check` caps them at 8 GB: a laptop demo footprint, never a load test host); images and Gradle caches take about 15 GB | `nproc` / `sysctl -n hw.ncpu` |
| Docker Engine with the Compose v2 plugin, 2.20 or newer (`up --wait`, profiles); on macOS Docker Desktop with at least 8 GB memory for its VM | every container | `docker compose version` |
| JDK 25 (Temurin or any other build) on `PATH` or in `JAVA_HOME` | `make build` builds each service jar on the host with its own Gradle wrapper; the wrappers download Gradle itself | `java -version` |
| bash, make (GNU or BSD), git, curl, jq | the Makefile and `scripts/`; jq for `make smoke`, `make edge-test`, `make security` | `jq --version` |
| Node.js 22+ with npx | `make api-test` (Newman, pinned, fetched by npx; needs access to registry.npmjs.org and cdnjs.cloudflare.com), `make contracts-lint`, `make compose-check`; the admin console builds inside Docker | `node --version` |
| Node.js 24 + pnpm (optional) | only the consoles' own tests: `pnpm test` in `ui/mw-admin-ui` / `ui/mw-test-console`, `make ui-e2e` and `make tester-e2e` (Playwright against the running stack; Chromium is installed on first use) | `pnpm --version` |
| Internet access on the first run | images, Gradle distributions and dependencies, the console's pnpm packages | |
| Host port 443 free | APISIX is the only container that publishes a port (443 -> 9443) | `lsof -i :443` |

## Quick start (clean host, under 15 minutes)

Acceptance criterion 6 of the MVP (OWN-06): a clean host brings up the full stack with seeded data
in under 15 minutes, from the repo alone. On a host with a warm Docker cache it takes 3–5 minutes;
the first run spends most of its time downloading images and Gradle dependencies.

```sh
git clone <this repo> mfs-middleware && cd mfs-middleware
java -version && docker compose version     # JDK 25, Compose 2.20+
make build                                  # jars (bootJar, 3 modules at a time), then every image
time make up                                # credentials, then the stack from the built images; waits until healthy
make seed                                   # shared baseline through the APIs (mw-seeder); manifest in build/seed/manifest.json
make smoke                                  # live checks: containers, topics, Kafka, PG, Valkey, Keycloak, OpenBao, APISIX
```

The whole stack is one hand-written file, `deploy/docker-compose.yml` (project `mfs-mw`, ADR
[D16](docs/adr/D16-single-compose-file.md)), with profiles: no profile = the platform (always
starts), `minimal` = the P2P and cash path, `core` = everything (the default), `seed` = the seeder.

`make build` (`scripts/build.sh`) runs `./gradlew bootJar` in every `services/mw-*` (no tests;
`JOBS` modules at a time, logs in `build/build/`), then `docker compose build`: images
`mfs-mw/<service>:local` plus `mfs-mw/provision:local` (`deploy/provision`, the dev provisioning
one-shot). `make build M=mw-x` builds one module's jar and image, plus `provision`.

`make up` (`scripts/up.sh`) never builds:

1. generates every dev credential into the gitignored `deploy/.env`
   (`scripts/gen-dev-env.sh`; existing values are kept, so the volumes stay usable);
2. runs `docker compose --profile core up -d --wait` (`PROFILE=minimal` for the P2P and cash path
   only; `TIMERS=fast` adds `deploy/test-timers.env`: short status-inquiry, reversal, retry-topic
   and IPN timers for test runs; `M=mw-x` starts one service and what it depends on);
3. returns once every started container is healthy and every one-shot
   (`kafka-init`, `etcd-init`, `apisix-init`, `provision`) has exited 0, and fails naming the
   container otherwise (`MW_UP_TIMEOUT`, default 300 s). A second `make up` reuses the images and
   containers as they are.

Start-up order and cost (ticket 55): `provision` starts once PostgreSQL and OpenBao are healthy
and, for every service of `deploy/provision/services.sh` in turn, writes its OpenBao secret,
database role and schema and AppRole (each into that service's own `<svc>-approle` volume; ADR D16).
Every `mw-*` service starts once PostgreSQL, Kafka, Keycloak and OpenBao are healthy and
`kafka-init` and `provision` have exited 0. `kafka-init` creates the topics of `deploy/kafka/topics.txt` with 1
partition each (`MW_KAFKA_PARTITIONS=1`, the laptop footprint; the file keeps the production counts) and leaves a done
marker in its volume, so a later `make up` with the same plan skips it in about a second. Keycloak
is pre-built (`deploy/keycloak/Dockerfile`, image `mfs-mw/keycloak:local`) and starts `--optimized`.
The service images are trained for their AOT cache with the dev stack's JVM flags (build arg
`JVM_OPTS`, `x-jvm-build-args` in `deploy/docker-compose.yml`). `make reset` removes every
container and volume of the stack (every profile) for a fresh start.

Seeded on start, no extra step: `mw-core-mock` creates 1,000 customer wallets `01711000001..`,
50 agents `01811000001..`, 20 merchants `01911000001..`, 2 orgs `01311000001..`, all with the fake
PIN `1234`; Keycloak imports its realms with test users (table below); the simulators, USSD menus,
SMS/email templates and partner configs load their dev data.

`make seed` (`scripts/seed.sh`, ticket 57) then runs the one-shot `mw-seeder` (profile `seed`) on the
running stack and fails unless it exits 0. Through the services' APIs only (no SQL), it adds the
baseline the API and Playwright suites share: the personas `seed-ops-maker`, `seed-ops-checker`,
`seed-ops-viewer`, `seed-ops-compliance` (realm ops, with the TOTP secret `KC_OPS_TEST_TOTP_SECRET`), `seed-org-maker`,
`seed-org-checker`, `seed-customer01`/`02`, `seed-agent01`, `seed-merchant01` with their core-mock
wallets (`01712000001`, ...), the OCS partner at its baseline and a first admin version of the USSD
main menu and the P2P received SMS, all through maker-checker. It is idempotent: a second `make seed`
logs every step `unchanged`. The manifest (seeded ids and personas, no secret) is copied to
`build/seed/manifest.json`; how to log in as the personas: `services/mw-seeder/CLAUDE.md`.

Then:

- **Admin console**: open https://localhost/console (the edge certificate is self-signed: accept
  the browser warning). Sign in as `ops-maker01`; the password is `KC_OPS_TEST_PASSWORD` in
  `deploy/.env` (`grep KC_OPS_TEST_PASSWORD deploy/.env`). The
  first sign-in of each operator asks you to enrol TOTP with an authenticator app (realm `ops`
  requires MFA). A second operator, `ops-checker01`, approves what `ops-maker01` requests: use a
  private window for them.
- **A first transaction**: `make seed`, then `make api-test FOLDER=01` runs the P2P scenarios through
  APISIX (the first run downloads Newman). For the whole suite start the stack with
  `make up TIMERS=fast` (short status-inquiry and retry timers) and run `make api-test`. Then search the console for MSISDN `01711000001`.
- **Test every feature by hand**: `make tester` starts the dev/test-only test console at
  https://localhost/tester (it plays the app, a USSD handset, the partners, the checkout shop and the
  org portal); [docs/testing-guide.html](docs/testing-guide.html) walks through every feature with it.
- **Stop**: `make down` keeps the data; `make reset` drops every container and volume (a fresh
  start next time).

### Test users (dev only, passwords in the generated `.env`)

| Realm | Users | Password variable | Used by |
| --- | --- | --- | --- |
| `ops` | `ops-viewer01`, `ops-maker01`, `ops-checker01`, `ops-admin01`, `ops-compliance01`, `ops-auditor01` (TOTP enrolled at first sign-in) | `KC_OPS_TEST_PASSWORD` | admin console |
| `ops` | `ops-e2e-maker01`, `ops-e2e-checker01` (TOTP preset from `KC_OPS_TEST_TOTP_SECRET`) | `KC_OPS_TEST_PASSWORD` | API suite only |
| `customers` | `customer01` (wallet `01711000001`), `customer02` (`01711000002`), `agent01`, `merchant01`, `merchant02` | `KC_CUSTOMERS_TEST_PASSWORD` | app API, API suite, perf |
| `portals` | `org-maker01`, `org-checker01`, `org-checker02`, `org-makerchecker01`, `sp-viewer01` | `KC_PORTALS_TEST_PASSWORD` | org / SP portal API |

### With plain `docker compose` (BitsPay pattern)

Without the Makefile, one command builds the images, starts everything and seeds it
([deploy/RUNNING.html](deploy/RUNNING.html) walks through every step):

```sh
./gradlew clean build                 # every module's clean, Docker-free test tier and bootJar
scripts/gen-dev-env.sh                # dev credentials in deploy/.env, once (values are kept)
cd deploy
docker compose --env-file .env --env-file test-timers.env --profile core --profile seed up -d --build
docker compose --profile core --profile seed ps -a mw-seeder   # exited (0) once seeded
./reset.sh                            # later: wipe every container and volume (same as make reset)
```

Drop `--env-file test-timers.env` for the production timers. The images build without the AOT
cache on the dev stack (`MW_AOT=0`); `MW_AOT=1` on the build trains it as the production images do.

## What runs

| Group | Containers |
| --- | --- |
| Edge | `apisix`, `etcd` (TLS on 443, JWT / HMAC / basic auth, quotas, error envelope) |
| Channels | `mw-app-gateway` (apps), `mw-ussd` (USSD over SMPP and HTTP), `mw-checkout` (web checkout, IPN), `mw-portal-api` (org bulk disbursement, SP query) |
| Core flow | `mw-orchestrator` (saga, idempotency, TSQ, reversal), `mw-core-mock` (ledger, fault injection) |
| Partners | `mw-adapter-telco`, `mw-adapter-billpay`, `mw-adapter-bank` |
| Support | `mw-notify` (SMS over SMPP, email), `mw-audit-dw` (hash-chained audit trail), `mw-admin-api`, `mw-admin-ui` (console at `/console`) |
| Platform | `kafka` (KRaft), `postgres` (18), `valkey`, `keycloak`, `openbao`, one-shots `kafka-init` and `provision` (every service's secret, database role and AppRole, `deploy/provision`) |
| Test | `mw-simulators` (OCS, bill aggregator, C2W bank, SMSC, USSD gateway), `greenmail` |

Repository layout: `contracts/` (OpenAPI and JSON Schema files, owner per file in
`contracts/OWNERS.yml`), `services/mw-*` (12 standalone Gradle builds), `ui/mw-admin-ui`
(Next.js 16.4.1), `templates/mw-service-template`, `deploy/` (the stack file `deploy/docker-compose.yml`, APISIX, Kafka topics, ZAP
decisions), `tests/api` (Newman API suite), `tests/perf` (Gatling), `scripts/` (everything the Makefile
runs, with shell self-tests in `scripts/test/`), `docs/`.

## Commands

There is no CI/CD in the MVP: the Makefile is the pipeline (M13). Every target works for all
modules, or for one with `M=<module>`. `make help` prints the list.

### Build and test gate

| Command | What it does |
| --- | --- |
| `./gradlew clean build` (repo root) | every module's `clean`, then its `test` tier and `bootJar`: fresh jars for the images, no Docker |
| `cd services/mw-x && ./gradlew test` | unit and slice tests, no Docker (the fast tier) |
| `cd services/mw-x && ./gradlew integrationTest` | the `*IT` classes: Testcontainers PostgreSQL 18 and Kafka (needs Docker) |
| `cd services/mw-x && ./gradlew conformance` | the `@Conformance` suite copied from the template (correlation, envelope, masking, outbox, inbox, retry/DLQ) |
| `cd services/mw-x && ./gradlew check` | `test` + `integrationTest` + the `@Conformance` suite + JaCoCo (80 % lines on `service`, `mapper`, `client`) |
| `make verify [M=] [JOBS=3]` | isolation check, field-injection check, contracts lint, `make compose-check`, then `./gradlew check` per module, `JOBS` at a time (`JOBS=1` is the old serial run; logs in `build/verify/`, pass/fail and duration summary); without `M=` also the script self-tests. Testcontainers reuse is on: a module's `integrationTest` and `conformance` share one PostgreSQL (own database each, `<svc>_it` / `<svc>_conf`) and one Kafka, removed when its check ends (`KEEP_TC=1` keeps them). Slow for all modules: use `make verify-changed` |
| `make verify-changed [BASE=main] [JOBS=3]` | the same gate for the modules changed since `BASE` plus their contract neighbours, `JOBS` at a time (logs in `build/verify-changed/`). A changed contract selects the modules whose `services/mw-*/contracts.yml` lists it; run it with the stack down (Docker memory) |
| `make check-isolation [M=]` | module independence: no cross-module dependency, `includeBuild`, convention plugin or path outside the module except `contracts/` |
| `make check-field-injection [M=]` | no `@Autowired` field anywhere, tests included |
| `make contracts-lint` | lint `contracts/` and every module's `contracts.yml`, and the API suite's acceptance traceability (`scripts/check-api-traceability.sh`) (Node.js, python3) |
| `make versions-check [M=]` | report version drift between the modules' version catalogs |
| `make test-scripts` | shell self-tests of the Makefile and `scripts/` (this README's commands included) |
| `make new-service NAME=mw-x` | scaffold a service from `templates/mw-service-template` |

Contract manifests (ticket 53): each `services/mw-*/contracts.yml` lists the event and API
contracts the module produces and consumes; `ProducedContractsTest` and `ConsumedContractsTest`
(Docker-free, part of `./gradlew test`) check them against the root `contracts/` folder. Keep the
manifest in step with every message change. The admin console is tested with `pnpm test` in
`ui/mw-admin-ui` (no stack) and `make ui-e2e` (Playwright against the running, seeded stack;
`ui/mw-admin-ui/CLAUDE.md`).

### Stack and black-box suites

| Command | What it does |
| --- | --- |
| `make build [M=] [JOBS=3]` | `bootJar` per stack module, `JOBS` at a time, then `docker compose build` (every image, or `M=`'s) |
| `make up [PROFILE=core] [TIMERS=fast] [M=]` | start the stack from the built images and wait until healthy (above); `PROFILE=minimal` = the P2P and cash path, `TIMERS=fast` = short test timers (`deploy/test-timers.env`; recorded in `build/stack-timers`, so later targets such as `make seed` keep them until a plain `make up` or `make reset`), `M=mw-x` = one service and its dependencies |
| `make seed` | run the seed profile's one-shot `mw-seeder` on the running stack, fail unless it exits 0, copy its manifest to `build/seed/manifest.json` (`MW_SEED_MANIFEST=` overrides); idempotent |
| `make smoke` | live checks against the running stack |
| `make edge-test` | APISIX edge policies: 401/400/413/429 envelopes, header stripping, correlation ID, quotas, idempotent apply |
| `make api-test [FOLDER=] [ALL=1]` | Newman API suite in `tests/api` (`tests/api/README.md`) against the running stack (`make up TIMERS=fast && make seed` first; under 10 minutes): every folder but `90 Slow`, or the ones in `FOLDER` (numbers or names, comma-separated, e.g. `make api-test FOLDER="01, 03"`); `ALL=1` adds `90 Slow` |
| `make ui-e2e [GREP=]` | admin console Playwright suite (`ui/mw-admin-ui/e2e`) against the running, seeded stack (`make up && make seed` first): real Keycloak logins as `seed-ops-maker` / `-checker` / `-viewer`, the real mw-admin-api behind APISIX; `pnpm install --frozen-lockfile` and the Chromium download when needed; credentials from `deploy/.env`, personas from `build/seed/manifest.json`; `GREP=@approvals` filters by spec tag (`@smoke`, `@console`, `@approvals`, `@partners`, `@ussd`, `@templates`); stops in global setup, with the fix, when the stack is not ready; idempotent (run it twice) |
| `make tester` | start the dev/test-only test console (`ui/mw-test-console`, ADR D17) on the running stack: re-applies the test-only gateway routes (`MW_E2E_ROUTES=1`, its own route `/tester` included), starts `mw-test-console` (profile `tester`, about 192 MiB outside the core budget) and prints https://localhost/tester; a later plain `make up` removes the route again; walkthrough in [docs/testing-guide.html](docs/testing-guide.html) |
| `make tester-e2e [GREP=]` | the test console's Playwright suite (`ui/mw-test-console/e2e`) against it on the running, seeded stack (`make up TIMERS=fast && make seed && make tester` first): app flows with balances, USSD over HTTP and SMPP, a lost core reply resolved by the status inquiry, checkout with IPN, a bulk batch |
| `make creds` | print every login and key a tester needs on the dev/test stack (ops, app and portal users and their passwords, Keycloak admin, the OpenBao root token while the stack is up, APISIX keys, partner and merchant signing secrets, USSD gateway passwords), read from `deploy/.env`; walkthrough in [docs/testing-guide.html](docs/testing-guide.html) section 1 |
| `make totp [PERSONA=seed-ops-maker]` | the current 6-digit ops console one-time code of a persona with a pre-set secret (`seed-ops-maker`, `seed-ops-checker`, `seed-ops-viewer`, `seed-ops-compliance`, `ops-e2e-*`): HmacSHA256 over `KC_OPS_TEST_TOTP_SECRET`, so no authenticator app is needed |
| `make chaos [TARGETS=]` | kill every `mw-*` container, then `kafka` and `postgres`, mid-flow under load; checks no transaction is left non-terminal and the core-mock ledger balances (criterion 3); report in `build/chaos/` |
| `make perf` | Gatling mix at 100 TPS with the p95 middleware-overhead check (criterion 7, `tests/perf/README.md`); report under `tests/perf/build/reports/` |
| `make security [M=]` | gitleaks, CycloneDX SBOM + trivy for every module and image; with the stack up also the ZAP baseline and the container log scan. `SECURITY_LIVE=1 make security` makes the live stages mandatory (the milestone gate). Decisions in `docs/security-findings.md` |
| `make log-scan` | scan every container log of the stack for a PIN or an unmasked MSISDN / NID |
| `make compose-check` | fail when a long-running service of `deploy/docker-compose.yml` has no memory limit or the limits pass 8 GB |
| `make down [DOWN_ARGS=-v]` | stop the stack, every profile (`-v` drops its volumes) |
| `make reset` | remove every container, volume and orphan of the stack (every profile): the next `make up` starts fresh; `deploy/reset.sh` does the same from `deploy/` |

The milestone gate, in order: `make verify-changed BASE=<run start>` with the stack down, then
`make reset && make build && make up TIMERS=fast && make seed && make api-test`, then `make chaos`, `make perf` and
`SECURITY_LIVE=1 make security` on that stack (see [docs/demo.md](docs/demo.md), M6).
