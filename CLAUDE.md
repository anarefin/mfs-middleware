# upay MFS middleware: repo conventions

Spec: `spec/upay-mfs-middleware-spec.md`. This file is the short form of the rules every
module follows. Each service also has its own `CLAUDE.md` (responsibilities, contracts
owned, contracts used).

Operator docs (ticket 47, WP30): `README.md` (prerequisites, clean-host quick start, every make
target), `docs/runbook.md` (MVP host), `docs/demo.md` (one demo per milestone M0–M6),
`docs/adr/` (one record per spec decision D1–D15, M1–M13). `scripts/test/test_repo_docs.sh`
fails when they name a make target, repo path, e2e tag or `VAR=` knob that does not exist, when
the README misses a Makefile target, or when a spec decision row has no ADR: a new target, knob or
decision updates these docs in the same change.

## Repository and package layout

- Root (spec 4.2): Gradle composite build for convenience only. `settings.gradle.kts`
  does `includeBuild` for every `services/*` module (auto-discovered); `build.gradle.kts`
  holds only `checkAll` and `bootJarAll`. No `allprojects`/`subprojects`, no `buildSrc`,
  no convention plugins, no shared version catalog.
- `contracts/` (files only: OpenAPI, AsyncAPI, JSON Schema), owner per file in
  `contracts/OWNERS.yml`. Never change a contract your service does not own.
- `templates/mw-service-template/`: copied by `make new-service NAME=mw-x`; never a dependency.
- `services/mw-<name>/`: standalone Gradle build with its own wrapper,
  `gradle/libs.versions.toml`, `Dockerfile`, `compose.yml`, `CLAUDE.md`.
- `ui/mw-admin-ui/`: the Next.js admin console (spec 4.2), own `package.json` (pnpm), `Dockerfile`,
  `compose.yml`, `CLAUDE.md`; tests `pnpm test` and `pnpm test:e2e` (Playwright, self-contained).
  Not a Gradle module, so the Makefile module targets skip it.
