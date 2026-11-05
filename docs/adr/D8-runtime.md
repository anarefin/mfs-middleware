# D8: Docker images for every component

Status: Accepted, 2026-11-03 (spec revision 1).

## Context

The middleware must run the same way on a developer laptop, the MVP host and production, without host-installed services.

## Decision

Every component, including platform tools, runs from a Docker image: one image per service (non-root, read-only root filesystem where possible), one init image per service for its one-shot setup, pinned third-party images.

## Consequences

- A host needs only Docker (plus JDK 25 to build the jars in the MVP).
- Image scanning (`make security`) covers every image the stack runs.

## Where in the repo

- `services/mw-orchestrator/Dockerfile`
- `scripts/test/test_dockerfiles.sh` (hardening checks)
- spec section 20.1
