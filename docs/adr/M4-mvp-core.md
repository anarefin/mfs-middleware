# M4: A core mock with a real ledger

Status: Accepted, 2026-11-03.

## Context

The real core is not available to the MVP, and a stub that always says yes would hide the hard paths (holds, insufficient funds, lost replies).

## Decision

`mw-core-mock` implements the proposed Kafka contract (spec 6.3) on a small double-entry ledger in its own schema, with seed data (1,000 customers, 50 agents, 20 merchants, 2 orgs), flat fees, limits, PIN check, lockout and fault injection (delay, drop reply, duplicate reply, reject).

## Consequences

- It is a test double: it never ships to production and is replaced by the real core without orchestrator change.
- Tests prove money is never moved twice by reading its balances (criterion 2) and `scripts/chaos.sh` (originally `make chaos`) checks its ledger invariants.

## Where in the repo

- `services/mw-core-mock/CLAUDE.md`
- `services/mw-core-mock/src/main/java/mfs/mw/core/mock/service/WalletSeeder.java`
