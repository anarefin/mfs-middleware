# mw-audit-dw

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.audit.dw`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Audit trail in the MVP (spec 14.3, WP10; no DW stream, SFTP export or `audit.payload` yet).
- Internal audit search (ticket 35, contract `api/mw-audit-dw/audit-query.v1.openapi.yaml`;
  `controller/AuditSearchController` -> `service/AuditSearchService` -> `repository/AuditSearchRepository`):
  `GET /internal/v1/audit/events` (txnId, actorType, actorId, action, resource, outcome, `[from, to)` on
  `at`, limit 1..500, offset; ordered by `at`, then chain order), for mw-admin-api over the `app`
  network, **no APISIX route**. Auth (M3 gate decision, `config/SecurityConfig`,
  `security/InternalCallerPolicy`, `mw.internal-auth.*`, same as mw-orchestrator's): Keycloak
  client-credentials JWT of realm `services`, exact issuer, `aud` containing `mw-audit-dw`, `azp`
  mw-admin-api only (the search has no org filter, so mw-portal-api is refused); no / bad token
  `401 MW-AUTH-001`, other client `403 MW-AUTH-002`; `InternalApiAuthIT`. Reads as `audit_reader` (`config/AuditSearchConfig`: its own lazy
  read-only pool `audit-search`, max 4, same reader credentials `mw.audit-dw.chain-verify.reader-*`;
  not a `DataSource` bean). Values come back as stored (already masked); operator usernames are not
  masked. No MSISDN filter: events carry no keyed hash, so an operator finds the txn by MSISDN on
  mw-orchestrator's query API, then its audit by txnId. `AuditSearchApiIT`. Consumes `mw.audit.event.v1` (`route/AuditEventRoute` ->
  `messaging/AuditEventListener` -> `service/AuditService.record`) with retry topics and DLQ; an
  unreadable event or one missing a required field throws and ends on the DLQ.
- Test-only audit API (ticket 58, contract `api/mw-audit-dw/audit-dw-test.v1.openapi.yaml`,
  `controller/AuditTestController`, `service/AuditTestService`, `AuditTestControllerTest`, `AuditTestServiceTest`): only with
  `mw.audit-dw.test-api.enabled=true` (`MW_AUDIT_DW_TEST_API_ENABLED`, set by `deploy/docker-compose.yml`
  for the dev stack; off, the paths do not exist). `GET /test/v1/audit/events` = the internal search
  (same filters, same `AuditPage`) without a token; `GET /test/v1/audit/chains/{month}` (yyyy-MM, else
  400 `MW-VAL-001`) -> `service/AuditTestService.verifyChain` -> `AuditChainVerifier.verify(month)` as `audit_reader`, 200 `{month, intact, rows,
  failedSeq, reason}` whatever the outcome; 503 `MW-SYS-002` when chain verification is switched off.
  Reached only through the test-only APISIX route `test-audit-dw` (`/test/audit-dw/v1/*`, GET, key-auth
  `mw_e2e`, `MW_E2E_ROUTES=1`); `SecurityConfig` guards only `/internal/**`, so `/test/**` needs no JWT.
  The Newman suite (`tests/api`) reads a bulk batch's trail (`resource=bulk-batch/<id>`) and checks the
  month's chain with it, instead of psql.
- `audit.event`: append-only, RANGE-partitioned by month on `recorded_at` (the writer's clock,
  UTC), partitions `event_yyyy_MM` created on the month's first event by the SECURITY DEFINER
  function `ensure_month_partition` (unique `seq` index + `month` check per partition). A
  trigger refuses UPDATE/DELETE even for the owner.
- Hash chain per partition (`service/HashChain`): `seq` from 1, `prev_hash` (first row: 32 zero
  bytes), `hash` = SHA-256 over the JSON array `[month, seq, auditId, recordedAt, at, txnId,
  correlationId, actorType, actorId, action, resource, outcome, service, sourceIp, payloadRef,
  before, after, prevHashHex]` (instants ISO-8601 UTC truncated to microseconds; before/after
  in PostgreSQL's `jsonb::text` form, obtained from PostgreSQL before hashing). `HashChainTest`
  pins the format with a golden vector. `audit.chain_head` (month, last seq, last hash) is
  locked `FOR UPDATE` to append (one writer per month at a time) and anchors the tail, so a
  deleted last row is detected too. Daily anchor export to WORM is not built (spec: TBC).
- Verification: `service/AuditChainVerifier.verify(month)` -> `ChainVerification` (intact, or the
  first broken `seq` and why); `months()` lists the chains. The service role cannot read
  `audit.event`, so the verifier gets a reading role's `JdbcTemplate`.
- Scheduled verification (ticket 50): `scheduler/AuditChainVerifyJob` (ShedLock `audit-chain-verify`,
  cron `mw.audit-dw.chain-verify.cron`, hourly at :30, `-` off as in tests) -> `service/ChainVerifyService.verifyAll()`
  verifies every month as the read-only role `audit_reader` (`config/AuditReaderConfig`: a lazy
  one-connection read-only Hikari pool on the service's JDBC URL, deliberately not a `DataSource`/
  `JdbcTemplate` bean; `mw.audit-dw.chain-verify.{enabled,reader-username,reader-password}`, password
  in OpenBao). A broken chain or an unreadable month -> ERROR `audit.chain.broken` (month, failedSeq,
  reason) and gauge `mw.audit.chain.broken` (broken months of the last run, 0 = intact); every run logs
  `audit.chain.verified`. `AuditChainVerifyIT` tampers a row as superuser and checks the reader can only
  read and the writer still cannot SELECT.
- Producers today: mw-orchestrator (`txn.state.changed`, `partner.callback.late`,
  `txn.outcome.conflict`, `partner.callback.rejected`) and the three adapters
  (`partner.call.completed`, S6: masked partner request/response in `after`), and mw-portal-api
  (`bulk.batch.uploaded|approved|rejected|expired|paused|resumed`, actor the operator). The consumer
  accepts any action that fits the contract; `AuditTrailIT` stores a partner-call event.
- Dedup: `audit.event_key` (auditId primary key, never purged, INSERT-only for the writer) in the
  same transaction, so a redelivery is refused however late it comes (the chassis inbox is
  purged after 7 d, so it is not used for this).
- Masking (18.3): producers mask; before storing, `MaskingPolicy.scrub` runs again over
  `correlationId`, `actor.id`, `resource`, `payloadRef` and every string, number and property
  name in `before`/`after` (a number the scrub changes is stored as the masked string; property
  names that mask alike are never overwritten, later ones get `#2`, `#3`... in order). The scrub
  has no UUID exemption, so only system ids escape it: a `resource` that is exactly
  `<type>/<uuid>` (type `[a-z][a-z0-9-]*`, a lower-case RFC 9562 UUID, version 1-8, variant 8-b;
  producers build these from system ids) and a `correlationId` that is such a UUID are stored as
  they are, on shape alone (`CorrelationIdFilter` is the gate: it replaces a caller correlation id
  with a 6+ digit run or one the scrub would change, e.g. a UUID spreading an MSISDN over its groups);
  any other resource, e.g. `txn/<crafted uuid shape>` or `foo/01712345678`, is scrubbed. `txnId` is parsed as a UUID. `AuditTrailIT` pins it. The hash
  covers the masked values.
- Roles (16.4): the service runs as `audit_writer` (INSERT only on `event` and `event_key`; SELECT/INSERT/UPDATE
  on `chain_head`; EXECUTE `ensure_month_partition`; DML on the chassis `inbox`/`outbox`/
  `shedlock`). Flyway runs as the schema owner `audit_dw_rw` (`SPRING_FLYWAY_USER` +
  `spring.flyway.password` from OpenBao); V4 grants the writer. `audit_reader` (V5): USAGE on the
  schema and SELECT on `event` and `chain_head` only. Nobody holds UPDATE/DELETE on
  `event`. A superuser can still bypass everything, which the chain detects (`AuditTrailIT`).
- Init (`mw-audit-dw-init`, label `mw.oneshot`, `init/init.sh`, idempotent, dev only): roles
  `audit_dw_rw`, `audit_writer` and `audit_reader`, schema `audit`, `secret/mw-audit-dw` (the three DB
  passwords, never overwritten; the reader's is `mw.audit-dw.chain-verify.reader-password`) and the AppRole `mw-audit-dw` (configtree volume). On a fresh stack it waits
  for OpenBao's `dev-init.json` and retries the database step when another init races it.

## Contracts owned

- `events/mw-audit-dw/mw.audit.event.v1.schema.json` (+ samples): own type `dto/AuditEvent`,
  checked by `AuditEventSchemaTest`. Produced by every service, consumed here.
- `api/mw-audit-dw/audit-query.v1.openapi.yaml` (internal audit search, ticket 35).
- `api/mw-audit-dw/audit-dw-test.v1.openapi.yaml` (test-only audit search and chain check, ticket 58).

## Contracts used

- None besides its own `mw.audit.event.v1`.
- `contracts.yml`: the machine-readable list of what this module produces and consumes (checked
  by `make contracts-lint`, tested by `contracts/ConsumedContractsTest` (every valid sample reads
  into `dto/AuditEvent`) and `contracts/ApiContractsTest` (search responses against
  `audit-query.v1`, test API responses against `audit-dw-test.v1`); no Docker). It produces no events, so there is no `ProducedContractsTest`
  (`ConsumedContractsTest.producesNoEvents` fails if the manifest gains one).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (`security-*` in
  `gradle/libs.versions.toml`; drop once the Boot / Camel BOMs ship them); the Dockerfile runs
  `apt-get upgrade` in the runtime stage and `init/Dockerfile` runs `apk upgrade` and removes gosu. `make security M=<this module>` scans the SBOM and the
  `mfs-mw/*:local` images (HIGH/CRITICAL fail; accepted ones in `.trivyignore.yaml` with a reason and expiry).
- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, unsafe or holding a
  run of 6+ digits or one the scrub would change (WARN `correlation.id.replaced`), MDC
  `correlationId`; drops a caller `traceparent` whose trace or parent id is such a value.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field. Party ids are
  `PARTY_ID` (ticket 23): masked by the party's `type` (wallet/MSISDN as an MSISDN, a bank account or any
  other type last 4 only).
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
  Add context with `log.atInfo().addKeyValue("event", "...")` or the MDC.
- `config/MwServiceProperties`: config binding; secrets from OpenBao when `VAULT_ENABLED=true`.
- `messaging/EventPublisher`: Kafka records with the 6.1 headers, idempotent `acks=all` producer.
- `route/RetryTopicRouteBuilder` + `messaging/RetryDelayListener`: subclass per consumed topic; retry
  topics 10 s / 1 min / 10 min then DLQ (19.2); per-record commit; no sleeps on Kafka threads.
- `application.yml`: Resilience4j defaults (19.2), own schema `audit` migrated by Flyway on
  start from `db/migration` (16.4).

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew test` | unit, slice and contract tests (`contracts/`), no Docker |
| `./gradlew integrationTest` | Testcontainers `*IT` classes (Docker needed) |
| `./gradlew conformance` | the `@Conformance` suite (Docker needed) |
| `./gradlew check` | `test` + `integrationTest` + `conformance`, JaCoCo gate (80% lines on `service`/`mapper`/`client`, over all three) |
| `./gradlew check bootJar && docker build -t mw-audit-dw:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-audit-dw` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make verify-changed [BASE=main]` | the gate for the modules changed since `BASE` (this one when it or a contract in its `contracts.yml` changed) |
| `make build M=mw-audit-dw` | `bootJar`, then this service's images (`mfs-mw/mw-audit-dw:local`, `-init`) |
| `make up M=mw-audit-dw` | start this service and what it depends on from `deploy/docker-compose.yml` (`TIMERS=fast` for short test timers) |
