# mw-service-template

The reference service every `services/mw-*` module is copied from (spec 4.2, WP03). It is
never a dependency and is not in the root composite build. Follow the root `CLAUDE.md`.

## Scaffolding

`make new-service NAME=mw-x` runs `scaffold.sh mw-x services/mw-x`: copies this folder
without build output, `scaffold.sh` or this file; renames package `mfs.mw.service.template`
to `mfs.mw.<x with hyphens as dots>`, `ServiceTemplateApplication` to `<X>Application`, and
every `mw-service-template` / `MW_SERVICE_TEMPLATE` / `service_template` name; writes a service `CLAUDE.md`; and
fails if any template name survives. So keep every template name in one of those forms,
and keep `scripts/test/test_service_template.sh` green.

## What the chassis has (HTTP part, WP03)

| Piece | Where | Spec |
| --- | --- | --- |
| Correlation ID filter (`X-Correlation-Id`, generated if absent or unsafe; a caller id with a run of 6+ digits, or one `MaskingPolicy.scrub` would change (e.g. a valid UUID spreading an MSISDN over its groups), is replaced too, WARN `correlation.id.replaced` without the value (the APISIX edge applies the 6+ digit rule); a caller `traceparent` whose trace or parent id is such a value is dropped with `tracestate` before tracing reads it, WARN `traceparent.dropped`; MDC `correlationId`) | `filter/CorrelationIdFilter` | 2.2, 21.3 |
| Security headers | `filter/SecurityHeadersFilter` | 18.6 |
| Error envelope, code namespaces -> HTTP status, global advice; logs route templates, not raw URIs | `exception/`, `dto/ErrorEnvelope` | 5.3, 18.6 |
| `@Sensitive` + `MaskingPolicy` (MSISDN `0171****789`, NID last 3, account last 4, name initial, secrets `****`, `PARTY_ID` by the party's `type`: wallet/MSISDN ids as an MSISDN, anything else last 4); `scrub` also masks MSISDNs written with a single `-`, `.`, `_`, `:` or space between any of their digits (`01711-234567`, `0171.2345.678`, `+880 1711-234567`, an MSISDN spread over UUID groups `aaaa0171-2345-678a-...` -> `0171****567` / `0171****678` / `+880171****567`) and leaves amounts, dates and times alone; no UUID or id-shape exemption: a digit run inside a UUID or trace id in free text is masked too, as a caller can craft an id that hides a number (ticket 50; every service's copy identical apart from the package) | `util/` | 18.3 |
| JSON logs with the 21.1 fields, masking before output; only system ids are written unmasked, on their shape alone (`CorrelationIdFilter` is the gate for caller correlation and trace ids): `txnId` and `correlationId` when a lower-case RFC 9562 UUID (version 1-8, variant 8-b), `traceId`/`spanId` when 32/16 lower-case hex (generated, or a caller traceparent the filter let through); anything else, `partnerId` always, is `MaskingPolicy.scrub`bed like free text; free text keeps digit-only bounds, so a number glued to letters is still masked | `logging/JsonLogFormatter` | 21.1 |
| Config binding, OpenBao/Vault via Spring Cloud Vault (AppRole, `VAULT_ENABLED`) | `config/MwServiceProperties`, `application.yml` | 18.4, 20.4 |
| Liveness/readiness, `/actuator/prometheus`, Brave tracing (W3C), virtual threads | `application.yml` | 20.1, 21.2, 21.3 |
| Image: layered jar, JDK 25 AOT cache, non-root uid 10001, readiness `HEALTHCHECK`, SBOM at `/app/sbom/bom.json` | `Dockerfile` | 20.1 |
| Compose: read-only FS, tmpfs `/tmp`, limits, `unless-stopped`, healthcheck, zone networks `app`, `data` | `compose.yml` | 20.2 |

## Kafka, Camel, resilience, Flyway (WP05 part)

| Piece | Where | Spec |
| --- | --- | --- |
| Camel Kafka component: idempotent producer, `acks=all`, group = service name, auto-commit off (per-record commit after handling or parking) | `application.yml` (`camel.component.kafka`) | 6.1 |
| `EventPublisher`: JSON record with the 6 headers (`mw-correlation-id`, `mw-txn-id`, `mw-idempotency-key`, `mw-source`, `mw-schema-version`, `traceparent`) | `messaging/` | 6.1, 21.3 |
| `RetryTopicRouteBuilder`: subclass per consumed topic; failures go `<topic>.<group>.retry.1..3` (10 s, 1 min, 10 min, configurable as `mw.kafka.retry.delays`; `RetryTopicRouteIT` shortens them) then `<topic>.<group>.dlq`, `<group>` = the service's consumer group (service name), so services sharing a topic never share parked records (declare them with `retry=<service>` in `deploy/kafka/topics.txt`); key, body and headers kept; delays pause the partition, never sleep on the consumer thread (`messaging/RetryDelayListener`); due times from the injected `Clock` bean (`config/ClockConfig`); logs ids and exception class only | `route/`, `messaging/`, `config/KafkaRetryProperties` | 19.2, 6.2 |
| Resilience4j defaults (CB 60 s window / 50 calls / 50% failed or slow / 30 s open / 5 probes, bulkhead 200, time limiter 30 s, retry 2x 200 ms -> 1 s jittered, idempotent calls only) | `application.yml` (`resilience4j`) | 19.2 |
| Own schema `service_template` (renamed by scaffold to the service name in snake case; services may pick the spec 16.2 name), Flyway on start from `db/migration/V<n>__*.sql` | `application.yml`, `src/main/resources/db/migration` | 16.2, 16.4 |
| Own type for `mw.config.changed.v1`, checked against `contracts/` via `mwContractsDir` | `dto/ConfigChangedEvent`, `ConfigChangedEventSchemaTest` | 20.4, 22.1 |
| Test-side schema loader: reads every `https://contracts.upay.mw/` `$id` from `mwContractsDir`, so `$ref`s between envelope, payload and `common/` schemas resolve offline | `src/test/.../contracts/ContractSchemas` | 4.3, 22.1 |

