# 05: Template part 2a: Kafka/Camel defaults, retry topics, DLQ, Resilience4j, Flyway

**What to build:** Every service copied from the template inherits the same Kafka and Camel defaults, ready to use. These are: idempotent producer with `acks=all`; the §6.1 record headers (`mw-correlation-id`, `mw-txn-id`, `mw-idempotency-key`, `mw-source`, `mw-schema-version`, `traceparent`); a Camel error handler with non-blocking retry topics at 10 s, 1 min and 10 min, then DLQ (§19.2); Resilience4j wiring with the §19.2 defaults; and Flyway migrations that run on start against the service's own schema. Spec WP05 (part).

**Blocked by:**

- 03: Service template part 1: HTTP chassis
- 04: Event contracts v1

**Status:** done

- [x] A route test proves that a failing message goes through retry.1 → retry.2 → retry.3 → DLQ, with headers kept
- [x] A route test proves that a message which succeeds on retry is not sent to the DLQ
- [x] A Testcontainers test shows Flyway creating the template's own schema on start-up
- [x] Published records carry every required header, and a test asserts this
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (n/a: local tracker, no PR; test logs pasted in merge report)
- [x] The template's schema tests load contracts from the root contracts/ folder through mwContractsDir (carried from ticket 04)
