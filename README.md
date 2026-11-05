# upay MFS middleware (MVP)

The integration layer between upay's channels (customer, agent and merchant apps, USSD, web
checkout, org and SP portals) and the core wallet, built on Spring Boot 4, Apache Camel, Kafka,
APISIX and PostgreSQL 18. The MVP runs the whole system on one Docker Compose host against a
mocked core (`mw-core-mock`) and simulated partners (`mw-simulators`).

- Design: [spec/upay-mfs-middleware-spec.md](spec/upay-mfs-middleware-spec.md) (the "MVP scope"
  section wins over sections 1–25 for the MVP).
- Why it is built this way: [docs/adr/README.md](docs/adr/README.md), one record per decision
  D1–D15 and M1–M13.
- Running the MVP host: [docs/runbook.md](docs/runbook.md).
- Milestone demos M0–M6: [docs/demo.md](docs/demo.md).
- Conventions for contributors and coding agents: [CLAUDE.md](CLAUDE.md) and the `CLAUDE.md` of
  each module.

## Prerequisites

The quick start needs nothing but this repo and the tools below. Everything else (Kafka,
PostgreSQL, Valkey, Keycloak, OpenBao, APISIX, GreenMail, every service and the admin console)
runs in Docker.

| What | Why | Check |
| --- | --- | --- |
| Linux or macOS host, 4+ CPU cores, 16 GB RAM, 40 GB free disk | the stack's memory limits add up to about 7.5 GB (`make compose-check` caps them at 10 GB); images and Gradle caches take about 15 GB | `nproc` / `sysctl -n hw.ncpu` |
| Docker Engine with the Compose v2 plugin, 2.20 or newer (Compose `include`); on macOS Docker Desktop with at least 10 GB memory for its VM | every container | `docker compose version` |
| JDK 25 (Temurin or any other build) on `PATH` or in `JAVA_HOME` | `make up` builds each service jar on the host with its own Gradle wrapper; the wrappers download Gradle itself | `java -version` |
| bash, make (GNU or BSD), git, curl, jq | the Makefile and `scripts/`; jq for `make smoke`, `make edge-test`, `make security` | `jq --version` |
| Node.js 22+ (optional) | only `make contracts-lint`, `make compose-flat` and `make compose-check`; the admin console builds inside Docker | `node --version` |
| Internet access on the first run | images, Gradle distributions and dependencies, the console's pnpm packages | |
| Host port 443 free | APISIX is the only container that publishes a port (443 -> 9443) | `lsof -i :443` |

## Quick start (clean host, under 15 minutes)

Acceptance criterion 6 of the MVP (OWN-06): a clean host brings up the full stack with seeded data
in under 15 minutes, from the repo alone. On a host with a warm Docker cache it takes 3–5 minutes;
the first run spends most of its time downloading images and Gradle dependencies.

```sh
git clone <this repo> mfs-middleware && cd mfs-middleware
java -version && docker compose version     # JDK 25, Compose 2.20+
time make up                                # credentials, jars, images, stack; waits until healthy
make smoke                                  # live checks: containers, topics, Kafka, PG, Valkey, Keycloak, OpenBao, APISIX
```

`make up` (`scripts/up.sh`):

1. generates every dev credential into the gitignored `deploy/compose/platform/.env`
   (`scripts/gen-dev-env.sh`; existing values are kept, so the volumes stay usable);
2. builds every service jar (`./gradlew bootJar` in each `services/mw-*`, no tests);
3. starts the MVP project `mw` (`deploy/compose/mvp/compose.yml`, which includes
   `deploy/compose/platform/compose.yml` and every module's own `compose.yml`, with the
   low-resource override `deploy/compose/mvp/compose.lowmem.yml`) with `up -d --build`;
4. waits until every long-running container is healthy and every one-shot init container
   (`kafka-init`, `etcd-init`, `apisix-init`, `<module>-init`) has exited 0; it fails at once,
   naming the container, otherwise (`MW_UP_TIMEOUT`, default 300 s).

Seeded on start, no extra step: `mw-core-mock` creates 1,000 customer wallets `01711000001..`,
50 agents `01811000001..`, 20 merchants `01911000001..`, 2 orgs `01311000001..`, all with the fake
PIN `1234`; Keycloak imports its realms with test users (table below); the simulators, USSD menus,
SMS/email templates and partner configs load their dev data.

