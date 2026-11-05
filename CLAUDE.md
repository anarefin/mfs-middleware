# upay MFS middleware: repo conventions

Spec: `spec/upay-mfs-middleware-spec.md`. This file is the short form of the rules every
module follows. Each service also has its own `CLAUDE.md` (responsibilities, contracts
owned, contracts used).

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
`*RepositoryIT` for repository: the JDBC repositories run against the module's JVM-scoped
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
- Ids: free-text `scrub` has no UUID/id-shape exemption; only system ids stay whole: in logs a
  `txnId`/`correlationId` that is an RFC 9562 UUID and a W3C `traceId`/`spanId`, in the audit trail
  a `<type>/<uuid>` resource and a UUID correlationId. A caller `X-Correlation-Id` (edge and
  `CorrelationIdFilter`) or `traceparent` (filter) with a run of 6+ digits is replaced/dropped.
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
| `make check-isolation [M=]` | module independence rules (fails on cross-module deps, `includeBuild`, convention plugins, paths outside the module except `contracts/`) |
| `make contracts-lint` | lint `contracts/` |
| `make versions-check [M=]` | report version drift between module version catalogs (report only, not in `verify`) |
| `make up [M=]` | Compose stack (`deploy/compose/mvp`) or one module's `compose.yml`; first generates the gitignored dev credentials in `deploy/compose/platform/.env` (`scripts/gen-dev-env.sh`), always rebuilds every stack module jar (`bootJar`) and its images (`up --build`; `MW_UP_NO_BUILD=1` skips both), then waits until services are healthy and one-shot inits exited 0 |
| `make smoke` | live checks against the running MVP stack (health, topics, Kafka, PG, Valkey, Keycloak tokens, OpenBao, APISIX `/healthz`) |
| `make edge-test` | live APISIX edge policy tests on the running stack (401/400/413/429 envelopes, X-Mw-* strip, correlation ID, idempotent apply); edge config rules in `deploy/apisix/README.md` |
| `make down [DOWN_ARGS=-v]` | stop the MVP stack (`-v` drops its volumes) |
| `make e2e [M=] [TAGS=@p2p]` | Cucumber suite in `tests/e2e` against the running stack (`make up` first), filtered by `@<module>` and `TAGS`; first re-runs `apisix-init` with `MW_E2E_ROUTES=1` (test-only routes, key-auth); stack access and reusable steps: `tests/e2e/README.md` |
| `make e2e-changed [BASE=main] [TAGS=]` | fast feedback after a change, no `verify`: rebuilds (`bootJar`, no tests) only the modules changed since `BASE` (commits, staged, unstaged, untracked) and recreates just their services in the running MVP project (`MW_UP_ONLY`); the whole stack when it is down or `contracts/`/`deploy/` changed; then the full e2e suite (`E2E_SCOPE=modules` runs only `@<changed-module>` scenarios). Not a substitute for `make up M=` (that runs the module as a separate compose project) or for the gate |
| `make perf [M=]` | Gatling suite in `tests/perf` |
| `make security [M=]` | gitleaks, CycloneDX SBOM, trivy (deps and images) |
| `make new-service NAME=mw-x` | scaffold a service from the template |
| `make test-scripts` | shell tests for the Makefile and `scripts/` (`scripts/test/`) |
| `cd services/mw-x && ./gradlew check` | one module on its own |

Definition of done: tests written first, `make verify M=<module>` and the relevant
`make e2e TAGS=...` green locally, output pasted into the PR template.

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
