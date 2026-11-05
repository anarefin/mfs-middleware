# M10: Built mainly by coding agents

Status: Accepted, 2026-11-03.

## Context

The MVP is built mostly by Claude Code agents, which need small, testable and unambiguous work items.

## Decision

Work is sliced into about 30 work packages (spec "MVP build plan"), each one module, one contract to build against and tests written first. Agents work in parallel git worktrees on tickets in `.scratch/`; conventions live in the root and module `CLAUDE.md` files.

## Consequences

- Contracts come first so parallel agents never guess each other's interfaces.
- The fast gate (ticket 53) keeps per-change testing cheap: `./gradlew test integrationTest` per touched module, `make verify-changed` at milestone gates.

## Where in the repo

- root `CLAUDE.md`
- `templates/mw-service-template`