- `deploy/` (Compose, APISIX, Kafka topics), `tests/` (e2e, perf; black-box), `docs/`.

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
| `make verify [M=]` | isolation check, field-injection check, contracts lint, `./gradlew check` per module (tests, JaCoCo 80% line gate on `service`/`mapper`/`client`); without `M=` also the script self-tests |
| `make verify-changed [BASE=main] [JOBS=3]` | the same gate for the modules changed since `BASE` only (`scripts/changed-modules.sh --contracts-precise`: a changed contract selects the modules whose `contracts.yml` lists it, plus its owner; `contracts/common/`, `OWNERS.yml`, `deploy/`, `scripts/` or the root build select all), `JOBS` modules at a time; per-module logs in `build/verify-changed/<module>.log`, pass/fail and duration summary; warns when the MVP stack is running (Docker memory) |
| `make check-isolation [M=]` | module independence rules (fails on cross-module deps, `includeBuild`, convention plugins, paths outside the module except `contracts/`) |
| `make contracts-lint` | lint `contracts/` and every module's `contracts.yml` (entries exist, `#<Msg>` is a message type, every event schema has a producer and a consumer manifest or a side in `contracts/EXTERNAL.yml`) |
| `make versions-check [M=]` | report version drift between module version catalogs (report only, not in `verify`) |
| `make up [M=]` | Compose stack (`deploy/compose/mvp`) or one module's `compose.yml`; first generates the gitignored dev credentials in `deploy/compose/platform/.env` (`scripts/gen-dev-env.sh`), always rebuilds every stack module jar (`bootJar`) and its images (`up --build`; `MW_UP_NO_BUILD=1` skips both), then waits until services are healthy and one-shot inits exited 0; the MVP project always runs with the low-resource override `deploy/compose/mvp/compose.lowmem.yml` (functional runs only, about 7.5 GB of limits) |
| `make compose-flat` | regenerate the root `compose.yml`: the whole stack as one flat file (includes and the low-resource override merged, `${VAR}` placeholders kept, repo-relative paths). Run it with `docker compose --env-file deploy/compose/platform/.env up -d --build` after building the jars |
| `make compose-check` | fail when `compose.yml` is stale, a long-running service has no memory limit, or the limits add up to more than 10 GB (also run by `make verify`) |
| `make smoke` | live checks against the running MVP stack (health, topics, Kafka, PG, Valkey, Keycloak tokens, OpenBao, APISIX `/healthz`) |
| `make edge-test` | live APISIX edge policy tests on the running stack (401/400/413/429 envelopes, X-Mw-* strip, correlation ID, idempotent apply); edge config rules in `deploy/apisix/README.md` |
| `make chaos [TARGETS="mw-x kafka"]` | chaos run (WP27, acceptance criterion 3) on a fresh, healthy MVP stack (`make down DOWN_ARGS=-v && make up && make chaos`): for every long-running `mw-*` service, then `kafka`, then `postgres`, runs the e2e suite as load (`CHAOS_LOAD_TAGS`, default the non-`@tsq` money flows), SIGKILLs the container mid-flow, restarts it, waits until the stack is healthy, stops the load, waits up to `CHAOS_SETTLE_TIMEOUT` (900 s) for sweeps/TSQ, then fails on any txn created since the run started that is not COMPLETED/FAILED/REJECTED/REVERSED (`MANUAL_REVIEW` counts as stuck unless `CHAOS_ALLOW_MANUAL_REVIEW=1`) and on core-mock ledger damage (sum of balances changed, a txn posted by two commands = double debit/credit, unbalanced entries, held amount vs HELD holds, a hold HELD for a terminal txn, FAILED/REJECTED txn that moved money, COMPLETED txn with no entries). Writes `build/chaos/chaos-report-<UTC>.md` (`CHAOS_REPORT`) to paste into the PR; knobs in the header of `scripts/chaos.sh`. Takes about 15-20 min per target |
| `make down [DOWN_ARGS=-v]` | stop the MVP stack (`-v` drops its volumes) |
| `make e2e [M=] [TAGS=@p2p]` | Cucumber suite in `tests/e2e` against the running stack (`make up` first), filtered by `@<module>` and `TAGS`; first re-runs `apisix-init` with `MW_E2E_ROUTES=1` (test-only routes, key-auth); stack access and reusable steps: `tests/e2e/README.md` |
| `make e2e-changed [BASE=main] [TAGS=]` | fast feedback after a change, no `verify`: rebuilds (`bootJar`, no tests) only the modules changed since `BASE` (commits, staged, unstaged, untracked) and recreates just their services in the running MVP project (`MW_UP_ONLY`); the whole stack when it is down or `contracts/`/`deploy/` changed; then the full e2e suite (`E2E_SCOPE=modules` runs only `@<changed-module>` scenarios). Not a substitute for `make up M=` (that runs the module as a separate compose project) or for the gate |
| `make perf [M=]` | Gatling 100 TPS mix in `tests/perf` against the running stack (`make up` first, fresh for the gate): re-applies the test-only routes, `gatlingRun` then always `perfOverhead` (overhead = response time minus core-mock and simulator time); reports in `tests/perf/build/reports/{gatling,perf}`; extra `-D` settings via `MW_PERF_ARGS`; see `tests/perf/README.md` |
| `make security [M=]` | security pass (WP29): gitleaks over the files not gitignored, CycloneDX SBOM + trivy per module, trivy on the built `:dev` images and the pinned third-party images (HIGH/CRITICAL fail; accepted findings with reason + expiry in `.trivyignore.yaml` / `deploy/trivyignore-third-party.yaml`); with the stack up also the ZAP baseline on APISIX and `scripts/log-scan.sh` over every container log (`SECURITY_LIVE=1` makes them mandatory: the gate runs it after `make e2e`); scanners from PATH or their pinned images; register `docs/security-findings.md` |
| `make log-scan` | `scripts/log-scan.sh` over every container log of the running MVP stack: fails on a PIN, PIN ciphertext, unmasked MSISDN or NID (findings printed masked; also a stage of `make security`) |
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
and the relevant `make e2e TAGS=...`; at a milestone gate `make verify-changed BASE=<gate base>` and
the e2e suite; a full `make verify` once at the end of a milestone run. Output pasted into the PR
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
