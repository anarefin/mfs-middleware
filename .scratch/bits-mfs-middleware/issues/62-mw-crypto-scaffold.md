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

**Status:** open

- [ ] `./gradlew :mw-crypto:build` green with Docker
- [ ] `./gradlew :checkIsolation :checkFieldInjection :contractsLint :composeCheck` green
- [ ] On the running stack, `GET /internal/v1/keys` lists a key of every purpose after the first start
