# M12: Human review of every pull request, milestone demos

Status: Accepted, 2026-11-03.

## Context

Agent-written code needs a human owner who understands and accepts it.

## Decision

An upay engineer reviews and approves every pull request, including agent-written ones. Each milestone ends with a demo that a human runs from the demo script.

## Consequences

- Small diffs (target 1,500 changed lines per work package).
- The demo for each milestone is in [docs/demo.md](../demo.md).

## Where in the repo

- `.github/pull_request_template.md`
- [docs/demo.md](../demo.md)
