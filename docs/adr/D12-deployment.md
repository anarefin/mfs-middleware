# D12: Docker Compose for everything

Status: Accepted, 2026-11-03 (spec revision 2). The file layout (one `compose.yml` per module, the generated flat file) is superseded by [D16](D16-single-compose-file.md), 2026-11-06.

## Context

Without Kubernetes (D10, D11), the deployment must still be declarative, repeatable and owned by the team.

## Decision

Every component runs under Docker Compose: platform (Kafka, PostgreSQL, Valkey, Keycloak, OpenBao, APISIX/etcd, GreenMail), every `mw-*` service, the admin UI and the simulators. Nothing is installed natively on the host. Originally each module had its own `compose.yml`, included by an environment project; since D16 the whole stack is one hand-written `deploy/docker-compose.yml` with profiles.

## Consequences

- `docker compose --profile core up -d` from `deploy/` starts the whole MVP; naming one service starts it and what it depends on (D16; originally `make up` and `make up M=<module>`, until D18).
- `./gradlew :composeCheck` (originally `make compose-check`) keeps the memory limits of the long-running containers within 10 GB.

## Where in the repo

- `deploy/docker-compose.yml` (D16)
- `scripts/compose-check.sh`
- spec section 20.2
