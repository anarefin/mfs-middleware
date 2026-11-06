# MAP — Bits MFS middleware (read once, trust it)

Rules: root `CLAUDE.md` (conventions, error model, masking, log fields, "Never" list) and each
`services/<mw-x>/CLAUDE.md` (responsibilities, contracts owned/used). Spec: `spec/bits-mfs-middleware-spec.md`.
Tickets: `.scratch/bits-mfs-middleware/issues/<NN>-<slug>.md`; run order + status: `issues/DEPENDENCIES.md`.

## Layout
- `contracts/` files only: `events/<owner>/<topic>.schema.json` (+ `<topic>/<Msg>.payload.schema.json`,
  `samples/<topic>/[<Msg>/]{valid,invalid}*.json`), `api/<owner>/*.openapi.yaml`, `common/defs.v1.schema.json`.
  Owner per file: `contracts/OWNERS.yml`. Change only contracts your module owns (unless the ticket says so).
- `services/mw-*/`: 12 standalone Gradle builds (own wrapper, `gradle/libs.versions.toml`, `Dockerfile`,
  `compose.yml`, `CLAUDE.md`). Package `mfs.mw.<name without mw-, hyphens -> dots>`.
- `templates/mw-service-template/`: copied by `make new-service NAME=mw-x` (`scaffold.sh`); never a dependency.
- `deploy/`: `compose/platform` (PG, Kafka, Valkey, Keycloak, OpenBao), `compose/mvp` (+ `compose.lowmem.yml`),
  `apisix/` (routes, edge policies, README), Kafka topic definitions. Root `compose.yml` is GENERATED (`make compose-flat`).
- `tests/e2e/` Cucumber (feature files tagged `@<module>`, `@p2p`...; README has reusable steps), `tests/perf/` Gatling.
- `scripts/`: `verify.sh`, `changed-modules.sh`, `e2e-changed.sh`, `contracts-lint.sh`, `check-isolation.sh`,
  `check-field-injection.sh`, `compose-flat.sh`, `up.sh`, `down.sh`, `new-service.sh`, `lib/modules.sh`;
  shell self-tests in `scripts/test/test_*.sh` (runner `scripts/test/run.sh`).

## Modules (services/)
- mw-orchestrator: saga engine for all txn types, outbox/inbox, core commands, partner requests, internal txn query API (`/internal/v1/transactions`, Keycloak `services` realm JWT).
- mw-core-mock: stands in for the core banking/wallet system (core.command -> core.event), fault injection.
- mw-app-gateway: customer/agent/merchant app API, device binding + signature, OTP.
- mw-ussd: USSD engine (menu JSON, Valkey sessions), HTTP + SMPP transports.
- mw-checkout: merchant checkout sessions, pay, redirect, signed IPN with retries.
- mw-portal-api: org portal, bulk CSV + maker-checker, result file, SP txn query.
- mw-adapter-telco / -billpay / -bank: partner adapters (shared `partner/` framework, copied, identical).
- mw-notify: SMS (SMPP) + email notifications.
- mw-audit-dw: audit trail store + audit query API.
- mw-simulators: partner/OCS/SMSC/USSD-gateway simulators.
- mw-seeder (ticket 57): one-shot, no web server/DB/Kafka; seeds the stack through APIs only (Keycloak admin
  REST, mw-core-mock's test-only `PUT /core-mock/v1/wallets/{id}`, mw-admin-api maker-checker), idempotent;
  `make seed` runs it (profile `seed`) and copies its manifest to `build/seed/manifest.json`. Steps are
  `service/*Step` (`@Order`), runner `scheduler/SeedRunner`; data in its `application.yml` (`mw.seeder.*`).
- NOT YET: `mw-admin-api` (ticket 36, create with `make new-service NAME=mw-admin-api`), `mw-admin-ui` (Next.js 16.3.8, ticket 40).

## Tests
- `src/test/java/mfs/mw/<svc>/...` mirrors main packages.
- `*Test` unit/slice (`@WebMvcTest` for controllers); `*IT` Testcontainers (PG 18 + KRaft Kafka, JVM-scoped,
  `TestInfrastructure` imported with `@Import`); `*RepositoryIT` in `@SpringBootTest`; `conformance/*ConformanceIT`
  tagged `@Conformance` (task `conformance`); `contracts/ContractSchemas(Test)` validate against `contracts/`
  read via system property `mwContractsDir` (Gradle property, default `../../contracts`).
- After ticket 53: `test` = no Docker (no `*IT`), `integrationTest` = `*IT`, `check` = test + integrationTest + conformance + JaCoCo;
  each module has `contracts.yml` + `contracts/{Produced,Consumed}ContractsTest`. Until 53 lands, `test` includes `*IT` (Docker needed).

## Commands
- One module: `cd services/mw-x && ./gradlew test` ; single class: `./gradlew test --tests mfs.mw.x.service.FooTest`
- Compile only: `./gradlew compileJava compileTestJava`
- After 53: `./gradlew integrationTest`, `make verify-changed BASE=<sha> JOBS=3`
- Scripts: `make test-scripts` ; contracts: `make contracts-lint` ; isolation: `make check-isolation M=mw-x`
- Stack: `make up` (regenerates dev `.env`, builds jars+images), `make down [DOWN_ARGS=-v]`, `make smoke`,
  `make edge-test`, `make e2e [M=] [TAGS=@x]` (stack must be up), `make e2e-changed`.
- **Do NOT run `make verify` or `./gradlew check` on all modules per ticket** (hours). User rule (2026-11-05):
  per ticket run `./gradlew test` (+ `integrationTest` after 53) for the modules you touch only. Gates run later.

## Non-obvious
- Isolation: a module never references another module, the template, the root build, or any path outside its
  folder except `contracts/` via `mwContractsDir` (tests/build-time only). Duplicated chassis code is intended.
- Constructor injection only (field `@Autowired` fails `check-field-injection.sh`, tests included).
- Compile has `-Werror` (`-Xlint:all`): unused/raw/deprecation warnings break the build.
- Spring Boot 4.x, Jackson 3 (`tools.jackson.*` packages, `JsonMapper`), JSON schema via networknt (`com.networknt.schema`).
- JaCoCo 80% line gate per package on `service`, `mapper`, `client`.
- Kafka: transactional outbox (`messaging/OutboxWriter`) + inbox dedup; listeners never block; retry topics 10s/1m/10m then DLQ
  (`route/RetryTopicRouteBuilder`). Headers in `messaging/MwHeaders`.
- Masking: `util/MaskingPolicy` + `@Sensitive`; never log PIN / unmasked MSISDN, NID, account, name.
- Secrets from OpenBao (`VAULT_ENABLED`), dev init containers `init/init.sh` per service; test fixtures use fake values.
- Keycloak realms: `ops` (operators, MFA; admin API/UI), `services` (client-credentials for internal APIs), plus app/portal realms in `deploy/`.
- Error envelope `{code,message,messageBn?,txnId?,correlationId,retryable,details[]}`, codes `MW-VAL/AUTH/IDEM/CORE/PTR/TMO/SYS-*`,
  built by each service's `exception/GlobalExceptionHandler`.
