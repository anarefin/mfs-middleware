# Bits MFS middleware: repo conventions

Spec: `spec/bits-mfs-middleware-spec.md`. This file is the short form of the rules every
module follows. Each service also has its own `CLAUDE.md` (responsibilities, contracts
owned, contracts used).

Operator docs (ticket 47, WP30): `README.md` (prerequisites, clean-host quick start, every make
target), `docs/runbook.md` (MVP host), `docs/demo.md` (one demo per milestone M0–M6),
`docs/testing-guide.html` (QA walkthrough: credentials (`make creds`, `make totp`), every feature by hand with the test console and the ops console),
`docs/adr/` (one record per spec decision D1–D15, M1–M13). `scripts/test/test_repo_docs.sh`
fails when they name a make target, repo path, `make api-test` folder or `VAR=` knob that does not exist, when
the README misses a Makefile target, or when a spec decision row has no ADR: a new target, knob or
decision updates these docs in the same change.

## Repository and package layout

- Root (spec 4.2): Gradle composite build for convenience only. `settings.gradle.kts`
  does `includeBuild` for every `services/*` module (auto-discovered); `build.gradle.kts`
  holds only `checkAll`, `bootJarAll`, and `clean` / `build` (`./gradlew clean build`: each
  module's `clean`, then its Docker-free `test` and `bootJar`). No `allprojects`/`subprojects`, no `buildSrc`,
  no convention plugins, no shared version catalog.
- `contracts/` (files only: OpenAPI, AsyncAPI, JSON Schema), owner per file in
  `contracts/OWNERS.yml`. Never change a contract your service does not own.
- `templates/mw-service-template/`: copied by `make new-service NAME=mw-x`; never a dependency.
- `services/mw-<name>/`: standalone Gradle build with its own wrapper,
  `gradle/libs.versions.toml`, `Dockerfile`, `CLAUDE.md` (no `compose.yml`: ADR D16).
- `ui/mw-admin-ui/`: the Next.js admin console (spec 4.2), own `package.json` (pnpm), `Dockerfile`,
  `CLAUDE.md`; tests `pnpm test` (Vitest, no stack) and `make ui-e2e` (Playwright against the running,
  seeded stack).
  Not a Gradle module, so the Makefile module targets skip it.
- `ui/mw-test-console/`: the dev/test-only test console (ADR D17) at `/tester`: plays the app, a USSD
  handset, the partner simulators, the checkout shop and the org portal for manual testing
  (`docs/testing-guide.html`); compose profile `tester`, test-only APISIX route; `pnpm test`, `make tester`,
  `make tester-e2e`. Not a Gradle module either.
- `deploy/`: `docker-compose.yml`, the whole stack in one hand-written file with profiles (none =
  platform, `minimal` = P2P and cash path, `core` = everything, `seed` = seeder; ADR D16; a new
  service adds its block there, `make new-service` prints it), `test-timers.env`, APISIX, Kafka
  topics; `tests/` (`tests/api` Newman API suite, `tests/perf`; black-box), `docs/`.

Module isolation (4.3, D13, D14): a module never depends on another `mfs.mw` artifact,
never uses `includeBuild`, never applies a shared plugin or script, and never references
a path outside its own folder. The one exception is the root `contracts/` folder, read
through the `mwContractsDir` Gradle property (default `../../contracts`) by tests and
build-time schema checks only. Duplicated code (masking, errors, correlation, outbox,
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
`*RepositoryIT` for repository (any test that needs Docker is a `*IT`): the JDBC repositories run against the module's JVM-scoped
Testcontainers PostgreSQL from its `TestInfrastructure`, in a `@SpringBootTest` context, as the
catalogs carry no JDBC slice starter; Camel route tests for route).

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

No CI/CD in the MVP: the Makefile is the pipeline. Every target works for all modules,
or one with `M=<module>` (e.g. `M=mw-orchestrator`).

| Command | What it does |
| --- | --- |
| `make verify [M=] [JOBS=3] [KEEP_TC=1]` | isolation check, field-injection check, contracts lint, `./gradlew check` per module (tests, JaCoCo 80% line gate on `service`/`mapper`/`client`), `JOBS` modules at a time (`JOBS=1` serial; logs in `build/verify/<module>.log`, pass/fail and duration summary); without `M=` also the script self-tests. Runs with `TESTCONTAINERS_REUSE_ENABLE=true` (`scripts/lib/gate.sh`): each module's `TestInfrastructure` PostgreSQL and Kafka carry the labels `mw.module`/`mw.root` and are shared by its `integrationTest` and `conformance` JVMs (own database each, `<svc>_it` / `<svc>_conf` from the `mw.tc.database` system property, dropped and recreated; every topic and consumer group deleted at JVM start), then removed when the module's check ends and, without `M=`, every reused container of the checkout at the end; `KEEP_TC=1` keeps them |
| `make verify-changed [BASE=main] [JOBS=3]` | the same gate for the modules changed since `BASE` only (`scripts/changed-modules.sh --contracts-precise`: a changed contract selects the modules whose `contracts.yml` lists it, plus its owner; `contracts/common/`, `OWNERS.yml`, `deploy/` (not its `*.md`), `scripts/` or the root build select all; `docs/` selects none), Testcontainers reuse as `make verify`, `JOBS` modules at a time; per-module logs in `build/verify-changed/<module>.log`, pass/fail and duration summary; warns when the stack is running (Docker memory) |
| `make check-isolation [M=]` | module independence rules (fails on cross-module deps, `includeBuild`, convention plugins, paths outside the module except `contracts/`) |
| `make contracts-lint` | lint `contracts/` and every module's `contracts.yml` (entries exist, `#<Msg>` is a message type, every event schema has a producer and a consumer manifest or a side in `contracts/EXTERNAL.yml`); then `scripts/check-api-traceability.sh` (every API suite request mapped in `tests/api/TRACEABILITY.md`, requirement tags in its name, every acceptance coverage item claimed, collection not stale) |
| `make versions-check [M=]` | report version drift between module version catalogs (report only, not in `verify`) |
| `make build [M=] [JOBS=3]` | `./gradlew bootJar` per stack module (`services/*` with a Dockerfile), `JOBS` at a time (logs in `build/build/`), then `docker compose build` of `deploy/docker-compose.yml` (images `mfs-mw/<svc>:local` and the dev provisioning one-shot's `mfs-mw/provision:local`; `M=` only that module's `<svc>` plus `provision`) |
| `make up [PROFILE=core] [TIMERS=fast] [M=]` | first generates the gitignored dev credentials in `deploy/.env` (`scripts/gen-dev-env.sh`), then `docker compose --profile $PROFILE up -d --wait [mw-x]` on `deploy/docker-compose.yml`: never builds (images from `make build` are reused), waits until every started service is healthy and the one-shots (`kafka-init`, `etcd-init`, `apisix-init`, `provision`) exited 0. `PROFILE=minimal` starts the platform and the P2P/cash path only; `TIMERS=fast` adds `deploy/test-timers.env` (short core and partner deadlines, inquiry, reply budgets, reversal, retry-topic, bulk and IPN timers; defaults are the production values), recorded in `build/stack-timers` so later targets (`make seed`, ...) keep them until a plain `make up` or `make reset`; `M=mw-x` one service plus its dependencies. Laptop demo footprint only (functional runs and exploring, never load tests; about 7.9 GB of limits). Without the Makefile: `deploy/RUNNING.html` (`./gradlew clean build`, then from `deploy/` `docker compose --env-file .env --env-file test-timers.env --profile core --profile seed up -d --build`; `deploy/reset.sh`) |
| `make seed` | `docker compose --profile core --profile seed up -d mw-seeder`, then `wait mw-seeder`, which must exit 0 (ticket 57; a no-op while the file has no `mw-seeder`) |
| `make compose-check` | fail when a long-running service of `deploy/docker-compose.yml` (`--profile core`) has no memory limit or the limits add up to more than 8 GB (also run by `make verify`) |
| `make smoke` | live checks against the running stack (health, topics, Kafka, PG, Valkey, Keycloak tokens, OpenBao, APISIX `/healthz`) |
| `make edge-test` | live APISIX edge policy tests on the running stack (401/400/413/429 envelopes, X-Mw-* strip, correlation ID, idempotent apply); edge config rules in `deploy/apisix/README.md` |
| `make chaos [TARGETS="mw-x kafka"]` | chaos run (WP27, acceptance criterion 3) on a fresh, healthy stack (`make reset && make up && make chaos`): for every long-running `mw-*` service, then `kafka`, then `postgres`, runs API suite folders as load (`CHAOS_LOAD_FOLDERS`, default the money flows `01 03 04 05 06 08`), SIGKILLs the container mid-flow, restarts it, waits until the stack is healthy, stops the load, waits up to `CHAOS_SETTLE_TIMEOUT` (900 s) for sweeps/TSQ, then fails on any txn created since the run started that is not COMPLETED/FAILED/REJECTED/REVERSED (`MANUAL_REVIEW` counts as stuck unless `CHAOS_ALLOW_MANUAL_REVIEW=1`) and on core-mock ledger damage (sum of balances changed, a txn posted by two commands = double debit/credit, unbalanced entries, held amount vs HELD holds, a hold HELD for a terminal txn, FAILED/REJECTED txn that moved money, COMPLETED txn with no entries). Writes `build/chaos/chaos-report-<UTC>.md` (`CHAOS_REPORT`) to paste into the PR; knobs in the header of `scripts/chaos.sh`. Takes about 15-20 min per target |
| `make down [DOWN_ARGS=-v]` | stop the stack, every profile (`-v` drops its volumes) |
| `make reset` | `down -v --remove-orphans` across every profile: every container and volume of the stack goes (fresh start) |
| `make api-test [FOLDER=] [ALL=1]` | Newman API suite in `tests/api` (`tests/api/README.md`) against the running stack (`make up TIMERS=fast && make seed` first): one request per scenario, folders `00`-`23` (`FOLDER="01, 05"` by number or full name; `ALL=1` adds `90 Slow`); `tests/api/run.sh` first re-runs `apisix-init` with `MW_E2E_ROUTES=1` (test-only routes, key-auth), runs a pinned Newman with `npx`, JUnit report `tests/api/build/newman-report.xml`. Request names carry the requirement tags `scripts/check-api-traceability.sh` (in `make contracts-lint`) checks against `tests/api/TRACEABILITY.md`; regenerate the collection with `node tests/api/build_collection.mjs` |
| `make ui-e2e [GREP=]` | admin UI Playwright suite (`ui/mw-admin-ui/e2e`, numbered specs tagged `@smoke`, `@console`, `@approvals`, `@partners`, `@ussd`, `@templates`) against the running, seeded stack (`make up && make seed` first; no mock): real Keycloak logins as the seeded ops personas, the real mw-admin-api behind APISIX; `pnpm install --frozen-lockfile` / Chromium when needed; credentials from `deploy/.env`, personas from `build/seed/manifest.json`; global setup fails in seconds with the fix when the stack is not ready; idempotent |
| `make tester` | dev/test-only test console (`ui/mw-test-console`, ADR D17): re-runs `apisix-init` with `MW_E2E_ROUTES=1` (test routes and its own `/tester` route), starts `mw-test-console` (profile `tester`, outside the core budget), prints https://localhost/tester; manual walkthrough of every feature in `docs/testing-guide.html` |
| `make tester-e2e [GREP=]` | the test console's Playwright suite (`ui/mw-test-console/e2e`, tags `@smoke`, `@app`, `@ussd`, `@failures`, `@checkout`, `@bulk`) against it on the running, seeded stack (`make up TIMERS=fast && make seed && make tester` first) |
| `make creds` | print every dev/test login and key (`scripts/creds.sh`: `deploy/.env`, the OpenBao root token while the stack is up) for testers (`docs/testing-guide.html` section 1) |
| `make totp [PERSONA=seed-ops-maker]` | current ops console TOTP of a persona with the pre-set secret (`scripts/totp.sh`; HmacSHA256, raw UTF-8 key as mw-seeder `util/Totp`) |
| `make perf [M=]` | Gatling 100 TPS mix in `tests/perf` against the running stack (`make up` first, fresh for the gate): re-applies the test-only routes, `gatlingRun` then always `perfOverhead` (overhead = response time minus core-mock and simulator time); reports in `tests/perf/build/reports/{gatling,perf}`; extra `-D` settings via `MW_PERF_ARGS`; see `tests/perf/README.md` |
| `make security [M=]` | security pass (WP29): gitleaks over the files not gitignored, CycloneDX SBOM + trivy per module, trivy on the built `mfs-mw/*:local` images and the pinned third-party images (HIGH/CRITICAL fail; accepted findings with reason + expiry in `.trivyignore.yaml` / `deploy/trivyignore-third-party.yaml`); with the stack up also the ZAP baseline on APISIX and `scripts/log-scan.sh` over every container log (`SECURITY_LIVE=1` makes them mandatory: the gate runs it after `make api-test`); scanners from PATH or their pinned images; register `docs/security-findings.md` |
| `make log-scan` | `scripts/log-scan.sh` over every container log of the running stack: fails on a PIN, PIN ciphertext, unmasked MSISDN or NID (findings printed masked; also a stage of `make security`) |
| `make new-service NAME=mw-x` | scaffold a service from the template |
| `make test-scripts` | shell tests for the Makefile and `scripts/` (`scripts/test/`) |
| `cd services/mw-x && ./gradlew test` | unit, slice and contract tests (`contracts/ProducedContractsTest`, `ConsumedContractsTest`); no Docker, no `*IT` classes, no `@Conformance` |
| `cd services/mw-x && ./gradlew integrationTest` | the Testcontainers `*IT` classes (not `@Conformance`), Docker needed |
| `cd services/mw-x && ./gradlew conformance` | the `@Conformance` suite |
| `cd services/mw-x && ./gradlew check` | one module on its own: `test`, `integrationTest`, `conformance` and the JaCoCo gate over all three |

Each module lists the contracts it produces and consumes in `services/mw-x/contracts.yml` (paths
relative to `contracts/`, `#<Msg>` for one message type of a multi-type topic). Keep it in sync with
the code in the same commit; its `ProducedContractsTest` / `ConsumedContractsTest` fail on an entry
without a fixture.

Definition of done: tests written first; per ticket `./gradlew test integrationTest` green for every
touched module (plus `make test-scripts` / `make contracts-lint` when scripts or contracts changed)
and the relevant `make api-test FOLDER=...`; at a milestone gate `make verify-changed BASE=<gate base>` and
the API suite (`make api-test` on `make up TIMERS=fast && make seed`); a full `make verify` once at the end of a milestone run. Output pasted into the PR
template.

## Never

- Never put secrets in code, config files or tests (use OpenBao/Vault config binding;
  test fixtures use obviously fake values).
- Never use `System.out` / `System.err` / `printStackTrace`; log through SLF4J.
- Never use field injection (`@Autowired` on fields), tests included; constructor injection
  only (`make verify` runs `scripts/check-field-injection.sh`).
- Never block on Kafka listener threads (no synchronous waits for replies, sleeps or
  long partner calls on the consumer thread).
- Never log a PIN or an unmasked MSISDN, NID, account number or name.
- Never depend on, include or reference another module, the template or the root build.
- Never change a contract your service does not own.
- Never read or write another service's database schema.
