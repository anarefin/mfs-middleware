# D11: No high availability, anywhere

Status: Accepted, 2026-11-03 (spec revision 2).

## Context

HA for every component multiplies cost and operational load; the expected volumes fit one instance of each component, with recovery by restart or restore.

## Decision

One instance of every component in every environment: no clustering, replication, failover, second site or throughput replicas. Recovery is container restart and, after host or volume loss, restore from backup (spec 19.3).

## Consequences

- Availability target 99.5 %, RPO 5 min, RTO 4 h (TBC).
- Every service must recover by itself after a restart: outbox, inbox, saga timers and TSQ sweeps finish what was in flight. `scripts/chaos.sh` (originally `make chaos`) proves it (criterion 3).
- Backup and the restore drill are deferred for the MVP (runbook).

## Where in the repo

- `scripts/chaos.sh`
- [docs/runbook.md](../runbook.md)
- spec section 19.3
