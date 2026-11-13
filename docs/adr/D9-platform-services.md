# D9: Keycloak, OpenBao and Resilience4j

Status: Accepted, 2026-11-03 (spec revision 1). OpenBao chosen over HashiCorp Vault for the MVP.

## Context

Identity, secrets and resilience are cross-cutting and must not be invented per service.

## Decision

Keycloak for IAM/SSO (realms `customers`, `portals`, `ops`, `services`, `partners`; MFA for `ops`), OpenBao for secrets (KV, per-service AppRole, config binding at start), Resilience4j for timeouts, retries and circuit breakers (60 s time window, minimum 50 calls).

## Consequences

- No secret in code, config or tests: dev credentials are fixed, obviously fake values committed in `deploy/dev.env.defaults` (copied to the gitignored `deploy/.env`; amendment 2026-11-13) and written into OpenBao by the dev provisioning one-shot `provision` (`deploy/provision`; ADR D16), which gives every service its own secret, AppRole and database role.
- The OpenBao root token is kept in its data volume in dev only (known gap, production unseal deferred).
- Amendment (testing guide): the logins a tester types on the dev/test stack are fixed, well-known fake values, so `docs/testing-guide.html` can print them at each step: the ops, customers and portals test passwords, the Keycloak `kc-admin` password, the ops TOTP secret, the test-route `apikey` (now in `deploy/dev.env.defaults`) and an OpenBao token `dev-root-token` created on the first start of a fresh volume (`deploy/openbao/dev-unseal.sh`). They live only in the gitignored `deploy/.env` / the dev volume and are never used outside the dev stack; real environments inject all of them (spec 18.4). An `.env` from before keeps its random values (the volumes were initialised with them) until a reset (originally `make reset`, since 2026-11-13 `deploy/reset.sh`), which rewrites the fixed ones.
- Amendment (2026-11-13, BitsPay deploy alignment): every dev value, not only the tester logins, is now a fixed, obviously fake value committed in `deploy/dev.env.defaults` (other credentials are low-entropy `dev-...` strings; also the USSD source CIDRs and the short test timers). `cd deploy && cp dev.env.defaults .env` creates the gitignored `deploy/.env` (`scripts/lib/common.sh` `ensure_env` does it in the scripts that start the stack, and `deploy/reset.sh` too); the generator script is gone. This supersedes "every other dev credential stays random" above. The values are valid only on the dev stack; real environments still inject every secret (spec 18.4). The secret scan allow-lists exactly that file (`.gitleaks.toml`; `docs/security-findings.md`). A volume initialised with older random values needs `deploy/reset.sh`.

## Where in the repo

- `deploy/keycloak/realms`
- `deploy/openbao`
- `deploy/dev.env.defaults`, `.gitleaks.toml`
- spec sections 18.2, 18.4, 19.2
