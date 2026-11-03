# 06: Template part 2b: outbox, inbox, conformance suite

**What to build:** Services that change state and publish events never lose or duplicate an event (§6.4). The template provides a transactional outbox: the business row and the outbox row are written in one DB transaction, and a relay publishes the outbox rows to Kafka and marks them sent. It also provides an inbox that records processed `eventId`s, so a redelivered event does nothing. A conformance test suite covers correlation propagation, error envelope, masking, log fields, Kafka retry/DLQ, outbox and inbox. The suite is copied into every service. Spec WP05 (part).

**Blocked by:**

- 05: Template part 2a: Kafka/Camel defaults, retry topics, DLQ, Resilience4j, Flyway

**Status:** done

- [x] Integration test: when the business transaction is rolled back, no outbox row is written and nothing is published
- [x] Integration test: the relay publishes pending rows after a simulated crash and restart, with no duplicates past the consumer's inbox
- [x] Integration test: the same `eventId` delivered twice has its side effect applied once
- [x] The conformance suite is green on the template and runs as part of `make verify`
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (n/a: local tracker, no PR; verify passed 99/99 script tests + template check after merge d9a622b)
