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
| Correlation ID filter (`X-Correlation-Id`, generated if absent or unsafe, MDC `correlationId`) | `filter/CorrelationIdFilter` | 2.2, 21.3 |
| Security headers | `filter/SecurityHeadersFilter` | 18.6 |
| Error envelope, code namespaces -> HTTP status, global advice; logs route templates, not raw URIs | `exception/`, `dto/ErrorEnvelope` | 5.3, 18.6 |
| `@Sensitive` + `MaskingPolicy` (MSISDN `0171****789`, NID last 3, account last 4, name initial, secrets `****`) | `util/` | 18.3 |
| JSON logs with the 21.1 fields, masking before output | `config/JsonLogFormatter` | 21.1 |
| Config binding, OpenBao/Vault via Spring Cloud Vault (AppRole, `VAULT_ENABLED`) | `config/MwServiceProperties`, `application.yml` | 18.4, 20.4 |
| Liveness/readiness, `/actuator/prometheus`, Brave tracing (W3C), virtual threads | `application.yml` | 20.1, 21.2, 21.3 |
| Image: layered jar, JDK 25 AOT cache, non-root uid 10001, readiness `HEALTHCHECK`, SBOM at `/app/sbom/bom.json` | `Dockerfile` | 20.1 |
| Compose: read-only FS, tmpfs `/tmp`, limits, `unless-stopped`, healthcheck, zone networks `app`, `data` | `compose.yml` | 20.2 |

## Kafka, Camel, resilience, Flyway (WP05 part)

| Piece | Where | Spec |
| --- | --- | --- |
| Camel Kafka component: idempotent producer, `acks=all`, group = service name, auto-commit off (per-record commit after handling or parking) | `application.yml` (`camel.component.kafka`) | 6.1 |
| `EventPublisher`: JSON record with the 6 headers (`mw-correlation-id`, `mw-txn-id`, `mw-idempotency-key`, `mw-source`, `mw-schema-version`, `traceparent`) | `messaging/` | 6.1, 21.3 |
| `RetryTopicRouteBuilder`: subclass per consumed topic; failures go `<topic>.retry.1..3` (10 s, 1 min, 10 min) then `<topic>.dlq`, key, body and headers kept; delays pause the partition, never sleep on the consumer thread (`RetryDelayListener`); logs ids and exception class only | `route/`, `config/KafkaRetryProperties` | 19.2, 6.2 |
| Resilience4j defaults (CB 60 s window / 50 calls / 50% failed or slow / 30 s open / 5 probes, bulkhead 200, time limiter 30 s, retry 2x 200 ms -> 1 s jittered, idempotent calls only) | `application.yml` (`resilience4j`) | 19.2 |
| Own schema `service_template` (renamed by scaffold to the service name in snake case; services may pick the spec 16.2 name), Flyway on start from `db/migration/V<n>__*.sql` | `application.yml`, `src/main/resources/db/migration` | 16.2, 16.4 |
| Own type for `mw.config.changed.v1`, checked against `contracts/` via `mwContractsDir` | `dto/ConfigChangedEvent`, `ConfigChangedEventSchemaTest` | 20.4, 22.1 |

Tests that start the context import `TestInfrastructure` (PostgreSQL 18 + Kafka
Testcontainers, shared per run). Topic, retry-topic and DLQ declarations and their producer
ACLs belong to the platform (`deploy/kafka/`), not this module. Outbox/inbox and the
conformance suite come in ticket 06.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | all tests; `*IT` use Testcontainers (Docker) |
| `make test-scripts` | includes `test_template_copy_checks_and_builds_its_image_alone`: a copy plus `contracts/` runs `./gradlew check bootJar && docker build .` (skipped when Docker is down) |
| `./gradlew check bootJar && docker build -t mw-service-template:dev .` | spec 4.3 rule 1 |
| `docker compose up -d --wait` | run it alone (creates/joins `${COMPOSE_PROJECT_NAME:-mw}_app`) |
| `make verify M=mw-service-template` | isolation + contracts lint + `./gradlew check` |
