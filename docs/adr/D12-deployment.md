# D12: Docker Compose for everything

Status: Accepted, 2026-11-03 (spec revision 2).

## Context

Without Kubernetes (D10, D11), the deployment must still be declarative, repeatable and owned by the team.

## Decision

Every component runs under Docker Compose: platform (Kafka, PostgreSQL, Valkey, Keycloak, OpenBao, APISIX/etcd, GreenMail), every `mw-*` service, the admin UI and the simulators. Each module has its own `compose.yml`; the environment project includes the platform file and every module file. Nothing is installed natively on the host.

## Consequences

- `make up` starts the whole MVP; `make up M=<module>` starts one module as its own project.
- The repo-root `compose.yml` is a generated flat copy for plain `docker compose up`; `make compose-check` keeps it current and the memory limits within 10 GB.

## Where in the repo

- `deploy/compose/mvp/compose.yml`
- `deploy/compose/platform/compose.yml`
- `scripts/compose-flat.sh`
- spec section 20.2
