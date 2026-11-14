# 62: mw-crypto scaffold, provider SPI and PKCS#11 key management

**What to build:** The new service `mw-crypto` (`scripts/new-service.sh mw-crypto`, package `mfs.mw.crypto`), the only HSM client (spec 18.8, D23). This ticket covers the skeleton, the SPI, the PKCS#11 provider and key management. The operations come in tickets 63–65, mTLS in ticket 66.

- **Provider SPI:** `CryptoProvider` with capability flags (`PIN_HMAC`, `JWS`, `HMAC`, `TOKENISE`, `ENVELOPE`, `XMLDSIG`, `PIN_BLOCK`, `PVV`) and `generateKey`, `publicKey`, `destroy`. The `Pkcs11Provider` uses the JDK SunPKCS11 provider configured for the softhsm token (ticket 61), with a session pool. It logs in with the user PIN from OpenBao and never logs it.
- **Keys:**
  - The `crypto` schema holds `key` (keyId, purpose, algorithm, provider, HSM label, state ACTIVE/VERIFY_ONLY/RETIRED, version, created/rotated at) and nothing secret.
  - Purposes: `PIN_TRANSPORT` (RSA 3072), `PIN_HMAC` (HMAC-SHA256), `JWT_SIGN` and `ASSERTION_SIGN` (EC P-256), `AUDIT_ANCHOR_SIGN`, `TOKENISE_<domain>`, `KEK_<purpose>` (AES-256), `XMLDSIG` (RSA 3072).
  - Rotation adds a version. The previous version stays VERIFY_ONLY until it is retired.
- **API** (`contracts/api/mw-crypto/crypto-internal.v1.openapi.yaml`, owned by mw-crypto): `GET /internal/v1/keys`, `POST /internal/v1/keys` (generate; dev/bootstrap only until ticket 75's ceremony), `POST /internal/v1/keys/{keyId}/rotate`, `GET /internal/v1/keys/{keyId}/public` (PEM and JWK).
  - Internal JWT check as in mw-audit-dw (`InternalAuthProperties`, `SecurityConfig`: iss, aud `mw-crypto`, azp allow-list).
  - A Keycloak `services` client per caller.
- **Bootstrap:** on start in dev, create the missing keys of every purpose (an idempotent `scheduler/KeyBootstrap` with ShedLock), so the stack comes up usable. In production, keys come only from the ceremony (flag `mw.crypto.bootstrap-keys`, default false).
- **Errors:** `MW-SYS-*` when the HSM is unavailable (the request fails closed), `MW-VAL-*` for an unknown keyId or purpose.
- **Metrics:** `crypto.op.duration{op,provider}`, `crypto.hsm.up`.
- **Alert:** HSM down.
- **Compose and provisioning:** a compose block (`core` and `minimal` profiles) and `provision_mw_crypto` in `deploy/provision/services.sh` (DB role, OpenBao path, AppRole).
- **Tests:**
  - unit tests for the SPI and the key service;
  - `Pkcs11ProviderIT` against a SoftHSMv2 Testcontainer built from `deploy/softhsm`, copied into the module's test resources (no path outside the module);
  - `@WebMvcTest` for the controller;
  - `ProducedContractsTest` for the OpenAPI.

**Blocked by:** 61

**Status:** done

- [x] `./gradlew :mw-crypto:build` green with Docker
- [x] `./gradlew :checkIsolation :checkFieldInjection :contractsLint :composeCheck` green
- [ ] On the running stack, `GET /internal/v1/keys` lists a key of every purpose after the first start (deferred to final integration test)

Decisions recorded (ticket 62): JWT_SIGN is RSA 3072 / RS256 (user decision; D21 amendment, spec 18.7/18.8).
HSM ITs run mw-crypto's own image next to SoftHSM on a Testcontainers network (`HsmStack`, recorded in
`services/mw-crypto/CLAUDE.md`). mw-crypto image is Alpine (p11-kit 0.26.2 must match softhsm; noble's 0.25.3
fails key generation). Reconnect after an HSM outage re-initialises the PKCS#11 module through the JDK-internal
SunPKCS11 wrapper (`Add-Opens` in the boot jar manifest). Memory: `CRYPTO_MEM_LIMIT` 320M / `CRYPTO_XMX` 112m,
`ADMIN_UI_MEM` 160M -> 64M (total 8176 / 8192). The running-stack check needs `deploy/reset.sh` (new audience
mapper `aud-mw-crypto` on the Keycloak `mw-admin-api` client is imported only into a fresh realm).
