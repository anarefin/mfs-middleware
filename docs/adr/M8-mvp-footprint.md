# M8: Single host, Docker Compose, no HA

Status: Accepted, 2026-11-03.
Amendment (2026-11-13, BitsPay deploy alignment): the dev stack also publishes host ports on
127.0.0.1 only: every `mw-*` service (8081-8093), the admin UI (3000), the test console (3001) and
the platform (PostgreSQL 5432, Valkey 6379, Kafka 9094, Keycloak 8180, OpenBao 8200, GreenMail
8025/3025), each a `MW_PORT_<NAME>` knob in `deploy/docker-compose.yml`, for local debugging. A
direct port skips every edge policy. APISIX 443 stays the edge and the only port reachable beyond
loopback; etcd and the APISIX Admin API stay unpublished. Production stays edge-only (only APISIX publishes 443, as decided below).

## Context

The MVP host is one VM; production follows the same single-instance model (D11).

## Decision

One Compose project on one Linux VM (about 16 vCPU, 64 GB RAM, 500 GB SSD, TBC). Only APISIX publishes a port (443). Functional runs use the low-resource override (about 7.5 GB of memory limits in total).

## Consequences

- A laptop with 16 GB RAM runs the full stack for demos and e2e.
- Throughput figures from the low-resource override are a sanity check only (criterion 7).

## Where in the repo

- `deploy/docker-compose.yml` (its memory limits; `./gradlew :composeCheck`)
- [docs/runbook.md](../runbook.md)
