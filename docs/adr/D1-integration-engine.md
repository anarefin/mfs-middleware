# D1: Apache Camel on Spring Boot 4, JDK 25 with virtual threads

Status: Accepted, 2026-11-03 (spec revision 1). Versions confirmed at the M0 gate: Spring Boot 4.1.2, Camel 4.22.2 LTS.

## Context

The middleware connects many partners with different protocols (REST/JSON, SMPP, callbacks) and runs long-lived request/reply conversations over Kafka. The RFP asks for an integration platform the Bits team can own, with open-source components and no per-core licence.

## Decision

Every service is a Spring Boot 4 application on JDK 25. Partner and messaging integration uses Apache Camel 4 (Java and YAML route DSL, no XML DSL) inside the Spring Boot app. Virtual threads are on (`spring.threads.virtual.enabled`), so blocking partner HTTP calls do not need a reactive stack.

## Consequences

- One programming model for HTTP APIs (Spring MVC), Kafka listeners and partner routes.
- Camel route templates make partner onboarding mostly configuration (the adapter framework, spec 12).
- JDK 25 is required on every build host (the quick start checks it); Camel and Boot upgrades are per module, because each module pins its own versions (D14).

## Where in the repo

- `services/mw-orchestrator/gradle/libs.versions.toml` (one catalog per module)
- `route/` packages, e.g. `services/mw-adapter-telco/src/main/java/mfs/mw/adapter/telco/partner/template`
- spec section 3
