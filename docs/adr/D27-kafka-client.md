# D27: Spring Kafka for internal messaging

Status: Accepted, 2026-11-18 (user decision, ticket 77). Amends D1 (Camel no longer carries Kafka
traffic) and spec 4.4, 6.2, 6.4 and 22.1; D2 is unchanged.

## Context

D1 put every Kafka consumer, producer, retry topic and outbox relay on Camel's Kafka component.
That gave each module a hand-written retry chain (`RetryTopicRouteBuilder`), listeners coupled to
Camel's `Exchange`, a Camel context in services that have no partner to integrate, and Camel-specific
test settings (the consumers' shutdown timeout, D19 amendment). None of it uses what Camel is for:
connecting to external systems in many protocols. Spring Boot already manages Spring Kafka, whose
listener container, error handler and non-blocking retry topics cover the same needs.

## Decision

- Every module talks to Kafka through Spring Kafka (`spring-kafka`, the version managed by Spring
  Boot). No module uses Camel's Kafka component, the adapters included.
- Consumers are `@KafkaListener` methods in `messaging/` with record-level manual acknowledgement:
  a record is acknowledged after its business transaction (and its inbox row) commits.
- Producers use `KafkaTemplate`, idempotent with `acks=all`.
- Non-blocking retry topics follow D2: 10 s, 1 min, 10 min, then a DLQ. **The topic names stay**
  `<topic>.<group>.retry.<n>` and `<topic>.<group>.dlq` (spec 6.2), so the topic catalogue and the
  ops tooling do not change. The retry and DLQ producer is the Spring Kafka error handler.
- The transactional outbox and the inbox are unchanged (D2, spec 6.4). The outbox relay is a
  `@Scheduled` job in `scheduler/` (guarded by ShedLock where a module already uses it) that sends
  pending rows with `KafkaTemplate` and marks them sent.
- Listeners never block (no synchronous waits for replies, sleeps or long partner calls on the
  consumer thread), as before.
- Consumers extract the W3C trace context and the correlation id from the record headers into the
  MDC before the listener runs, and clear it after, so the log fields (spec 21.1) stay the same.

## Consequences

- `route/` disappears from every module but the adapters (D28); its Kafka routes move to
  `messaging/` and the outbox relay to `scheduler/`.
- Each module's tests: Spring Kafka listener `*IT` classes replace the Camel route tests for Kafka
  paths; the test config gives the listener container a 100 ms shutdown timeout instead of Camel's
  Kafka consumers.
- The conformance suite keeps checking retry/DLQ, outbox and inbox behaviour; only the
  implementation under it changes.
- Rolled out by tickets 78 (template), 79, 80 and 81 (the services); each fixes the docs pages that
  still describe Camel for Kafka (`docs/tutorial/02-architecture.html`, `docs/tutorial/glossary.html`).

## Where in the repo

- `templates/mw-service-template/` (from ticket 78)
- `messaging/` and `scheduler/` packages of every service
- `deploy/kafka/topics.txt` (retry and DLQ topic names unchanged)
- spec decision table (D27), sections 4.4, 6.2, 6.4, 22.1
