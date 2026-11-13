# Bits MFS middleware: repo conventions

Spec: `spec/bits-mfs-middleware-spec.md`. This file is the short form of the rules every
module follows. Each service also has its own `CLAUDE.md` (responsibilities, contracts
owned, contracts used).

Operator docs (ticket 47, WP30): `README.md` (prerequisites, clean-host quick start, every operator
script, compose memory knobs, troubleshooting), `docs/runbook.md` (MVP host), `docs/demo.md` (one demo
per milestone M0–M6), `docs/testing-guide.html` (QA walkthrough: credentials (`scripts/creds.sh`,
`scripts/totp.sh`), every feature by hand with the test console and the ops console),
`docs/tutorial/` (onboarding tour for a new developer: architecture, core ideas, every feature's flow, a first day),
`docs/adr/` (one record per spec decision D1–D15, M1–M13, plus later ones). `scripts/test/test_repo_docs.sh`
(in `./gradlew :scriptTests`) fails when they name a script, repo path, `./gradlew` task, API suite
folder or `VAR=` knob that does not exist, when the README misses an operator script in `scripts/`, or
when a spec decision row has no ADR: a new script, knob or decision updates these docs in the same change.

## Repository and package layout

- Root (spec 4.2): one Gradle multi-module build with the only wrapper (`./gradlew`).
  `settings.gradle.kts` includes every `services/*` folder that has a `build.gradle.kts` as the
  subproject `:mw-x` (auto-discovered; nothing else goes in that file). `gradle.properties` turns on
  parallel builds, the build cache and the configuration cache, with `org.gradle.workers.max=4` (a
  Docker memory budget: at most four modules' test containers at once). `build.gradle.kts` applies
  only `base` and registers the repo checks (`checkIsolation`, `checkFieldInjection`, `contractsLint`,
  `composeCheck`, `scriptTests`: the scripts in `scripts/` as cached Exec tasks) under the root
  `check`. No `allprojects`/`subprojects`, no `buildSrc`, no convention plugins, no version catalog.
- `contracts/` (files only: OpenAPI, AsyncAPI, JSON Schema), owner per file in
  `contracts/OWNERS.yml`. Never change a contract your service does not own.
- `templates/mw-service-template/`: copied by `scripts/new-service.sh mw-x`; never a dependency and
  never included in the root build.
- `services/mw-<name>/`: a subproject with its own self-contained `build.gradle.kts` (versions inline),
  `Dockerfile`, `contracts.yml`, `CLAUDE.md`; no wrapper, settings file, `gradle.properties` or
  version catalog of its own (no `compose.yml` either: ADR D16). `bootJar` writes the fixed name
  `build/libs/mw-<name>.jar`, which the `Dockerfile` copies.
- `ui/mw-admin-ui/`: the Next.js admin console (spec 4.2), own `package.json` (pnpm), `Dockerfile`,
  `CLAUDE.md`; tests `pnpm test` (Vitest, no stack) and `scripts/ui-e2e.sh` (Playwright against the
  running, seeded stack). Not a Gradle module.
- `ui/mw-test-console/`: the dev/test-only test console (ADR D17) at `/tester`: plays the app, a USSD
  handset, the partner simulators, the checkout shop and the org portal for manual testing
  (`docs/testing-guide.html`); compose profile `tester`, test-only APISIX route; `pnpm test`,
  `scripts/tester.sh`, `scripts/tester-e2e.sh`. Not a Gradle module either.
- `deploy/`: `docker-compose.yml`, the whole stack in one hand-written file with profiles (none =
  platform, `minimal` = P2P and cash path, `core` = everything, `seed` = seeder, `tester` = test
  console; ADR D16; a new service adds its block there, `scripts/new-service.sh` prints it), memory
  knobs in its header, `test-timers.env`, `reset.sh`, APISIX, Kafka topics, the `provision` one-shot;
  `tests/` (`tests/api` Newman API suite, `tests/perf` Gatling, its own standalone build; black-box), `docs/`.

Module isolation (4.3, D13, D14 as amended for the multi-module root): a module never depends on
another module (`project(":...")` or an `mfs.mw` artifact), never configures another project, never
applies a shared plugin or script, never uses a version catalog (versions are written inline in its
`build.gradle.kts`) and never references a path outside its own folder. The one exception is the
root `contracts/` folder, read through the `mwContractsDir` Gradle property (default
`../../contracts`) by tests and build-time schema checks only. Gradle rejects one plugin requested
with two versions in one build, so every module names the same plugin versions; `./gradlew
:checkIsolation` (`scripts/check-isolation.sh`) enforces all of this. Library versions may drift
(`scripts/versions-check.sh` reports it). Duplicated code (masking, errors, correlation, outbox,
inbox, Kafka error handling) is intended; it is copied from the template.

Package convention: `mfs.mw.<service>`, the module name without `mw-`, hyphens turned
into dots (`mw-adapter-telco` -> `mfs.mw.adapter.telco`). Layered packages (4.4), one
kind of class per package, only the packages a service needs:

```
mfs.mw.<service>/
  <Service>Application.java   only class in the root package
  config/      @Configuration, @ConfigurationProperties
  controller/  @RestController: mapping and @Valid only, no business logic
  dto/         request/response and Kafka message records
  service/     business logic, @Transactional boundaries
  repository/  Spring Data repositories
  entity/      JPA entities / JDBC aggregates (own schema only)
  mapper/      DTO <-> entity <-> message
  exception/   exceptions, error codes, global @RestControllerAdvice
  messaging/   Kafka listeners/publishers, outbox relay, inbox dedup, retry/DLQ
  route/       Camel RouteBuilders and YAML route templates (no XML DSL)
  client/      outbound calls (partners, SMSC), with Resilience4j; in mw-simulators also the
               simulated SMPP servers (SMSC, USSD gateway)
  transport/   channel transports: mw-ussd's HTTP and SMPP USSD transports (MNO wire <-> engine)
  scheduler/   @Scheduled jobs guarded by ShedLock
  security/    filters, signature/HMAC, JWT converters
  filter/      correlation ID and request logging filters
  logging/     structured log formatter (21.1 fields, masking before output)
  util/        small static helpers (masking, hashing, crypto envelope)
  partner/     adapters only: the adapter framework (spec 12), see below
```

The one sanctioned exception to one kind of class per package is the adapter framework
`partner/` in each adapter (mw-adapter-telco, -billpay, -bank; copied, not a library): its SPI
(`PartnerAdapter`, `AuthScheme`, `PartnerCall`, `PartnerResponse`, `InboundCall`, ...) and its
Spring wiring `PartnerFrameworkConfiguration` sit in `partner/` itself, with its own subpackages
`partner/config/` (partner config records, store, config-changed route and listener),
`partner/template/` (Camel route templates, registrar, gateway, call wrapper, REST/JSON adapter,
audit), `partner/auth/` (auth schemes, credential resolver) and `partner/mapping/` (JSONata). The
three copies stay identical apart from the package; mw-adapter-telco's CLAUDE.md describes it. The
framework hands its audit to the service's `messaging/PartnerAuditPublisher`; everything else in an
adapter follows the layered packages above.

Dependencies point one way: `controller`, `messaging`, `route`, `scheduler`, `transport` -> `service`
-> `repository`, `client`. One sanctioned exception: `service` may call the outbox and inbox
writers (`messaging/OutboxWriter`, `messaging/Inbox`) and use the header constants and metadata
of `messaging/` (`MwHeaders`, `EventMetadata`) inside its own transaction, because the event must
commit with the business change (spec 6.4). The same exception covers `messaging/EventPublisher`
when a request changes no local state the event must commit with (mw-app-gateway
`TransactionService` / `DeviceOtpService`, mw-checkout `CheckoutPayService`, mw-ussd
`KafkaTransactionFlow` publish a command and wait for its reply asynchronously; an OTP is sent
straight to Kafka so it is never stored): a port interface would have one implementation in
`messaging/` and leave the dependency direction as it is, so it is not added. Listeners, relays and routes in `messaging/` still
depend on `service`, never the other way round otherwise. Entities never leave the service layer.
Tests mirror the main packages (unit for service/mapper/util, `@WebMvcTest` for controller,
`*RepositoryIT` for repository, Camel route tests for route). Any test that needs Docker is a `*IT`:
it imports the module's `TestInfrastructure` (one PostgreSQL and one Kafka per test JVM, never
reused, removed by Ryuk; a fresh database per Spring context) and `DockerAvailableCondition` skips it
when Docker is not available. The JDBC repositories run in a `@SpringBootTest` context against that
PostgreSQL, as the modules carry no JDBC slice starter.

## Error model

One envelope for every API (5.3):

```
{ code, message, messageBn?, txnId?, correlationId, retryable, details[] }
```

Codes are namespaced: `MW-VAL-*` validation, `MW-AUTH-*`, `MW-IDEM-*`, `MW-CORE-*` (core
rejection, carries the core reason), `MW-PTR-<partner>-*` (mapped partner error),
`MW-TMO-*` (timeout / unknown outcome), `MW-SYS-*`. Throw exceptions from `exception/`;
the global `@RestControllerAdvice` builds the envelope. Listeners route failures to
retry topics / DLQ. Partner-code -> canonical-code maps live in adapter config.

## Masking

`MaskingPolicy` in each service's `util` package (18.3, SEC-10), applied to logs, audit
payloads and admin views, enforced by a log appender filter and unit tests on every DTO
field marked `@Sensitive`:

- MSISDN: `0171****789`
- NID: last 3 digits only
- Account number: last 4 only
- Names: first initial only
- Ids: free-text `scrub` has no UUID/id-shape exemption; only system ids stay whole, on their shape
  (digit runs and all): in logs a `txnId`/`correlationId` that is an RFC 9562 UUID and a W3C
  `traceId`/`spanId`, in the audit trail a `<type>/<uuid>` resource and a UUID correlationId.
  `CorrelationIdFilter` is the gate: a caller `X-Correlation-Id` (edge: 6+ digit rule only) or
  `traceparent` trace/parent id with a run of 6+ digits, or that `scrub` would change, is
  replaced/dropped. `scrub` masks an MSISDN (11 digits `01…`, or with
  `880`/`+880`) whose digits are joined by single `-`, `.`, `_`, `:` or spaces, e.g. spread over UUID
  groups (`aaaa0171-2345-678a-…`).
- PIN: never logged or stored; the middleware only ever handles ciphertext (RSA-OAEP,
  SHA-256 with the core's public key). Card PAN never enters the middleware.

## Log fields

JSON to stdout, one event per line, UTC ISO-8601 timestamps (21.1). Fields:
`ts`, `level`, `service`, `version`, `host`, `env`, `correlationId`, `txnId`, `traceId`,
`spanId`, `partnerId`, `channel`, `event`, `message`, `durationMs`, `errorCode`.
Masking runs before output; payload logging is off by default. Business events use
stable `event` names (`txn.state.changed`, `partner.call.completed`, `smpp.bind.down`).

## Test commands

No CI/CD in the MVP and no Makefile: the root Gradle build is the gate (ADR D18), the stack is plain
`docker compose` on `deploy/docker-compose.yml`, and everything else is a script in `scripts/` (usage
in its header). Run Gradle from the repo root; run `docker compose` from `deploy/`.

Each module has one `test` task: unit, slice, contract, Camel route, Testcontainers `*IT` and
`@Conformance` tests in one JVM (no separate tiers, no coverage gate). Without Docker the `*IT`
classes are skipped, so the build still passes; a gate run needs Docker.

| Command | What it does |
| --- | --- |
| `./gradlew clean build` | the full gate: every module's compile, `test` and `bootJar` (`build/libs/mw-x.jar`), plus the repo checks under the root `check`. The build cache restores what did not change (an unchanged rebuild takes seconds; a cold run about 7 min on a 12-core laptop); `--rerun-tasks` forces everything |
| `./gradlew :mw-x:build` | one module: compile, `test`, `bootJar` |
| `./gradlew :mw-x:test` | one module's tests (`--tests '*SomeIT'` for one class) |
| `./gradlew :mw-x:bootJar` | one module's jar for its image, no tests |
| `./gradlew check` | every module's tests plus the repo checks, no jars |
| `./gradlew :checkIsolation` | module independence (`scripts/check-isolation.sh [M]`): no cross-module dependency, settings file, `includeBuild`, `allprojects`/`subprojects`, convention plugin, version catalog, path outside the module except `contracts/`; one plugin with two versions across modules |
| `./gradlew :checkFieldInjection` | no `@Autowired` field anywhere, tests included (`scripts/check-field-injection.sh [M]`) |
| `./gradlew :contractsLint` | lint `contracts/` and every module's `contracts.yml` (entries exist, `#<Msg>` is a message type, every event schema has a producer and a consumer manifest or a side in `contracts/EXTERNAL.yml`), the derived body schemas, then `scripts/check-api-traceability.sh` (every API suite request mapped in `tests/api/TRACEABILITY.md`, requirement tags in its name, every acceptance coverage item claimed, collection not stale) |
| `./gradlew :composeCheck` | fail when a long-running service of `deploy/docker-compose.yml` (`--profile core`) has no memory limit or the limits (knob values from the shell or `deploy/.env`, else their defaults) add up to more than 8 GB |
| `./gradlew :scriptTests` | the shell tests of `scripts/` and the repo layout and docs (`scripts/test/run.sh [test_file.sh ...]`) |
| `scripts/versions-check.sh [M]` | report version drift between the modules' build files (report only, not in the build) |
| `scripts/gen-dev-env.sh` | the gitignored dev credentials in `deploy/.env` (existing values kept); run once before the first `up` |
| `docker compose --env-file .env [--env-file test-timers.env] --profile core --profile seed up -d --build --wait` | from `deploy/`, after `./gradlew clean build`: builds the images from the jars, starts the stack, waits until healthy; the `provision` one-shot re-creates missing roles and schemas on every `up`; `mw-seeder` seeds (must show `exited (0)` in `docker compose --profile seed ps -a mw-seeder`). `test-timers.env` = short core and partner deadlines, inquiry, reversal, retry-topic, bulk and IPN timers (scripts that start services read `TIMERS=fast` for it); `--profile minimal` = the P2P and cash path only. Laptop demo footprint only (about 7.9 GB of limits), never load tests; knobs in `README.md`. Without the shell: `deploy/RUNNING.html` |
| `./gradlew :mw-x:bootJar && docker compose ... up -d --build mw-x` | rebuild and restart one service after a change |
| `docker compose --profile core --profile seed down` / `deploy/reset.sh` | stop the stack (data kept) / drop every container and volume of every profile (fresh start) |
| `scripts/smoke.sh` | live checks against the running stack (health, topics, Kafka, PG, Valkey, Keycloak tokens, OpenBao, APISIX `/healthz`) |
| `scripts/edge-test.sh` | live APISIX edge policy tests on the running stack (401/400/413/429 envelopes, X-Mw-* strip, correlation ID, idempotent apply); edge config rules in `deploy/apisix/README.md` |
| `scripts/api-test.sh [FOLDER] [ALL]` | Newman API suite in `tests/api` (`tests/api/README.md`) against the running, seeded stack started with `test-timers.env`: one request per scenario, folders `00`-`23` (`"01, 05"` by number or full name; `ALL` = `1` adds `90 Slow`); `tests/api/run.sh` re-runs `apisix-init` with `MW_E2E_ROUTES=1` (test-only routes, key-auth), runs a pinned Newman with `npx`, JUnit report `tests/api/build/newman-report.xml`. Request names carry the requirement tags `scripts/check-api-traceability.sh` checks against `tests/api/TRACEABILITY.md`; regenerate the collection with `node tests/api/build_collection.mjs` |
| `scripts/ui-e2e.sh [GREP]` | admin UI Playwright suite (`ui/mw-admin-ui/e2e`, numbered specs tagged `@smoke`, `@console`, `@approvals`, `@partners`, `@ussd`, `@templates`) against the running, seeded stack (no mock): real Keycloak logins as the seeded ops personas, the real mw-admin-api behind APISIX; installs pnpm packages / Chromium when needed; credentials from `deploy/.env`, personas from `build/seed/manifest.json`; global setup fails in seconds with the fix when the stack is not ready; idempotent |
| `scripts/tester.sh` | dev/test-only test console (`ui/mw-test-console`, ADR D17): re-runs `apisix-init` with `MW_E2E_ROUTES=1`, starts `mw-test-console` (profile `tester`, outside the core budget), prints https://localhost/tester; walkthrough in `docs/testing-guide.html` |
| `scripts/tester-e2e.sh [GREP]` | the test console's Playwright suite (`ui/mw-test-console/e2e`, tags `@smoke`, `@app`, `@ussd`, `@failures`, `@checkout`, `@bulk`) on the running, seeded stack started with `test-timers.env`, after `scripts/tester.sh` |
| `scripts/creds.sh` | print every dev/test login and key (`deploy/.env`, the OpenBao root token while the stack is up) for testers (`docs/testing-guide.html` section 1) |
| `scripts/totp.sh [PERSONA]` | current ops console TOTP of a persona with the pre-set secret (default `seed-ops-maker`; HmacSHA256, raw UTF-8 key as mw-seeder `util/Totp`) |
| `scripts/security.sh [M]` | security pass (WP29): gitleaks over the files not gitignored, CycloneDX SBOM + trivy per module (from its boot jar), trivy on the built `mfs-mw/*:local` images and the pinned third-party images (HIGH/CRITICAL fail; accepted findings with reason + expiry in `.trivyignore.yaml` / `deploy/trivyignore-third-party.yaml`); with the stack up also the ZAP baseline on APISIX and `scripts/log-scan.sh` (`SECURITY_LIVE=1` makes them mandatory: the gate runs it after `scripts/api-test.sh`); register `docs/security-findings.md` |
| `scripts/log-scan.sh` | every container log of the running stack: fails on a PIN, PIN ciphertext, unmasked MSISDN or NID (findings printed masked) |
| `scripts/chaos.sh` | chaos run (WP27, acceptance criterion 3) on a fresh, healthy stack: SIGKILLs each `mw-*` service, then `kafka` and `postgres`, mid-flow under API suite load, then fails on any txn left non-terminal and on core-mock ledger damage; report in `build/chaos/`; knobs (`CHAOS_TARGETS`, ...) in its header. About 15-20 min per target |
| `scripts/suite.sh perf [M]` | Gatling 100 TPS mix in `tests/perf` against the running stack: `gatlingRun`, then always `perfOverhead`; reports in `tests/perf/build/reports/{gatling,perf}`; extra `-D` settings via `MW_PERF_ARGS`; see `tests/perf/README.md` |
| `scripts/new-service.sh mw-x` | scaffold a service from the template (picked up by `settings.gradle.kts`; prints the compose block) |

Each module lists the contracts it produces and consumes in `services/mw-x/contracts.yml` (paths
relative to `contracts/`, `#<Msg>` for one message type of a multi-type topic). Keep it in sync with
the code in the same commit; its `ProducedContractsTest` / `ConsumedContractsTest` (part of `test`)
fail on an entry without a fixture, and `./gradlew :contractsLint` checks every event has a producer
and a consumer. The contract files a module's tests read are inputs of its `test` task, so a changed
contract re-runs exactly the modules that use it.

Definition of done: tests written first; per ticket `./gradlew :mw-x:build` green, with Docker
running, for every touched module (plus `./gradlew :scriptTests` / `./gradlew :contractsLint` when
scripts or contracts changed) and the relevant `scripts/api-test.sh` folders; at a milestone gate
the root `./gradlew clean build` and the whole API suite on a fresh stack (`deploy/reset.sh`, then
`up` with `test-timers.env` and the seed profile). Output pasted into the PR template.

## Never

- Never put secrets in code, config files or tests (use OpenBao/Vault config binding;
  test fixtures use obviously fake values). The one exception: the dev/test stack's tester logins
  are fixed, documented fake values in `scripts/gen-dev-env.sh` (ADR D9 amendment).
- Never use `System.out` / `System.err` / `printStackTrace`; log through SLF4J.
- Never use field injection (`@Autowired` on fields), tests included; constructor injection
  only (`./gradlew :checkFieldInjection` runs `scripts/check-field-injection.sh`).
- Never block on Kafka listener threads (no synchronous waits for replies, sleeps or
  long partner calls on the consumer thread).
- Never log a PIN or an unmasked MSISDN, NID, account number or name.
- Never depend on, include or reference another module, the template or the root build.
- Never change a contract your service does not own.
- Never read or write another service's database schema.
