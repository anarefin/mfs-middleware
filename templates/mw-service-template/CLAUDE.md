# mw-service-template

The reference service every `services/mw-*` module is copied from (spec 4.2, WP03). It is
never a dependency and is not in the root composite build. Follow the root `CLAUDE.md`.

## Scaffolding

`make new-service NAME=mw-x` runs `scaffold.sh mw-x services/mw-x`: copies this folder
without build output, `scaffold.sh` or this file; renames package `mfs.mw.service.template`
to `mfs.mw.<x with hyphens as dots>`, `ServiceTemplateApplication` to `<X>Application`, and
every `mw-service-template` / `MW_SERVICE_TEMPLATE` name; writes a service `CLAUDE.md`; and
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
| Compose: read-only FS, tmpfs `/tmp`, limits, `unless-stopped`, healthcheck, zone network `app` | `compose.yml` | 20.2 |

Kafka/Camel, retry/DLQ, Resilience4j, Flyway (WP05) and outbox/inbox/conformance come
later; the Camel BOM is already a platform in `build.gradle.kts`.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | all tests; `*IT` use Testcontainers (Docker) |
| `./gradlew check bootJar && docker build -t mw-service-template:dev .` | spec 4.3 rule 1 |
| `docker compose up -d --wait` | run it alone (creates/joins `${COMPOSE_PROJECT_NAME:-mw}_app`) |
| `make verify M=mw-service-template` | isolation + contracts lint + `./gradlew check` |
