# D9: Keycloak, OpenBao and Resilience4j

Status: Accepted, 2026-11-03 (spec revision 1). OpenBao chosen over HashiCorp Vault for the MVP.
Amended 2026-11-19 by D30: HashiCorp Vault replaces OpenBao (ticket 91).

## Context

Identity, secrets and resilience are cross-cutting and must not be invented per service.

## Decision

Keycloak for IAM/SSO (realms `customers`, `portals`, `ops`, `services`, `partners`; MFA for `ops`; realm list amended 2026-11-14, below), OpenBao for secrets (KV, per-service AppRole, config binding at start), Resilience4j for timeouts, retries and circuit breakers (60 s time window, minimum 50 calls).

## Consequences

- No secret in code, config or tests: dev credentials are fixed, obviously fake values committed in `deploy/dev.env.defaults` (copied to the gitignored `deploy/.env`; amendment 2026-11-13) and written into OpenBao by the dev provisioning one-shot `provision` (`deploy/provision`; ADR D16), which gives every service its own secret, AppRole and database role.
- The OpenBao root token is kept in its data volume in dev only (known gap, production unseal deferred).
- Amendment (2026-11-14, ticket 73, D21, spec 18.2): the realm list is now `ops`, `portals`, `services`, `partners`. The `customers` realm, its APISIX token route `auth-customers-token` (listed in `deploy/apisix/retired.txt`) and its test password `KC_CUSTOMERS_TEST_PASSWORD` are removed: wallet users (customers, agents, merchants) log in at `mw-auth` (MSISDN + PIN on a bound device, D21), and mw-seeder lists the app users in its manifest under the pseudo realm `mw-auth` (no Keycloak user). `portals` demands TOTP like `ops` (browser flow `browser-mfa`, password form then OTP form, both REQUIRED; TOTP HmacSHA256, 6 digits, 30 s); its test users have the pre-set fake secret `KC_PORTALS_TEST_TOTP_SECRET` (`scripts/totp.sh` prints their codes). `partners`: the client `partner-sim` authenticates with `private_key_jwt` (RS256 client assertion, no client secret; `KC_PARTNER_SIM_SECRET` is gone): the `provision` one-shot generates its RSA key pair and self-signed certificate once into OpenBao `secret/mw-simulators` (`sim.partner-sim.private-key`, for the simulator) and registers the certificate on the client on every run (Keycloak admin REST; provision now waits for a healthy Keycloak). Every realm: brute-force detection (temporary lockout only: `permanentLockout` off, 5 failures, wait increment 60 s up to 15 min), password policy `length(12) and notUsername and passwordHistory(5)` (the dev passwords meet it), and reviewed token lifetimes: access tokens 5 min; SSO session idle 15 min / max 8 h for `ops`, 30 min / 8 h for `portals`, 30 min / 10 h for `services` and `partners` (client credentials, no browser session); offline sessions capped at 24 h. A stack initialised before needs `deploy/reset.sh` (Keycloak keeps an imported realm in PostgreSQL).
- Amendment (testing guide): the logins a tester types on the dev/test stack are fixed, well-known fake values, so `docs/testing-guide.html` can print them at each step: the ops and portals test passwords (customers until ticket 73), the Keycloak `kc-admin` password, the ops TOTP secret, the test-route `apikey` (now in `deploy/dev.env.defaults`) and an OpenBao token `dev-root-token` created on the first start of a fresh volume (`deploy/vault/dev-unseal.sh`). They live only in the gitignored `deploy/.env` / the dev volume and are never used outside the dev stack; real environments inject all of them (spec 18.4). An `.env` from before keeps its random values (the volumes were initialised with them) until a reset (originally `make reset`, since 2026-11-13 `deploy/reset.sh`), which rewrites the fixed ones.
- Amendment (2026-11-13, BitsPay deploy alignment): every dev value, not only the tester logins, is now a fixed, obviously fake value committed in `deploy/dev.env.defaults` (other credentials are low-entropy `dev-...` strings; also the USSD source CIDRs and the short test timers). `cd deploy && cp dev.env.defaults .env` creates the gitignored `deploy/.env` (`scripts/lib/common.sh` `ensure_env` does it in the scripts that start the stack, and `deploy/reset.sh` too); the generator script is gone. This supersedes "every other dev credential stays random" above. The values are valid only on the dev stack; real environments still inject every secret (spec 18.4). The secret scan allow-lists exactly that file (`.gitleaks.toml`; `docs/security-findings.md`). A volume initialised with older random values needs `deploy/reset.sh`.

## Where in the repo

- `deploy/keycloak/realms`
- `deploy/vault`
- `deploy/dev.env.defaults`, `.gitleaks.toml`
- spec sections 18.2, 18.4, 19.2
