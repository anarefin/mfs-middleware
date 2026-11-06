# M7: MVP platform components

Status: Accepted, 2026-11-03.

## Context

The MVP must run the same platform pieces as production, only smaller (M8).

## Decision

Keycloak, OpenBao, Valkey, Kafka (KRaft), PostgreSQL 18, APISIX, and notifications (SMS over SMPP to the SMSC simulator, email over SMTP to GreenMail).

## Consequences

- Keycloak runs in production mode on PostgreSQL even in dev.
- `make smoke` checks every platform component on the running stack.

## Where in the repo

- `deploy/docker-compose.yml`
- `scripts/smoke.sh`
