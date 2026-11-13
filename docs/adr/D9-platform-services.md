# D9: Keycloak, OpenBao and Resilience4j

Status: Accepted, 2026-11-03 (spec revision 1). OpenBao chosen over HashiCorp Vault for the MVP.

## Context

Identity, secrets and resilience are cross-cutting and must not be invented per service.

## Decision

Keycloak for IAM/SSO (realms `customers`, `portals`, `ops`, `services`, `partners`; MFA for `ops`), OpenBao for secrets (KV, per-service AppRole, config binding at start), Resilience4j for timeouts, retries and circuit breakers (60 s time window, minimum 50 calls).

## Consequences

- No secret in code, config or tests: dev credentials are generated into a gitignored `.env` and written into OpenBao by the dev provisioning one-shot `provision` (`deploy/provision`; ADR D16), which gives every service its own secret, AppRole and database role.
- The OpenBao root token is kept in its data volume in dev only (known gap, production unseal deferred).
- Amendment (testing guide): the logins a tester types on the dev/test stack are fixed, well-known fake values, so `docs/testing-guide.html` can print them at each step: the ops, customers and portals test passwords, the Keycloak `kc-admin` password, the ops TOTP secret, the test-route `apikey` (`scripts/gen-dev-env.sh` `FIXED`) and an OpenBao token `dev-root-token` created on the first start of a fresh volume (`deploy/openbao/dev-unseal.sh`). They live only in the gitignored `deploy/.env` / the dev volume and are never used outside the dev stack; every other dev credential stays random, and real environments inject all of them (spec 18.4). An `.env` from before keeps its random values (the volumes were initialised with them) until a reset (originally `make reset`, since 2026-11-13 `deploy/reset.sh`), which rewrites the fixed ones.

## Where in the repo

- `deploy/keycloak/realms`
- `deploy/openbao`
- `scripts/gen-dev-env.sh`
- spec sections 18.2, 18.4, 19.2