Tests that start the context import `TestInfrastructure` (PostgreSQL 18 + Kafka
Testcontainers, JVM-wide singletons, not Spring beans: a closed context must not stop them).
Tests that talk to Kafka directly use `TestInfrastructure.kafkaBootstrapServers()`, and assert
"nothing was published" with `KafkaTestConsumer.readToEnd` rather than a timed wait. Topic, retry-topic and DLQ declarations and their producer
ACLs belong to the platform (`deploy/kafka/`), not this module.

## Outbox, inbox, conformance suite (WP05 part)

| Piece | Where | Spec |
| --- | --- | --- |
| `outbox` and `inbox (consumer, event_id)` tables | `db/migration/V2__outbox_inbox.sql` | 6.4, 16.3 |
| `OutboxWriter.append(...)`: call inside the business transaction (refuses to run outside one); stores the 6.1 headers incl. `traceparent` | `messaging/` | 6.4 |
| `OutboxRelay` + `route/OutboxRelayRouteBuilder` (timer, `mw.outbox.relay.*`): one relay at a time per schema (advisory xact lock), `FOR UPDATE SKIP LOCKED`. Batched and pipelined: claims up to `batch-size` (500) unsent rows in id order, hands them all to the idempotent producer on the timer thread without waiting (`EventPublisher.publishStoredAsync`, a Camel `AsyncProducer` per topic, never `ProducerTemplate.asyncSend`, whose thread pool reorders sends), waits for every ack, then marks the acked rows sent in one statement; polls again at once while batches come back full, waits one `period` once drained or after a failure. Order per key: send order = id order, kept by Kafka (`enable.idempotence`, `acks=all`, max 5 in flight); a row Kafka did not ack stays unsent and holds back every later row of its key in the batch (acked or not), other keys go on. Crash/restart re-publishes (at-least-once). `OutboxRelayThroughputConformanceIT` drains 10,000 rows well above 1,000 rows/s in per-key order | `messaging/`, `route/` | 6.4, 19.3 |
| `Inbox.processOnce(consumer, eventId, sideEffect)`: inbox row and side effect in one transaction; duplicates are no-ops, a failed side effect leaves no row | `messaging/` | 6.4, 19.3 |
| Purge: `service/RetentionService` deletes sent outbox rows and inbox rows past retention in short key-ordered batches, never unsent rows; `scheduler/OutboxInboxPurgeJob` only calls it, on `mw.retention.cron` (hourly; `-` = off, as in tests), ShedLock-guarded (`config/SchedulingConfig`, `shedlock` table in `V3`) so one instance runs it. Settings in `config/RetentionProperties`: `mw.retention.outbox`, `mw.retention.inbox` (default 7d), `mw.retention.batch-size`, `mw.retention.cron` | `service/`, `scheduler/`, `config/` | 4.4, 6.4, 16.3 |
| Conformance suite: `@Conformance` tests (correlation, error envelope, masking, log fields, Kafka retry/DLQ incl. rebalance hold-back, outbox, inbox, purge). Copy unchanged into every service | `src/test/.../conformance/`, `route/RetryTopicRouteIT` | 4.3, 22.1 |

The relay route is off in tests (`src/test/resources/config/application.yml`) because cached
test contexts would relay each other's rows; a test that needs it on sets
`mw.outbox.relay.enabled=true` and `@DirtiesContext`.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew test` | unit, slice and contract tests; no Docker (`*IT` classes and `@Conformance` are left out) |
| `./gradlew integrationTest` | the Testcontainers `*IT` classes (not `@Conformance`); Docker |
| `./gradlew conformance` | the conformance suite alone (`RetryTopicRouteIT` runs its independent routes side by side, under 10 s) |
| `./gradlew check` | `test`, `integrationTest`, `conformance` and the JaCoCo gate over all three (>= 80% lines per `service`/`mapper`/`client` package, 22.1; report in `build/reports/jacoco`) |
| `make test-scripts` | includes `test_template_copy_checks_and_builds_its_image_alone`: a copy plus `contracts/` runs `./gradlew check bootJar && docker build .` (skipped when Docker is down) |
| `./gradlew check bootJar && docker build -t mw-service-template:dev .` | spec 4.3 rule 1 |
| `docker compose up -d --wait` | run it alone (creates/joins `${COMPOSE_PROJECT_NAME:-mw}_app`) |
| `make verify M=mw-service-template` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make verify-changed [BASE=main]` | the same gate for the modules changed since `BASE` only |

## Contracts

`contracts.yml` lists the contracts the module produces and consumes (paths relative to
`contracts/`, `#<Msg>` for one message type of a multi-type topic); the template's lists are empty.
`make contracts-lint` checks the entries and `make verify-changed` maps a contract change to the
modules listing it. A service that produces or consumes events adds `contracts/ProducedContractsTest`
(each `produces.events` entry built by the real mapper and serialised as the publisher does,
validated against its schema; an entry without a fixture fails) and `ConsumedContractsTest` (every
valid sample of each `consumes.events` entry reads into its own DTO), plain JUnit on
`contracts/ContractSchemas`, no Spring context, no Docker (ticket 53).
