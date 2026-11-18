# 78: Service template on Spring Kafka (no Camel)

**What to build:** Move `templates/mw-service-template/` from camel-kafka to Spring Kafka (ADR D25, ticket 77). This
template is the reference that tickets 79–82 copy into each service (D14: copied, not shared). Behaviour must not
change: the same topics, retry/DLQ names, headers, outbox and inbox semantics.

- **`build.gradle.kts`:**
  - Remove the camel-spring-boot-bom, `camel-spring-boot-starter`, `camel-kafka-starter` and `camel-test-spring-junit6`.
  - Add `org.springframework.kafka:spring-kafka` (the version managed by Boot).
  - Keep `testcontainers-kafka`.
  - `./gradlew :checkIsolation` is still happy (same plugin versions).
- **`messaging/EventPublisher`:** use `KafkaTemplate<String,String>` (an idempotent producer, `acks=all`, max in-flight 5).
  - Sync send: `send(record).get(timeout)`.
  - `publishStoredAsync` returns the `CompletableFuture` from `send`.
  - Headers are those of `MwHeaders` and the existing `EventMetadata`.
  - `traceparent` is injected as today (Micrometer `Propagator`), or through `observationEnabled` on template and container, if that gives the same header.
- **`messaging/OutboxRelay`:** the JDBC logic does not change (advisory lock, `SKIP LOCKED`). The `route/OutboxRelayRouteBuilder`
  `timer:` becomes `scheduler/OutboxRelayScheduler` with `@Scheduled(fixedDelayString = "${mw.outbox.relay-interval}")`. The test config keeps 50 ms.
- **Retry and DLQ:** delete `route/RetryTopicRouteBuilder` and `messaging/RetryDelayListener`. Replace them with Spring Kafka
  non-blocking retries (`RetryTopicConfigurationSupport` / `RetryTopicConfigurationBuilder` in `config/KafkaConfig`).
  - **Names:** keep the topic names `<topic>.<group>.retry.<n>` and `<topic>.<group>.dlq`, through a custom retry-topic naming
    (`RetryTopicNamesProviderFactory`) or `DestinationTopicResolver`. Topics are not auto-created: the names must match `deploy/` Kafka topic definitions.
  - **Delays:** 10 s / 1 min / 10 min, taken from the existing `KafkaRetryProperties` (the short dev timers in `dev.env.defaults` still apply).
  - **Headers:** keep the headers `mw-retry-attempt`, `mw-original-topic`, `mw-error-class`, `mw-retry-not-before` (a
    `DeadLetterPublishingRecoverer` headers function). mw-admin-api's `DlqClient` and the DLQ replay read them.
  - **Non-retryable exceptions** (validation, schema): straight to DLQ (`notRetryOn`). This matches the current `onException` rules.
- **Listeners:**
  - Each consumer is a `@KafkaListener(topics=…, groupId="${spring.application.name}")` method that takes
    `ConsumerRecord<String,String>` and `Acknowledgment` (AckMode `MANUAL_IMMEDIATE` or `RECORD`, the same commit point as today).
  - No `org.apache.camel.Exchange` anywhere. `MwHeaders.text(Header)` replaces `text(Object)`.
  - Consumers can be switched off with a property, as `@ConditionalOnProperty …consumers.enabled` does today (`autoStartup`).
- **Spring Kafka hygiene:**
  - **Poison records:** `ErrorHandlingDeserializer` wraps the String deserializers, so a record that cannot be deserialized goes straight to the DLQ
    (not retried) and never blocks the partition.
  - **Concurrency:** set an explicit listener `concurrency` per container factory (property, default 1, at most the topic's partition count).
  - **Observation:** `observationEnabled=true` on `KafkaTemplate` and the listener containers, so traces flow through Micrometer.
  - **Thread use:** listener methods never block (root CLAUDE.md): any long work is handed off, as today.
- **`filter`/`messaging`: consumer-side context.** A `RecordInterceptor` (or listener observation) extracts the `traceparent` and the
  `X-Correlation-Id` header into MDC and clears it after the record. This closes today's gap, where only HTTP sets the MDC.
- **`application.yml`:**
  - `camel.component.kafka.*` becomes `spring.kafka.*`: bootstrap servers, `auto-offset-reset: earliest`, `enable-auto-commit: false`, String serdes, producer idempotence.
  - Test profile: container `shutdown-timeout` 100 ms, `KafkaTestConsumer` without a group, unchanged.
- **Tests (TDD, write them first):**
  - `RetryTopicIT`: a failing listener gives retry.1, then retry.2, then retry.3, then dlq. It checks the headers and names. The delay test runs with short timers; any ≥2 s wait is `@Tag("slow-wait")`.
  - `EventPublisherIT` on KafkaTemplate: headers and traceparent.
  - `OutboxRelaySchedulerIT`.
  - Delete `RetryTopicRouteIT`, `RetryTopicRouteBuilderTest` and `RetryDelayListenerTest`.
  - Adapt `OutboxRelayRecoveryConformanceIT` / `InboxConformanceIT` to `ConsumerRecord`.
- **Docs and scripts:**
  - Update `templates/mw-service-template/CLAUDE.md` (Kafka section).
  - Check that `scripts/new-service.sh` still prints the right compose block.

**Blocked by:** 77

**Status:** open

- [ ] `grep -r org.apache.camel templates/mw-service-template` is empty
- [ ] Template tests pass with Docker (retry → DLQ with the same names and headers, publisher, outbox relay, conformance)
- [ ] A record that cannot be deserialized goes to the DLQ at once, and the next record on that partition is still consumed
- [ ] `scripts/new-service.sh mw-tmp` scaffolds a module that builds (`./gradlew :mw-tmp:build`), then remove it
- [ ] `./gradlew :checkIsolation :checkFieldInjection :scriptTests` pass
