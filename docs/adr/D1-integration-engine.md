# D1: Apache Camel on Spring Boot 4, JDK 25 with virtual threads

Status: Accepted, 2026-11-03 (spec revision 1). Versions confirmed at the M0 gate: Spring Boot 4.1.2, Camel 4.22.2 LTS.
Amended 2026-11-18 (user decision, ticket 77): Camel for external partner integration only; internal Kafka messaging moves to Spring Kafka (D27); partner routes are config (D28).

## Context

The middleware connects many partners with different protocols (REST/JSON, SMPP, callbacks) and runs long-lived request/reply conversations over Kafka. The RFP asks for an integration platform the Bits team can own, with open-source components and no per-core licence.

## Decision

Every service is a Spring Boot 4 application on JDK 25. Partner and messaging integration uses Apache Camel 4 (Java and YAML route DSL, no XML DSL) inside the Spring Boot app. Virtual threads are on (`spring.threads.virtual.enabled`), so blocking partner HTTP calls do not need a reactive stack.

## Consequences

- One programming model for HTTP APIs (Spring MVC), Kafka listeners and partner routes.
- Camel route templates turn partner onboarding into mostly configuration (the adapter framework, spec 12).
- JDK 25 is required on every build host (the quick start checks it); Camel and Boot upgrades are per module, because each module pins its own versions (D14): originally in a per-module version catalog, since 2026-11-13 inline in its `build.gradle.kts` (D13).

## Amendment 2026-11-18: Camel for partner integration only (user decision)

- Camel 4 is used **only** in the partner adapters (`mw-adapter-telco`, `mw-adapter-billpay`,
  `mw-adapter-bank`), for partner HTTP/REST, SOAP (camel-cxf) and SFTP (camel-sftp).
- Each partner is a Camel YAML DSL route stored as config and built from shipped Kamelets (D28).
- No other module depends on `org.apache.camel`. Internal Kafka messaging (listeners, publishers,
  retry topics and DLQ, the outbox relay) uses Spring Kafka (D27) in every module, the adapters
  included.
- The earlier camel-smpp and camel-mail lines of the spec are superseded: mw-ussd and mw-notify
  already use jSMPP and Spring Mail directly.

Reason: Camel was chosen to onboard billers without code. Using it as a Kafka client added a
hand-written retry chain and `Exchange`-coupled listeners for no integration benefit.

Consequences of the amendment: the `route/` package exists in the adapters only; every other
module's `route/` classes (Kafka request routes, `OutboxRelayRouteBuilder`, `RetryTopicRouteBuilder`)
move to `messaging/` and `scheduler/` on Spring Kafka (tickets 78–81); the adapter framework's
templates become Kamelets plus route-as-config (tickets 82–87). Once the migration lands, `./gradlew :checkIsolation`
rejects `org.apache.camel` outside `mw-adapter-*` (rule added by ticket 81).

## Where in the repo

- `services/mw-orchestrator/build.gradle.kts` (versions inline, one build file per module)
- the adapters' partner engine, e.g. `services/mw-adapter-telco/src/main/java/mfs/mw/adapter/telco/partner/route` (ADR D28)
- spec section 3
