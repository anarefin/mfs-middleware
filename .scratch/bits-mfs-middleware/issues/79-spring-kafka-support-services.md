# 79: Spring Kafka in mw-admin-api, mw-simulators, mw-audit-dw, mw-portal-api, mw-core-mock

**What to build:** Copy the Spring Kafka code from the template (ticket 78) into five services and remove Camel from them completely.
Today, besides the outbox relay, they have these consumers:

| Service | Consumers |
|---|---|
| mw-admin-api | none, outbox only |
| mw-simulators | none, outbox only |
| mw-audit-dw | `AuditEventRoute` |
| mw-portal-api | `TxnStatusRoute` |
| mw-core-mock | `CoreWalletCommandRoute`, `CorePaymentCommandRoute` |

- **Per service:**
  - Replace `messaging/EventPublisher`, `OutboxRelay`, `KafkaConfig`/retry config and `MwHeaders` with the template versions; keep the service's own topic list.
  - Each `route/*Route.java` consumer becomes a `@KafkaListener` on the existing listener bean, and the listener signature goes from `Exchange` to `ConsumerRecord`.
  - Delete the `route/` package, `RetryDelayListener` and the camel deps in `build.gradle.kts`.
  - Change `application.yml` from `camel.component.kafka.*` to `spring.kafka.*` (the compose env vars in `deploy/docker-compose.yml` follow, if any name a camel property).
- **mw-admin-api:**
  - `client/DlqClient` (raw `KafkaConsumer`) reads its brokers from `spring.kafka.bootstrap-servers` instead of `camel.component.kafka.brokers`.
  - The DLQ replay keeps working on the D27 names and headers.
- **Tests:** port the tests that use Camel (`RetryTopicRouteIT`, `EventPublisherIT`, conformance ITs) from the template versions. The service's own listener ITs move to `ConsumerRecord`.
- **Docs:** update each service's `CLAUDE.md`.

**Blocked by:** 78

**Status:** open

- [ ] `grep -rl org.apache.camel services/{mw-admin-api,mw-simulators,mw-audit-dw,mw-portal-api,mw-core-mock}` is empty (build files included)
- [ ] `./gradlew :mw-admin-api:build :mw-simulators:build :mw-audit-dw:build :mw-portal-api:build :mw-core-mock:build` is green with Docker
- [ ] `./gradlew :contractsLint` passes (contracts.yml unchanged)
- [ ] With the stack up: `scripts/smoke.sh`, and the API suite folders for the DLQ / admin replay and the audit query pass
