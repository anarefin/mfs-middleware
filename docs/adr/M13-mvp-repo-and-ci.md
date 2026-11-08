# M13: GitHub repo, no CI/CD: the Makefile is the pipeline

Status: Accepted, 2026-11-03. Gate cadence revised on 2026-11-05: `make verify-changed` at milestone gates, one full `make verify` at the end of M6.

## Context

CI/CD tooling is chosen separately (D10), but every change still needs a repeatable quality gate.

## Decision

The repo is on Bits's GitHub. Tests run locally through Makefile targets (`make verify`, `make api-test` (originally a Cucumber `e2e` target), `make security`, `make perf`); the pull request carries the test report and the reviewer re-runs `make verify` before merging.

## Consequences

- Every target works for all modules or one (`M=`).
- `make test-scripts` tests the Makefile and scripts themselves, including the commands these docs name.

## Where in the repo

- `Makefile`
- `scripts/verify.sh`, `scripts/verify-changed.sh`
- `.github/pull_request_template.md`
