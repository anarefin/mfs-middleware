# mw-service-template

The reference service every `services/mw-*` module is copied from (spec 4.2, WP03). It is
never a dependency and is not a module of the root build (the root `settings.gradle.kts` includes
`services/*` only). Follow the root `CLAUDE.md`.

## Scaffolding

`scripts/new-service.sh mw-x` runs `scaffold.sh mw-x services/mw-x`: copies this folder
without build output, `scaffold.sh` or this file; renames package `mfs.mw.service.template`
to `mfs.mw.<x with hyphens as dots>`, `ServiceTemplateApplication` to `<X>Application`, and
every `mw-service-template` / `MW_SERVICE_TEMPLATE` / `service_template` name; writes a service `CLAUDE.md`; and
fails if any template name survives. The root build picks `services/mw-x` up by itself
(`./gradlew :mw-x:build`); the copy has no wrapper, settings or `gradle.properties` of its own. So keep every template name in one of those forms,
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
| Image: copies the fixed jar `build/libs/mw-service-template.jar` (never a stale one), extracts its layers, no AOT cache and no SBOM in the image (`scripts/security.sh` builds its own); `JVM_OPTS` build arg = `JAVA_TOOL_OPTIONS` (the dev stack sets Serial GC there and only sizes in `JDK_JAVA_OPTIONS`, ticket 55), non-root uid 10001, readiness `HEALTHCHECK` | `Dockerfile` | 20.1 |
| Compose: no `compose.yml` in the module (ADR D16); `scaffold.sh` prints the service block for `deploy/docker-compose.yml`, which merges `x-app` (read-only FS, tmpfs `/tmp`, `unless-stopped`, readiness healthcheck, zone networks `app`, `data`) and sets the limits | `scaffold.sh` | 20.2 |

## Kafka, resilience, Flyway (WP05 part)

Kafka goes through Spring Kafka (ADR D25); no Camel in this template (Camel is for partner
integration in `mw-adapter-*` only, ADR D1 amendment).

| Piece | Where | Spec |
| --- | --- | --- |
| Spring Kafka client: one consumer group per service (`spring.kafka.consumer.group-id` = service name), auto-commit off, `auto-offset-reset: earliest`; producer `acks=all`, idempotent, at most 5 in flight; `ErrorHandlingDeserializer` over the strict `messaging/Utf8Deserializer` (a record that is not UTF-8 goes straight to the DLQ, its partition goes on); `StringOrBytesSerializer` (so the raw bytes of such a record can be parked); listener AckMode `MANUAL_IMMEDIATE`, `concurrency` = `MW_KAFKA_LISTENER_CONCURRENCY` (default 1, at most the partition count), Micrometer observation on; topics never auto-created; `mw.kafka.send-timeout`, `mw.kafka.listener.shutdown-timeout` (100 ms in tests) | `application.yml` (`spring.kafka`, `mw.kafka`), `config/KafkaConfig`, `config/MwKafkaProperties` | 6.1, 21.3 |
| Listener: `@KafkaListener(topics = ..., groupId = "${spring.kafka.consumer.group-id}", autoStartup = "${mw.<svc>.consumers.enabled:true}")` on a method taking `ConsumerRecord<String, String>` and `Acknowledgment`; it calls `ack.acknowledge()` after the record is handled (the commit point; a failure is committed by the error handler once parked). Never blocks: long work is handed off. `MwHeaders.text(record.headers().lastHeader(...))` reads a header. `autoStartup` also covers the listener's retry consumers | `messaging/` | 6.1, 19.2 |
| `EventPublisher`: JSON record with the 6 headers (`mw-correlation-id`, `mw-txn-id`, `mw-idempotency-key`, `mw-source`, `mw-schema-version`, `traceparent`); `publish` sends through the observed `kafkaTemplate` and waits for the ack (the template's observation writes the producer span, a child of the caller's, as `traceparent`); `publishStoredAsync` sends a stored record through `forwardingKafkaTemplate` (no observation, headers exactly as stored) and returns the send future. Both templates share Boot's one producer | `messaging/`, `config/KafkaConfig` | 6.1, 21.3 |
| Retry topics (Spring Kafka non-blocking retries, one `RetryTopicConfiguration` for every `@KafkaListener` on the default factory except `mw.kafka.retry.exclude-topics`): failures go `<topic>.<group>.retry.1..3` (10 s, 1 min, 10 min, `mw.kafka.retry.delays`; `RetryTopicIT` shortens them) then `<topic>.<group>.dlq` (`messaging/RetryTopicNaming`; retry groups `<group>.retry.<n>`), `<group>` = the service's consumer group, so services sharing a topic never share parked records (declare them with `retry=<service>` in `deploy/kafka/topics.txt`); validation and schema failures (`jakarta.validation.ValidationException`, `tools.jackson.core.JacksonException`) and undeserializable records go to the DLQ at once; the DLQ is never consumed by the service (its container does not start); key, body and headers kept, plus `mw-retry-attempt` (failed deliveries so far), `mw-original-topic`, `mw-error-class` (class only), `mw-retry-not-before` (not on the DLQ) from `messaging/RetryTopicRecoverer`; no exception message or stack trace in headers or logs; logs `kafka.retry.scheduled` / `kafka.dlq.parked` with ids and exception class only. A delay pauses the partition and seeks back, never sleeps on the consumer thread; resumes run on their own scheduler (`RetryTopicSchedulerWrapper`); a forged due time is capped at the record timestamp plus that retry's delay (`messaging/RetryDueTimeCap`); due times from the injected `Clock` bean (`config/ClockConfig`) | `config/KafkaConfig`, `messaging/`, `config/KafkaRetryProperties` | 19.2, 6.2 |
| Consumer-side context: while a record is handled the MDC carries its `correlationId` / `txnId` (headers `mw-correlation-id`, `mw-txn-id`) and, from the listener observation, the `traceId` / `spanId` of its `traceparent`; cleared after each record | `messaging/ConsumerContextInterceptor`, `config/KafkaConfig` | 21.1, 21.3 |
| Resilience4j defaults (CB 60 s window / 50 calls / 50% failed or slow / 30 s open / 5 probes, bulkhead 200, time limiter 30 s, retry 2x 200 ms -> 1 s jittered, idempotent calls only) | `application.yml` (`resilience4j`) | 19.2 |
| Own schema `service_template` (renamed by scaffold to the service name in snake case; services may pick the spec 16.2 name), Flyway on start from `db/migration/V<n>__*.sql` | `application.yml`, `src/main/resources/db/migration` | 16.2, 16.4 |
| Own type for `mw.config.changed.v1`, checked against `contracts/` via `mwContractsDir` | `dto/ConfigChangedEvent`, `ConfigChangedEventSchemaTest` | 20.4, 22.1 |
| Test-side schema loader: reads every `https://contracts.bits.mw/` `$id` from `mwContractsDir`, so `$ref`s between envelope, payload and `common/` schemas resolve offline | `src/test/.../contracts/ContractSchemas` | 4.3, 22.1 |

