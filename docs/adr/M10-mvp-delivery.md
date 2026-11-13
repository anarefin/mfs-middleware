# M10: Built mainly by coding agents

Status: Accepted, 2026-11-03. Gate amended 2026-11-13 (D18, D19).

## Context

The MVP is built mostly by Claude Code agents, which need small, testable and unambiguous work items.

## Decision

Work is sliced into about 30 work packages (spec "MVP build plan"), each one module, one contract to build against and tests written first. Agents work in parallel git worktrees on tickets in `.scratch/`; conventions live in the root and module `CLAUDE.md` files.

## Consequences

- Contracts come first so parallel agents never guess each other's interfaces.
- Per-change testing stays cheap. Originally the fast gate (ticket 53) ran the `test` and `integrationTest` tasks per touched module and `make verify-changed` at milestone gates. Since 2026-11-13 (D18, D19) every change runs `./gradlew clean build`: the build cache restores the modules whose inputs did not change, so only the touched modules and their contract neighbours run again.

## Where in the repo

- root `CLAUDE.md`
- `templates/mw-service-template`
