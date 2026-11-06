# D13: One Git repo, Gradle Kotlin DSL, every module a standalone build

Status: Accepted, 2026-11-03 (spec revisions 2 and 3: Gradle replaces Maven).

## Context

Twelve services should be built and released independently, yet live in one repo for review and contracts.

## Decision

One Git repo. Each module under `services/` is its own Gradle build (own wrapper, `settings.gradle.kts`, version catalog, Dockerfile; since D16 no `compose.yml`, the stack is `deploy/docker-compose.yml`) and a complete Spring Boot application. The root is a Gradle composite build for convenience only (`checkAll`, `bootJarAll`), with no shared configuration, `buildSrc` or convention plugins.

## Consequences

- A module builds and tests from its own folder alone.
- Build-level duplication (catalogs, build scripts) is accepted; `make versions-check` reports drift.
- The test tiers (ticket 53): `test` without Docker, `integrationTest` for `*IT`, `check` adds conformance and JaCoCo.

## Where in the repo

- `settings.gradle.kts`, `build.gradle.kts` (root)
- `scripts/check-isolation.sh`
- spec section 4.2
