# 67: mw-auth scaffold, wallet projection, mw-crypto client

**What to build:** The new service `mw-auth` (`scripts/new-service.sh mw-auth`, package `mfs.mw.auth`; spec 18.7, D20–D22) with its data model, the wallet projection from the core and the mTLS client to `mw-crypto`. Behaviour comes in 68–71.

- **Schema `auth`** (Flyway, own role):
  - `wallet` (walletId, MSISDN as a keyed hash plus the value encrypted through mw-crypto envelope encryption, type, status, hasPin, birthYear?, version);
  - `credential` (walletId, pin_hmac, hmac_key_id, policy_version, set_at, last_hmacs for reuse checks);
  - `attempt` (walletId, window, count);
  - `lock` (walletId, level, until, reason, actor);
  - `device` (deviceId, walletId, public key, bound_at, unbound_at);
  - `refresh_token` (hash, family, walletId, deviceId, expires, rotated_at, revoked);
  - `pin_reset`, `outbox`, `inbox`.
- **Wallet projection (D5):**
  - mw-core-mock emits `WalletStateChanged` on `core.wallet.event.v1` on create and every status change, plus a re-publish of every wallet on start in dev (snapshot, idempotent by version). Add the payload schema under `contracts/events/mw-core-mock/core.event.v1/` (owned by mw-core-mock), with samples.
  - mw-auth consumes it with inbox dedup and upserts `auth.wallet` when the version is newer.
  - mw-core-mock: add the event; nothing else changes here.
- **mw-crypto client:** a `client/CryptoClient` over mTLS (ticket 66's manager) with a service JWT (Keycloak `services` client `mw-auth`, the `ServiceTokenClient` pattern of mw-admin-api), Resilience4j (timeout 2 s, breaker), and fail-closed `MW-SYS-*`.
- **Internal API mTLS:** mw-auth's own `/internal/v1/**` listens on a second port with `client-auth: need` and a JWT allow-list (`mw-app-gateway`, `mw-ussd`, `mw-checkout`, `mw-admin-api`).
- **Compose and provisioning:** a compose block (`core`, `minimal`), `provision_mw_auth`, a PKI role, and a Keycloak `services` client.
- **Docs:** the module `CLAUDE.md` (responsibilities, contracts owned/used).
- **Tests:**
  - repository ITs;
  - a projection listener IT (out-of-order versions, duplicate events);
  - a CryptoClient test with WireMock over TLS;
  - contract tests for the new core event (mw-core-mock produced, mw-auth consumed).

**Blocked by:** 63, 66

**Status:** open

- [ ] `./gradlew :mw-auth:build :mw-core-mock:build` green with Docker
- [ ] `./gradlew :contractsLint :composeCheck :checkIsolation` green
- [ ] On the seeded stack, `auth.wallet` holds every seeded wallet
