# D14: No shared code: contracts only, cross-cutting code copied from a template

Status: Accepted, 2026-11-03 (spec revision 2).

## Context

Shared libraries couple release cycles and leave a module impossible to move to its own repo.

## Decision

No library, common, starter or test-kit module. Services share only contract files (JSON Schema, OpenAPI) in the root `contracts/` folder, which holds no code; each module reads it only in tests and build-time checks through the `mwContractsDir` property. Cross-cutting code (correlation, error envelope, masking, logging, outbox, inbox, Kafka retry) is copied from `templates/mw-service-template`, never depended on.

## Consequences

- Duplicated chassis code is intended; a template fix is rolled into each service as its own change.
- Each contract file has one owner (`contracts/OWNERS.yml`); only the owner changes it.
- Each module declares what it produces and consumes in `contracts.yml`, checked by Docker-free tests, so `make verify-changed` tests only a change's contract neighbours.

## Where in the repo

- `contracts/README.md`, `contracts/OWNERS.yml`
- `templates/mw-service-template`
- `services/mw-notify/contracts.yml` (a manifest)
- spec section 4.3
