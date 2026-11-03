# 03: Service template part 1: HTTP chassis

**What to build:** There is a runnable Spring Boot 4 / JDK 25 service template that every service is copied from. It is never depended on (D14). The template is a standalone Gradle build with its own settings, version catalog and wrapper; the Boot and Camel BOMs are Gradle platforms. It has a Dockerfile, a `compose.yml`, a short `CLAUDE.md` and a CycloneDX SBOM, and uses the layered package skeleton under `mfs.mw.<name>` (4.4). Built in: correlation ID filter (`X-Correlation-Id`, generated if absent), error envelope (5.3) with a global `@RestControllerAdvice`, `@Sensitive` masking applied to logs (MSISDN `0171****789`, NID last 3, account last 4, names first initial), JSON logs with the §21.1 fields, Vault/OpenBao config binding, actuator liveness/readiness and `/actuator/prometheus`, and virtual threads on. `make new-service NAME=mw-x` scaffolds a clean copy. Spec WP03.

**Blocked by:**

- 01: Repo skeleton and local quality gate

**Status:** ready-for-agent

- [ ] The template builds, tests and starts from its own directory alone: `./gradlew check bootJar && docker build .`
- [ ] Unit tests cover each masking rule and the mapping from exception to error envelope (code namespaces `MW-VAL`, `MW-AUTH`, `MW-SYS`, …)
- [ ] A log line emitted inside a request carries `correlationId` and has the masked value, never the raw one
- [ ] `make new-service NAME=mw-demo` produces a module with package `mfs.mw.demo` and no reference back to the template, and `make check-isolation` passes on it
- [ ] The container runs as non-root, and health endpoints are wired to a Compose `healthcheck`
