# D18: No Makefile: `./gradlew clean build` is the gate

Status: Accepted, 2026-11-13 (user decision, plan "Build, test and deploy the BitsPay way").
Amends M13 and D16.
Amendment (2026-11-13, BitsPay deploy alignment): the dev settings are the committed
`deploy/dev.env.defaults` (copied to `deploy/.env`, which Compose reads by itself), so the stack
command has no `--env-file` flags and no separate timers file (ADR D9, D16 amendments).

## Context

Until 2026-11-13 a 129-line Makefile was the pipeline (M13). Its targets called shell scripts that
looped over the modules with `xargs` (a verify, a verify-changed and a build script, plus helper
libraries for module lists, per-module checks and Testcontainers clean-up), and wrapper scripts for
the stack (up, seed, down). Much of it repeated what a multi-module Gradle build does by itself:
running every module, in parallel, and skipping what did not change. Each module ran in its own
Gradle invocation, so nothing came from the build cache across modules.

BitsPay (the reference project) has no Makefile: `./gradlew clean build` is the whole gate, and the
stack is plain `docker compose`.

## Decision

- No Makefile. Work happens through `./gradlew`, `docker compose` and the scripts in `scripts/` and
  `deploy/`, each run directly.
- `./gradlew clean build` from the repo root is the full gate: for every module compile, its one
  `test` task (D19) and its fixed-name boot jar; plus the repo checks, root tasks wired into the
  root `check`: `checkIsolation`, `checkFieldInjection`, `contractsLint` (with API suite
  traceability), `composeCheck` and `scriptTests`. They declare inputs and outputs, so they are
  cached like the module tasks.
- One module: `./gradlew :mw-x:build`, `./gradlew :mw-x:test`, `./gradlew :mw-x:bootJar`.
- One daemon builds every module with parallel execution, the build cache and the configuration
  cache. After `clean`, a module whose inputs did not change (its sources and the contract files its
  tests read) restores its test results and jar instead of running them again. That replaces the
  changed-modules selection of the old verify-changed gate.
- The stack: from `deploy/`, `cp dev.env.defaults .env`, then
  `docker compose --profile core --profile seed up -d --build --wait`
  (`deploy/RUNNING.html`); `deploy/reset.sh` wipes it. Live checks stay scripts:
  `scripts/smoke.sh`, `scripts/api-test.sh`, `scripts/ui-e2e.sh`, `scripts/edge-test.sh`,
  `scripts/security.sh`, `scripts/log-scan.sh`, `scripts/chaos.sh`, `scripts/suite.sh`.
- Docker images: the Dockerfiles copy the fixed jar `build/libs/mw-x.jar`; no AOT training stage and
  no SBOM copy in the image (`scripts/security.sh` builds its own SBOM).

## Consequences

- One command to learn for the gate, the same as BitsPay. A second `./gradlew clean build` with no
  changes comes from the cache.
- The `M=` and `JOBS=` knobs are gone: one module is `:mw-x:`, and parallelism is
  `org.gradle.workers.max` in the root `gradle.properties`, with module test tasks capped by
  `mwDockerTests` (a Docker and CPU budget, since every module's test JVM starts its own PostgreSQL
  and Kafka; D19 amendment).
- The full gate needs Docker and node on PATH (Testcontainers tests, `contractsLint`,
  `composeCheck`, `scriptTests`); without Docker the Testcontainers tests are skipped, not failed.
- `scripts/test/test_repo_docs.sh` checks that the docs name only existing scripts, Gradle tasks
  and knobs, and no make target.

## Where in the repo

- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` (root)
- `scripts/test/run.sh`, `scripts/test/test_repo_docs.sh`
- `deploy/RUNNING.html`, `deploy/reset.sh`
- spec decision table (D18), M13
