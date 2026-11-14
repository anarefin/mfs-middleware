# mw-crypto

Service module (spec 4.2, 4.3), a subproject of the root build. Package `mfs.mw.crypto`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- The HSM gateway (spec 18.8, ADR D23): the only process that talks to an HSM. Every other service asks it
  by `keyId` over `/internal/v1/**` and never holds key material. Built by tickets 62 (this scaffold, the
  provider SPI, PKCS#11 keys), 63 (operations), 64 (payShield provider), 65 (XML-DSig, HTTP signatures),
  66 (mTLS), 75 (key ceremony in the admin console).
- Key metadata in schema `crypto` (`key`: keyId, version, purpose, algorithm, provider, HSM label, state
  ACTIVE / VERIFY_ONLY / RETIRED, created/rotated at; nothing secret; `V4__key.sql`). Material stays in
  the HSM under the label `<keyId>.v<version>`, which is also the JWS `kid`.
- Purposes (`service/KeyPurpose`): `PIN_TRANSPORT` (RSA 3072, JWK `RSA-OAEP-256`/`enc`), `PIN_HMAC`
  (HMAC-SHA256), `JWT_SIGN` (RSA 3072, RS256: D21 amendment 2026-11-14, APISIX verifies RSA JWKS only),
  `ASSERTION_SIGN` and `AUDIT_ANCHOR_SIGN` (EC P-256, ES256), `TOKENISE_<domain>` (HMAC-SHA256),
  `KEK_<purpose>` (AES-256), `XMLDSIG` (RSA 3072). Default keyId = purpose in lower case with hyphens.
- Rotation (`service/KeyService.rotate`) adds a version: ACTIVE -> VERIFY_ONLY, an older VERIFY_ONLY ->
  RETIRED (verification accepts the current and the previous version only). Retired HSM objects are not
  destroyed here (`CryptoProvider.destroy` exists for the ceremony, ticket 75).
- Dev bootstrap (`scheduler/KeyBootstrap` -> `service/KeyBootstrapService`, ShedLock `key-bootstrap`):
  with `mw.crypto.bootstrap-keys=true` (compose sets `MW_CRYPTO_BOOTSTRAP_KEYS=true`; default false) it
  creates the missing key of every `mw.crypto.bootstrap-purposes` entry on start and every
  `bootstrap-period` (30 s) after, idempotently; an HSM that is not up yet defers it to the next run.
- Errors: `MW-VAL-010` unknown keyId (404), `MW-VAL-011` unknown purpose (400), `MW-VAL-012` keyId exists
  (409), `MW-VAL-013` symmetric key has no public key (400), `MW-SYS-010` HSM unavailable (503, retryable,
  fail closed; `exception/CryptoErrors`), `MW-AUTH-001/002` internal JWT.
- Metrics and alert: `crypto.op.duration{op,provider,outcome}` (timer around every provider call),
  `crypto.hsm.up{provider}` (gauge from `scheduler/HsmProbeJob` every `hsm-probe-period`, 10 s; a real
  `C_GenerateRandom` round trip). The alert is the ERROR log event `crypto.hsm.down` on the change to down
  (INFO `crypto.hsm.up` when back; runbook "Health and alerts"). Readiness does not depend on the HSM.

## Provider SPI and PKCS#11

- `client/CryptoProvider` (name, `capabilities()` from `client/Capability`, `generateKey(label,
  KeyAlgorithm)`, `publicKey`, `destroy`, `probe`). The key's row names its provider; `KeyService` looks
  providers up by name and checks the purpose's capability. Operations are added by tickets 63-65.
- `client/Pkcs11Provider`: JDK SunPKCS11 against `mw.crypto.pkcs11.library` (`/usr/lib/libp11-kit.so.0`,
  the p11-kit proxy) and `slot-list-index` 0. The image's only p11-kit module is
  `remote: |socat - TCP:softhsm:5657,...` (the softhsm `mw-crypto` token, `deploy/softhsm/README.md`).
  Logs in with `mw.crypto.pkcs11.user-pin` (OpenBao `secret/mw-crypto`, `provision_mw_crypto`); the PIN is
  never logged and its char copy is zeroed after the login. Keys are token objects, sensitive and never
  extractable (`attributes` lines of the provider config). A key pair is stored with a self-signed
  placeholder certificate signed inside the HSM (BouncyCastle `bcpkix`): SunPKCS11 keeps a private key
  only next to a certificate.
- Session pool: a semaphore of `max-sessions` (8); each call runs on an `hsm-*` platform thread with
  `call-timeout` (5 s) and keeps its permit until the native call returns, so a hung HSM soon fails every
  request at once (`acquire-timeout` 2 s). Connect is lazy (first call or probe).
- Reconnect: SunPKCS11 initialises a library once per JVM; after an HSM outage (or a start while the
  token was not reachable: the proxy then shows 0 slots) only `C_Finalize` + `C_Initialize` reach the
  token again. `Pkcs11Provider.resetModule` does that through the JDK-internal
  `sun.security.pkcs11.wrapper.PKCS11`, opened by `Add-Opens` in the boot jar manifest
  (`build.gradle.kts`). A JDK upgrade must keep `Pkcs11ProviderIT` (step 5) green.
- Image: Alpine JRE (`eclipse-temurin:25.0.5_8-jre-alpine`) with `p11-kit=0.26.2-r0`, `socat`, `bash`
  (healthcheck). The p11-kit client must match the softhsm server's version: Ubuntu noble's 0.25.3 logs in
  but fails every key generation with `CKR_DEVICE_MEMORY` (`scripts/test/test_softhsm.sh` checks it).

## HSM integration tests (decision for tickets 62-65)

No PKCS#11 tooling on the host (user decision 2026-11-14): PKCS#11 never runs in the host test JVM.

- `src/test/java/mfs/mw/crypto/HsmStack.java` starts, once per test JVM, on a private Testcontainers
  network: the SoftHSM container built with `ImageFromDockerfile` from `src/test/resources/softhsm/`
  (a copy of `deploy/softhsm`, alias `softhsm`, fake PINs, `Wait.forHealthcheck()`), a PostgreSQL
  (alias `postgres`) and this service's own image built from the module `Dockerfile` and
  `build/libs/mw-crypto.jar` (the `test` task depends on `bootJar`; jar and Dockerfile are test inputs).
  No OpenBao: the user PIN comes in as `MW_CRYPTO_PKCS11_USER_PIN`; Kafka points nowhere (relay off).
  The container validates internal JWTs against `security/InternalTokens`' JWKS on the host through
  `Testcontainers.exposeHostPorts` (`host.testcontainers.internal`).
- Tests drive it over HTTP: `HsmStack.get/post(path, token)` with `InternalTokens.forClient(...)`, and
  `HsmStack.stopHsm()` / `startHsm()` for outage tests (the token volume survives). Container logs go to
  the test log with the prefix `mw-crypto`.
- `client/Pkcs11ProviderIT` (ordered): bootstrap fills the token with a key of every purpose, public keys
  (RSA 3072 RS256, EC P-256) come out of the token, generate + rotate, `crypto_hsm_up 1`, then HSM stopped
  -> `crypto_hsm_up 0` and 503 `MW-SYS-010`, HSM started -> recovered. Tickets 63-65 add their operation
  ITs on the same `HsmStack` (start it in `@BeforeAll`; keys from the bootstrap or created per test with
  their own keyIds, never relying on test order across classes).
- Everything above the SPI runs without an HSM: `client/FakeCryptoProvider` (test, JDK keys in memory,
  named `pkcs11`, `down(true)` fails closed) replaces `Pkcs11Provider` with
  `mw.crypto.pkcs11.enabled=false` (`service/KeyServiceIT`: real PostgreSQL); plain unit tests for
  `KeyPurpose`, `KeyMapper`, `Pkcs11Provider` without a library (fails closed), `HsmHealthService`;
  `@WebMvcTest` `controller/KeyControllerTest` with `SecurityConfig` and `InternalTokens`.
- `scripts/test/test_softhsm.sh` fails when `src/test/resources/softhsm/` differs from `deploy/softhsm`:
  change both together (copy the folder again).

## Contracts owned

- `contracts/api/mw-crypto/crypto-internal.v1.openapi.yaml`: `GET /internal/v1/keys`, `POST
  /internal/v1/keys` (dev and bootstrap only until the ceremony), `POST /internal/v1/keys/{keyId}/rotate`,
  `GET /internal/v1/keys/{keyId}/public` (PEM and JWK per live version plus a JWK Set). Checked by
  `contracts/ProducedContractsTest`.

## Contracts used

- None. Callers: `mw-admin-api` (Keycloak realm `services` client, audience mapper `aud-mw-crypto` added
  here), `mw-auth` (ticket 67) and `mw-audit-dw` (ticket 74) add their own clients; all three are on
  `mw.internal-auth.allowed-clients`.
- `contracts.yml` is the machine-readable list, checked by `./gradlew :contractsLint`.

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- `filter/CorrelationIdFilter`, `filter/SecurityHeadersFilter`, `exception/` (`MwException`,
  `ErrorCategory`, `ErrorCodes`, `GlobalExceptionHandler`), `util/MaskingPolicy` + `@Sensitive`,
  `logging/JsonLogFormatter`, `config/MwServiceProperties`, the outbox / inbox / retry topic chassis
  (unused so far: mw-crypto produces and consumes no event).
- Internal JWT: `config/InternalAuthProperties`, `config/SecurityConfig`, `security/InternalCallerPolicy`
  (copied from mw-audit-dw without the org scoping).

## Compose and provisioning

- `deploy/docker-compose.yml` block `mw-crypto` (profiles `minimal` and `core`, port 8094
  `MW_PORT_CRYPTO`, waits for a healthy `softhsm`, limit `CRYPTO_MEM_LIMIT` 320M, heap `CRYPTO_XMX` 112m;
  measured 275 MiB after start and bootstrap). `deploy/provision/services.sh` `provision_mw_crypto`: DB
  role `crypto_rw`, schema `crypto`, `secret/mw-crypto` (`spring.datasource.password`,
  `mw.crypto.pkcs11.user-pin` from `SOFTHSM_MWCRYPTO_USER_PIN`), AppRole volume `crypto-approle`.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew :mw-crypto:test` | every test in one JVM, after `bootJar` (the HSM ITs build the image from it); `*IT` skipped without Docker |
| `./gradlew :mw-crypto:test --tests '*Pkcs11ProviderIT'` | the PKCS#11 provider against SoftHSM in containers (about 1.5 min with image builds) |
| `./gradlew :mw-crypto:build` | the tests plus the boot jar `build/libs/mw-crypto.jar` |
| `./gradlew :mw-crypto:bootJar && docker build -t mfs-mw/mw-crypto:local services/mw-crypto` | image, as spec 4.3 rule 1 |