Tests that start the context import `TestInfrastructure` (PostgreSQL 18 + Kafka
Testcontainers, JVM-wide singletons, not Spring beans: a closed context must not stop them).
One PostgreSQL and one Kafka per test JVM (the module's one `test` task, ADR D19), never reused
(no `withReuse`, no labels) and removed by Ryuk when the JVM exits. Every Spring context gets an
empty database of its own (`CREATE DATABASE ctx_<n>`); Kafka is shared, so tests keep apart by
their own topics, keys and consumer groups. `TestInfrastructureIT` checks it. Classes that need
Docker are `*IT`, skipped without Docker by `DockerAvailableCondition`. The root
`gradle.properties` (no module has its own) turns on the configuration cache, the build cache,
parallel modules and `-Xmx2g -XX:+UseParallelGC` for the daemon; the test JVM has `maxHeapSize = "1g"`.
Tests that talk to Kafka directly use `TestInfrastructure.kafkaBootstrapServers()`, and assert
"nothing was published" with `KafkaTestConsumer.readToEnd` rather than a timed wait. Topic, retry-topic and DLQ declarations and their producer
ACLs belong to the platform (`deploy/kafka/`), not this module; a test that consumes creates its
topic, retry topics and DLQ first (`KafkaTestTopics.createWithRetries`, in a `@Bean` method of its
test configuration, so they exist before the containers subscribe) and gives its context its own
`spring.kafka.consumer.group-id`, so its retry topics are its own.

## Outbox, inbox, conformance suite (WP05 part)

| Piece | Where | Spec |
| --- | --- | --- |
| `outbox` and `inbox (consumer, event_id)` tables | `db/migration/V2__outbox_inbox.sql` | 6.4, 16.3 |
| `OutboxWriter.append(...)`: call inside the business transaction (refuses to run outside one); stores the 6.1 headers incl. `traceparent` | `messaging/` | 6.4 |
| `OutboxRelay` + `scheduler/OutboxRelayScheduler` (`@Scheduled(fixedDelayString = "${mw.outbox.relay.period}")`, off with `mw.outbox.relay.enabled=false`, ShedLock-guarded): one relay at a time per schema (advisory xact lock), `FOR UPDATE SKIP LOCKED`. Batched and pipelined: claims up to `batch-size` (500) unsent rows in id order, hands them all to the idempotent producer on the scheduler thread without waiting (`EventPublisher.publishStoredAsync`: `KafkaTemplate.send` hands the record to the producer on the calling thread, in call order), waits for every ack, then marks the acked rows sent in one statement; polls again at once while batches come back full, waits one `period` once drained or after a failure. Order per key: send order = id order, kept by Kafka (`enable.idempotence`, `acks=all`, max 5 in flight); a row Kafka did not ack stays unsent and holds back every later row of its key in the batch (acked or not), other keys go on. Crash/restart re-publishes (at-least-once). `OutboxRelayThroughputConformanceIT` drains 10,000 rows well above 1,000 rows/s in per-key order; `OutboxRelaySchedulerIT` checks the schedule | `messaging/`, `scheduler/` | 6.4, 19.3 |
| `Inbox.processOnce(consumer, eventId, sideEffect)`: inbox row and side effect in one transaction; duplicates are no-ops, a failed side effect leaves no row | `messaging/` | 6.4, 19.3 |
| Purge: `service/RetentionService` deletes sent outbox rows and inbox rows past retention in short key-ordered batches, never unsent rows; `scheduler/OutboxInboxPurgeJob` only calls it, on `mw.retention.cron` (hourly; `-` = off, as in tests), ShedLock-guarded (`config/SchedulingConfig`, `shedlock` table in `V3`) so one instance runs it. Settings in `config/RetentionProperties`: `mw.retention.outbox`, `mw.retention.inbox` (default 7d), `mw.retention.batch-size`, `mw.retention.cron` | `service/`, `scheduler/`, `config/` | 4.4, 6.4, 16.3 |
| Conformance suite: `@Conformance` tests (correlation, error envelope, masking, log fields, Kafka retry/DLQ names and headers, poison records, hold-back without blocking or committing past, due-time cap, consumer MDC, outbox, inbox, purge). Copy unchanged into every service | `src/test/.../conformance/`, `messaging/RetryTopicIT` | 4.3, 22.1 |

The relay schedule is off in tests (`src/test/resources/config/application.yml`) because cached
test contexts would relay each other's rows; a test that needs it on sets
`mw.outbox.relay.enabled=true` and `@DirtiesContext`.

## Commands

| Command (from the repo root, once copied to `services/mw-x`) | What it does |
| --- | --- |
| `./gradlew :mw-x:test` | every test in one JVM: unit, slice and contract tests, the Testcontainers `*IT` classes and the conformance suite (`RetryTopicIT` runs the whole retry path with shortened delays, about 10 s); one PostgreSQL and one Kafka per test JVM (`TestInfrastructure`), a fresh database per Spring context; the `*IT` classes are skipped without Docker (`DockerAvailableCondition`). No coverage gate (ADR D19) |
| `./gradlew :mw-x:build` | `test`, then the boot jar `build/libs/mw-x.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-x:bootJar && docker build -t mfs-mw/mw-x:local services/mw-x` | spec 4.3 rule 1 |
| `./gradlew :scriptTests` | includes `scripts/test/test_service_template.sh`: `scripts/new-service.sh` makes a clean copy, and the root settings pick it up as a module |
| `./gradlew clean build` | the full gate: every module, plus the repo checks (`:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`); `-PwithSlow` adds the `slow-wait` tests (ADR D19) |

## Contracts

`contracts.yml` lists the contracts the module produces and consumes (paths relative to
`contracts/`, `#<Msg>` for one message type of a multi-type topic); the template's lists are empty.
`./gradlew :contractsLint` checks the entries. The listed contract files are inputs of the module's
`test` task, so a contract change re-runs the tests of the modules listing it and, through the build
cache, no others. A service that produces or consumes events adds `contracts/ProducedContractsTest`
(each `produces.events` entry built by the real mapper and serialised as the publisher does,
validated against its schema; an entry without a fixture fails) and `ConsumedContractsTest` (every
valid sample of each `consumes.events` entry reads into its own DTO), plain JUnit on
`contracts/ContractSchemas`, no Spring context, no Docker (ticket 53).

## Security pass

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (written inline in
  `build.gradle.kts`; drop once the Boot BOM ships them); the Dockerfile adds no OS packages to the
  pinned JRE base image. `scripts/security.sh <this module>` scans the SBOM and the
  `mfs-mw/*:local` images (HIGH/CRITICAL fail; accepted ones in `.trivyignore.yaml` with a reason and expiry).