Then:

- **Admin console**: open https://localhost/console (the edge certificate is self-signed: accept
  the browser warning). Sign in as `ops-maker01`; the password is `KC_OPS_TEST_PASSWORD` in
  `deploy/compose/platform/.env` (`grep KC_OPS_TEST_PASSWORD deploy/compose/platform/.env`). The
  first sign-in of each operator asks you to enrol TOTP with an authenticator app (realm `ops`
  requires MFA). A second operator, `ops-checker01`, approves what `ops-maker01` requests: use a
  private window for them.
- **A first transaction**: `make e2e TAGS=@p2p` runs the P2P scenarios through APISIX (the first
  run downloads the suite's dependencies). Then search the console for MSISDN `01711000001`.
- **Stop**: `make down` keeps the data; `make down DOWN_ARGS=-v` drops every volume (a fresh
  start next time).

### Test users (dev only, passwords in the generated `.env`)

| Realm | Users | Password variable | Used by |
| --- | --- | --- | --- |
| `ops` | `ops-viewer01`, `ops-maker01`, `ops-checker01`, `ops-admin01`, `ops-compliance01`, `ops-auditor01` (TOTP enrolled at first sign-in) | `KC_OPS_TEST_PASSWORD` | admin console |
| `ops` | `ops-e2e-maker01`, `ops-e2e-checker01` (TOTP preset from `KC_OPS_TEST_TOTP_SECRET`) | `KC_OPS_TEST_PASSWORD` | e2e suite only |
| `customers` | `customer01` (wallet `01711000001`), `customer02` (`01711000002`), `agent01`, `merchant01`, `merchant02` | `KC_CUSTOMERS_TEST_PASSWORD` | app API, e2e, perf |
| `portals` | `org-maker01`, `org-checker01`, `org-checker02`, `org-makerchecker01`, `sp-viewer01` | `KC_PORTALS_TEST_PASSWORD` | org / SP portal API |

### With plain `docker compose`

The repo-root `compose.yml` is the whole stack in one flat file (generated by `make compose-flat`
from the MVP project and its low-resource override). Without the Makefile:

```sh
scripts/gen-dev-env.sh                                            # dev credentials, once
for d in services/mw-*/; do (cd "$d" && ./gradlew bootJar -q); done  # the images copy build/libs/*.jar
docker compose --env-file deploy/compose/platform/.env up -d --build
make smoke                                                        # waits for health, then checks
```

This path does not wait for health by itself; `make smoke` does.

## What runs

| Group | Containers |
| --- | --- |
| Edge | `apisix`, `etcd` (TLS on 443, JWT / HMAC / basic auth, quotas, error envelope) |
| Channels | `mw-app-gateway` (apps), `mw-ussd` (USSD over SMPP and HTTP), `mw-checkout` (web checkout, IPN), `mw-portal-api` (org bulk disbursement, SP query) |
| Core flow | `mw-orchestrator` (saga, idempotency, TSQ, reversal), `mw-core-mock` (ledger, fault injection) |
| Partners | `mw-adapter-telco`, `mw-adapter-billpay`, `mw-adapter-bank` |
| Support | `mw-notify` (SMS over SMPP, email), `mw-audit-dw` (hash-chained audit trail), `mw-admin-api`, `mw-admin-ui` (console at `/console`) |
| Platform | `kafka` (KRaft), `postgres` (18), `valkey`, `keycloak`, `openbao`, one-shot `kafka-init` and per-service init containers |
| Test | `mw-simulators` (OCS, bill aggregator, C2W bank, SMSC, USSD gateway), `greenmail` |

Repository layout: `contracts/` (OpenAPI and JSON Schema files, owner per file in
`contracts/OWNERS.yml`), `services/mw-*` (12 standalone Gradle builds), `ui/mw-admin-ui`
(Next.js 16.4.1), `templates/mw-service-template`, `deploy/` (Compose, APISIX, Kafka topics, ZAP
decisions), `tests/e2e` (Cucumber), `tests/perf` (Gatling), `scripts/` (everything the Makefile
runs, with shell self-tests in `scripts/test/`), `docs/`.

## Commands

There is no CI/CD in the MVP: the Makefile is the pipeline (M13). Every target works for all
modules, or for one with `M=<module>`. `make help` prints the list.

### Build and test gate

| Command | What it does |
| --- | --- |
| `cd services/mw-x && ./gradlew test` | unit and slice tests, no Docker (the fast tier) |
| `cd services/mw-x && ./gradlew integrationTest` | the `*IT` classes: Testcontainers PostgreSQL 18 and Kafka (needs Docker) |
| `cd services/mw-x && ./gradlew check` | `test` + `integrationTest` + the `@Conformance` suite + JaCoCo (80 % lines on `service`, `mapper`, `client`) |
| `make verify [M=]` | isolation check, field-injection check, contracts lint, `make compose-check`, then `./gradlew check` per module; without `M=` also the script self-tests. Slow for all modules (hours): use `make verify-changed` |
| `make verify-changed [BASE=main] [JOBS=3]` | the same gate for the modules changed since `BASE` plus their contract neighbours, `JOBS` at a time (logs in `build/verify-changed/`). A changed contract selects the modules whose `services/mw-*/contracts.yml` lists it; run it with the stack down (Docker memory) |
| `make check-isolation [M=]` | module independence: no cross-module dependency, `includeBuild`, convention plugin or path outside the module except `contracts/` |
| `make check-field-injection [M=]` | no `@Autowired` field anywhere, tests included |
| `make contracts-lint` | lint `contracts/` (Node.js) |
| `make versions-check [M=]` | report version drift between the modules' version catalogs |
| `make test-scripts` | shell self-tests of the Makefile and `scripts/` (this README's commands included) |
| `make new-service NAME=mw-x` | scaffold a service from `templates/mw-service-template` |

Contract manifests (ticket 53): each `services/mw-*/contracts.yml` lists the event and API
contracts the module produces and consumes; `ProducedContractsTest` and `ConsumedContractsTest`
(Docker-free, part of `./gradlew test`) check them against the root `contracts/` folder. Keep the
manifest in step with every message change. The admin console is tested with `pnpm test` and
`pnpm test:e2e` in `ui/mw-admin-ui` (`ui/mw-admin-ui/CLAUDE.md`).

### Stack and black-box suites

| Command | What it does |
| --- | --- |
| `make up [M=]` | the MVP stack (above), or one module's `compose.yml` as its own project; `MW_UP_NO_BUILD=1` reuses the built jars and images, `MW_UP_ONLY="mw-a mw-b"` rebuilds and recreates only those services inside the running stack |
| `make smoke` | live checks against the running stack |
| `make edge-test` | APISIX edge policies: 401/400/413/429 envelopes, header stripping, correlation ID, quotas, idempotent apply |
| `make e2e [M=] [TAGS=]` | Cucumber suite in `tests/e2e` (`tests/e2e/README.md`), filtered by `@<module>` and a tag expression, e.g. `make e2e TAGS="@p2p or @cash"` |
| `make e2e-changed [BASE=main] [TAGS=]` | rebuild only the modules changed since `BASE` into the running stack, then the e2e suite (`E2E_SCOPE=modules` runs only their scenarios); fast feedback, not the gate |
| `make chaos [TARGETS=]` | kill every `mw-*` container, then `kafka` and `postgres`, mid-flow under load; checks no transaction is left non-terminal and the core-mock ledger balances (criterion 3); report in `build/chaos/` |
| `make perf` | Gatling mix at 100 TPS with the p95 middleware-overhead check (criterion 7, `tests/perf/README.md`); report under `tests/perf/build/reports/` |
| `make security [M=]` | gitleaks, CycloneDX SBOM + trivy for every module and image; with the stack up also the ZAP baseline and the container log scan. `SECURITY_LIVE=1 make security` makes the live stages mandatory (the milestone gate). Decisions in `docs/security-findings.md` |
| `make log-scan` | scan every container log of the stack for a PIN or an unmasked MSISDN / NID |
| `make compose-flat` | regenerate the root `compose.yml` |
| `make compose-check` | fail when `compose.yml` is stale, a service has no memory limit or the limits pass 10 GB |
| `make down [DOWN_ARGS=-v]` | stop the stack (`-v` drops its volumes) |

The milestone gate, in order: `make verify-changed BASE=<run start>` with the stack down, then
`make down DOWN_ARGS=-v && make up && make e2e`, then `make chaos`, `make perf` and
`SECURITY_LIVE=1 make security` on that stack (see [docs/demo.md](docs/demo.md), M6).
