# M13: GitHub repo, no CI/CD: the local build is the pipeline

Status: Accepted, 2026-11-03. Gate cadence revised on 2026-11-05: `make verify-changed` at milestone
gates, one full `make verify` at the end of M6. Amended 2026-11-13 (D18): no Makefile;
`./gradlew clean build` is the gate.

## Context

CI/CD tooling is chosen separately (D10), but every change still needs a repeatable quality gate.

## Decision

The repo is on Bits's GitHub. Tests run locally; the pull request carries the test report and the
reviewer re-runs the gate before merging.

- Originally (2026-11-03 to 2026-11-13) the gate was a set of Makefile targets (`make verify`,
  `make api-test` (originally a Cucumber `e2e` target), `make security`, `make perf`), driven by
  per-module verify and verify-changed scripts; the reviewer re-ran `make verify`.
- Since 2026-11-13 (D18) the gate is `./gradlew clean build` from the repo root: every module's
  tests and jar plus the repo checks. The stack checks are scripts run directly
  (`scripts/api-test.sh`, `scripts/security.sh`, `scripts/suite.sh`); the reviewer re-runs
  `./gradlew clean build`.

## Consequences

- One module or all: `./gradlew :mw-x:build` or `./gradlew build`.
- `./gradlew :scriptTests` tests the scripts themselves, including the commands, paths and knobs
  these docs name (`scripts/test/test_repo_docs.sh`).

## Where in the repo

- `build.gradle.kts` (root: the repo checks)
- `scripts/test/run.sh`, `scripts/test/test_repo_docs.sh`
- `.github/pull_request_template.md`
