# D4: PostgreSQL 18, only where a service needs its own state

Status: Accepted, 2026-11-03 (spec revision 1).

## Context

The core owns balances; the middleware must still keep transaction state, idempotency keys, outbox/inbox rows, audit and configuration.

## Decision

PostgreSQL 18, one cluster, one schema and one role per service. Services use Spring Data JDBC (or JPA) with their own Flyway migrations run on start; no service reads another's schema; no PostgREST.

## Consequences

- A service can move to its own database without code change.
- Cross-service reads go through APIs or events (for example the internal transaction query API), never SQL.
- The e2e and chaos scripts read databases only through `docker compose exec` with read-only queries.

## Where in the repo

- `deploy/postgres/initdb` (roles and schemas)
- `services/mw-orchestrator/src/main/resources/db` (Flyway migrations)
- spec section 16
