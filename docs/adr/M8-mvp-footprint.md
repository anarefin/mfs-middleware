# M8: Single host, Docker Compose, no HA

Status: Accepted, 2026-11-03.

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
