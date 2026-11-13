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

## Where in the repo

- `services/mw-orchestrator/build.gradle.kts` (the `test` task)
- `services/mw-orchestrator/src/test/java/mfs/mw/orchestrator/TestInfrastructure.java`
- `templates/mw-service-template/build.gradle.kts`
- spec decision table (D19), spec section 22.1
