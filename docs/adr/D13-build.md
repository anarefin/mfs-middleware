# D13: One Git repo, Gradle Kotlin DSL, one self-contained build file per module

Status: Accepted, 2026-11-03 (spec revisions 2 and 3: Gradle replaces Maven). Amended 2026-11-13:
the root is a multi-module build instead of a composite of standalone builds (user decision).

## Context

Twelve services should be built and released independently, yet live in one repo for review and contracts.

The first layout (2026-11-03 to 2026-11-13) made every module a standalone Gradle build: its own
wrapper, `settings.gradle.kts`, `gradle.properties` and version catalog (`gradle/libs.versions.toml`),
with a root composite build (`includeBuild` per module, `checkAll`, `bootJarAll`). In practice 14
included builds meant 14 configurations, no shared build cache hits across them and a Makefile with
per-module scripts to run anything across all modules. BitsPay (the reference project) runs one
multi-module Gradle build: one daemon, one wrapper, the build cache, and `./gradlew clean build` as
the whole gate.

## Decision

One Git repo. Each module under `services/` is a complete Spring Boot application with its own
`build.gradle.kts` and Dockerfile (since D16 no `compose.yml`, the stack is `deploy/docker-compose.yml`).

Since 2026-11-13 the root is a multi-module Gradle build:

- The root `settings.gradle.kts` includes every `services/*` folder that has a `build.gradle.kts`
  (discovered, so a new service never edits it). One wrapper and one `gradle.properties` at the
  root (parallel, build cache, configuration cache, `workers.max=4` as a Docker memory budget).
- The root `build.gradle.kts` applies only `base` and registers the repo checks (`checkIsolation`,
  `checkFieldInjection`, `contractsLint`, `composeCheck`, `scriptTests`) wired into its `check`.
- Still no shared build logic: no `allprojects` / `subprojects`, no `buildSrc`, no convention
  plugins, no version catalog, no `project(":…")` dependency between modules.
- Each module writes its versions inline in its own `build.gradle.kts` (plugin versions, BOM
  platforms, coordinates). Gradle rejects two versions of one plugin in one build, so every module
  uses the same plugin versions; `./gradlew :checkIsolation` enforces it.
- Each module's jar has a fixed name, `build/libs/mw-x.jar`, which its Dockerfile copies.

Reason for the amendment: the BitsPay pattern, one daemon and the build cache (an unchanged module
is restored after `clean`), user decision of 2026-11-13.

## Consequences

- A module builds and tests on its own with `./gradlew :mw-x:build` from the root; it no longer
  builds from its own folder with its own wrapper.
- Build-level duplication (build scripts, inline versions) is accepted; `scripts/versions-check.sh`
  reports drift between modules. Plugin versions cannot drift.
- Moving a module to its own repo means adding a `settings.gradle.kts`, a wrapper and a
  `gradle.properties` again; its build file needs no change.
- Tests: originally three tiers (ticket 53: `test` without Docker, `integrationTest` for `*IT`,
  `check` adding conformance and JaCoCo); since 2026-11-13 one `test` task per module (D19).

## Where in the repo

- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` (root)
- `services/mw-notify/build.gradle.kts` (a module build file)
- `scripts/check-isolation.sh`, `scripts/versions-check.sh`
- spec section 4.2
