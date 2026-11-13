# D19: One test task per module, no coverage gate

Status: Accepted, 2026-11-13 (user decision, plan "Build, test and deploy the BitsPay way").
Amends spec 22.1 (the JaCoCo gate) and the ticket-53 test tiers.

## Context

Ticket 53 split each module's tests into three Gradle tasks: `test` (unit, slice and contract tests,
no Docker), `integrationTest` (the Testcontainers `*IT` classes) and `conformance` (the
`@Conformance` suite), with `check` running all three and a JaCoCo gate of 80% line coverage on the
`service`, `mapper` and `client` packages (spec 22.1, NFR-08). The integration and conformance JVMs
shared reused containers (`TESTCONTAINERS_REUSE_ENABLE`, labels per module), so the Makefile had to
drop databases, delete topics and remove the containers after each module. Three JVMs per module,
container clean-up scripts and a coverage report made every gate run slower and harder to reason
about, and the coverage number was never the thing that caught a bug.

BitsPay runs one `test` task per module, with Testcontainers the plain way.

## Decision

- Each module has one `test` task. It runs everything in one JVM: unit, slice and contract tests,
  the Testcontainers `*IT` classes and the `@Conformance` suite.
- Testcontainers the BitsPay way: one PostgreSQL and one Kafka per test JVM (singletons in the
  module's `TestInfrastructure`), no reuse, removed by Ryuk when the JVM exits. Each Spring test
  context gets a fresh database (`CREATE DATABASE ctx_<n>`), so no context sees another's rows;
  Kafka is shared and tests keep apart by topic, key and consumer group.
- Without Docker the `*IT` and conformance classes are skipped (`DockerAvailableCondition`), not
  failed, so `./gradlew clean build` passes on a host without Docker.
- No JaCoCo and no coverage gate. Tests are still written first (spec 23.2), and review checks that
  a change is tested.
- `./gradlew :mw-x:test --tests '<class>'` runs one class, Testcontainers or not.

## Consequences

- No `integrationTest` or `conformance` task, no `jacocoTestReport`; the PR template no longer
  asks for the coverage gate.
- No container clean-up step: a test run leaves no container behind.
- Merging the tiers into one JVM can expose Kafka cross-talk between tests (shared consumer group
  ids); it is fixed in the tests, not with per-JVM clean-up.
- Spec 22.1's coverage target and NFR-08 are not enforced by the build for the MVP.

## Amendment 2026-11-13: a cold gate in about 3 minutes on 8 cores (user decision)

A cold `./gradlew clean build --no-build-cache` took 7m45s. The test reports showed most of that
was waiting, not testing: the outbox relay idling 500 ms between saga hops, a fresh consumer group
joined by every test read, Camel waiting its 30 s Kafka `shutdownTimeout` for consumers caught in a
rebalance whenever a test context closed, and tests whose job is to wait (an absence window, a
timeout or retry delay running out). The target host has at most 8 cores, so test concurrency stays
at 4 and the time had to come from less work.

- Test infrastructure (behaviour of every test unchanged): `mw.outbox.relay.period: 50ms` and
  `camel.component.kafka.shutdown-timeout: 100` in each module's test config; `KafkaTestConsumer`
  reads with assigned partitions and no consumer group; the test Kafka is `apache/kafka-native`
  (same version as the stack).
- Still one `test` task, but a test whose time is a deliberate wait that proves an outcome is tagged
  `@Tag("slow-wait")` and left out unless `-PwithSlow` is given. The rule: still 2 s or more after the
  infrastructure fixes, and the time is a wait written into the test or the configuration under test.
  The tests are not rewritten or shortened. A milestone gate runs `./gradlew clean build -PwithSlow`;
  the per-ticket `./gradlew :mw-x:build` and the everyday cold build skip them.
- `org.gradle.workers.max=8` so compile and jars overlap tests; at most `mwDockerTests` (4) module
  test tasks at once, through a `mwDockerTests` build service every module registers under the same
  name (no shared code). `scripts/test/run.sh` runs the shell test files side by side
  (`SCRIPT_TESTS_JOBS`, default 3).

## Where in the repo

- `services/mw-orchestrator/build.gradle.kts` (the `test` task)
- `services/mw-orchestrator/src/test/java/mfs/mw/orchestrator/TestInfrastructure.java`
- `templates/mw-service-template/build.gradle.kts`
- spec decision table (D19), spec section 22.1
