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
  `HMAC_<use>` (HMAC-SHA256: partner signatures, webhooks; ticket 63), `KEK_<purpose>` (AES-256),
  `XMLDSIG` (RSA 3072). Default keyId = purpose in lower case with hyphens.
- Rotation (`service/KeyService.rotate`) adds a version: ACTIVE -> VERIFY_ONLY, an older VERIFY_ONLY ->
  RETIRED (verification accepts the current and the previous version only). Retired HSM objects are not
  destroyed here (`CryptoProvider.destroy` exists for the ceremony, ticket 75).
- Dev bootstrap (`scheduler/KeyBootstrap` -> `service/KeyBootstrapService`, ShedLock `key-bootstrap`):
  with `mw.crypto.bootstrap-keys=true` (compose sets `MW_CRYPTO_BOOTSTRAP_KEYS=true`; default false) it
  creates the missing key of every `mw.crypto.bootstrap-purposes` entry on start and every
  `bootstrap-period` (30 s) after, idempotently; an HSM that is not up yet defers it to the next run.
- Errors: `MW-VAL-010` unknown keyId (404), `MW-VAL-011` unknown purpose (400), `MW-VAL-012` keyId exists
  (409), `MW-VAL-013` symmetric key has no public key (400), `MW-VAL-014` PIN block does not decrypt or
  holds no 5-digit PIN (400), `MW-VAL-015` key of another purpose or a version not usable for the
  operation (400), `MW-VAL-016` envelope fails its integrity check (400), `MW-VAL-001` bad body (shape,
  base64, length), `MW-SYS-010` HSM unavailable (503, retryable, fail closed; `exception/CryptoErrors`),
  `MW-AUTH-001/002` internal JWT. A wrong PIN is `{match: false}`, not an error.
- Metrics and alert: `crypto.op.duration{op,provider,outcome}` (timer around every provider call),
  `crypto.hsm.up{provider}` (gauge from `scheduler/HsmProbeJob` every `hsm-probe-period`, 10 s; a real
  `C_GenerateRandom` round trip). The alert is the ERROR log event `crypto.hsm.down` on the change to down
  (INFO `crypto.hsm.up` when back; runbook "Health and alerts"). Readiness does not depend on the HSM.

## Provider SPI and PKCS#11

- `client/CryptoProvider` (name, `capabilities()` from `client/Capability`, `generateKey(label,
  KeyAlgorithm)`, `publicKey`, `destroy`, `probe`; ticket 63: `decryptOaep`, `hmac` (a batch in one
  session), `sign(label, SignatureAlgorithm)`, `wrapKey` / `unwrapKey`). The key's row names its
  provider; `KeyService` and `service/HsmKeys` look providers up by name and check the purpose.
- PKCS#11 operations over the p11-kit RPC (what works against SoftHSM, `client/OperationsIT`): the
  PIN block is a raw RSA decrypt (`RSA/ECB/NoPadding`, `CKM_RSA_X_509`) in the HSM and OAEP decoding
  (SHA-256, MGF1-SHA-256) in `client/OaepDecoder` (BouncyCastle `OAEPEncoding`; SunPKCS11 has no
  OAEP cipher); a block of the wrong length or not below the modulus never reaches the HSM. HMAC
  `HmacSHA256`; signatures `SHA256withRSA`, `SHA256withRSASSA-PSS`, `SHA256withECDSA` (DER, turned into
  `R ‖ S` for JWS). The KEK wrap is AES-256-CTR built on `AES/ECB/NoPadding` of the counter blocks in
  the HSM (byte for byte JCA `AES/CTR`): over the RPC `CKM_AES_GCM` fails with `CKR_DEVICE_ERROR` (and
  drops the connection) and a CBC decrypt with `CKR_FUNCTION_NOT_SUPPORTED`. The wrapped key has no
  tag of its own; the envelope's GCM tag catches a changed one.
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

## Operations (ticket 63, spec 18.7, 18.8)

- Key references (`service/HsmKeys`): a keyId means its ACTIVE version, a kid `<keyId>.v<n>` that
  version. New values (sign, derive, tokenise, encrypt) take ACTIVE only; checks of old values (verify,
  decrypt) also VERIFY_ONLY; RETIRED never. By purpose: the key under the purpose's default keyId, else
  the first ACTIVE one. Every provider call is timed as `crypto.op.duration{op}`.
- PIN (`service/PinService`, caller mw-auth): `POST /internal/v1/pin/verify` `{walletId,
  encryptedPin{keyId, ciphertext}, storedHmac, hmacKeyId(kid)}` -> `{match, rehash?}`; `POST
  /internal/v1/pin/derive` `{walletId, encryptedPin, policyInput?, hmacKeyId?(keyId)}` -> `{weak:
  false, hmac, hmacKeyId}` or `{weak: true, rule}`. HMAC-SHA256 under `PIN_HMAC` over `walletId ‖ 0x00
  ‖ PIN` (UTF-8 walletId, no NUL allowed in it), `MessageDigest.isEqual`. The PIN must be exactly 5
  ASCII digits. The clear PIN and every buffer holding it (raw RSA block, HMAC message) are `byte[]`
  zeroed in `finally`. `rehash` = the value under the ACTIVE version on a match under a VERIFY_ONLY
  one. Weak-PIN rules (`service/WeakPinPolicy`, inputs from mw-auth): `REPEATED_DIGIT`, `SEQUENCE`
  (ascending/descending, wrapping 9->0), `MSISDN_DIGITS` (any 5 consecutive), `BIRTH_YEAR` (contained),
  `LAST_PIN` (HMAC of the new PIN under `lastHmacKeyId` equals `lastHmac`).
- JWS (`service/JwsService`, `HsmJwsSigner`: a Nimbus `JWSSigner` that calls the provider): `POST
  /internal/v1/jws/sign` `{keyId | purpose, alg?, header?, payload}` -> `{jws, kid, alg}`; RSA keys RS256
  (default) or PS256, EC keys ES256; header `kid` = key version; `alg`, `kid`, `jku`, `jwk`, `x5*`,
  `crit`, `b64`, `enc`, `zip` are refused in `header`; payload at most 64 KiB. `GET
  /internal/v1/jwks/{purpose}`: the JWK Set of every ACTIVE and VERIFY_ONLY version of the purpose's keys.
- HMAC (`service/HmacService`): `POST /internal/v1/hmac/sign` / `verify`, `HMAC_<use>` keys only (an
  HMAC oracle under `PIN_HMAC` or `TOKENISE_*` would let a caller brute-force PINs or reverse tokens).
- Tokens (`service/TokenService`): `POST /internal/v1/tokens` `{domain: MSISDN|NID|ACCOUNT, values[]
  (1..500, each <= 64 chars)}` -> `{domain, kid, tokens[]}`, token `<DOMAIN>_` + base32 (RFC 4648, no
  padding) of HMAC-SHA256 under `TOKENISE_<domain>` over the value's UTF-8 bytes as given (callers
  normalise); one HSM session per batch. A rotation gives new tokens.
- Envelope (`service/EnvelopeService`): `POST /internal/v1/envelope/encrypt` `{kekId | purpose
  (KEK_<x>), plaintext (<= 1 MiB), aad?}` -> `{kekId(kid), wrappedKey, iv, ciphertext, aad}`; fresh
  AES-256 data key, AES-256-GCM (12-byte nonce, 128-bit tag) in the JVM, data key wrapped in the HSM and
  zeroed; `/decrypt` takes that object back -> `{plaintext}`.
- Masking and logging: no payload logging here, ever. Every secret request/response field is
  `@Sensitive(SECRET)` (walletId and MSISDNs `MSISDN`) and every operation DTO's `toString()` is
  `MaskingPolicy.describe`; services log only event, kid, outcome. Bean Validation messages never echo
  values. Tests: `dto/OperationDtoMaskingTest` and the log check of `client/OperationsIT`.

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
- `client/OperationsIT` (ticket 63): every operation against the token with keys of its own keyIds
  (`it-pin-transport`, `it-pin-hmac`, `hmac-it`, `kek-it`; JWS and tokens use the bootstrap keys, which
  it never rotates): PIN derive/verify/weak/bad blocks, rotation of transport and PIN HMAC keys with the
  re-hash, RS256/PS256/ES256 against the JWKS, HMAC, tokens, envelope tamper and KEK rotation, then no
  request or answer value in the container log.
- Everything above the SPI runs without an HSM: `client/FakeCryptoProvider` (test, JDK keys in memory,
  named `pkcs11`, `down(true)` fails closed, `putHmacKey` for known answers, `lastDecrypted()` for the
  zeroing checks) replaces `Pkcs11Provider` with
  `mw.crypto.pkcs11.enabled=false` (`service/KeyServiceIT`: real PostgreSQL); plain unit tests for
  `KeyPurpose`, `KeyMapper`, `Pkcs11Provider` without a library (fails closed), `HsmHealthService`;
  `@WebMvcTest` `controller/KeyControllerTest` with `SecurityConfig` and `InternalTokens`. Ticket 63:
  `service/PinServiceTest` (HMAC known answer computed outside Java, match, re-hash, weak PIN, bad blocks,
  the PIN buffer all zero after every call), `WeakPinPolicyTest`, `JwsServiceTest`,
  `OtherOperationsTest` (HMAC, token known answer, envelope), `client/OaepDecoderTest` (OAEP decoding,
  CTR counter blocks = JCA CTR), `controller/OperationControllersTest`, `contracts/OperationContractsTest`.
- `scripts/test/test_softhsm.sh` fails when `src/test/resources/softhsm/` differs from `deploy/softhsm`:
  change both together (copy the folder again).

## Contracts owned

- `contracts/api/mw-crypto/crypto-internal.v1.openapi.yaml`: `GET /internal/v1/keys`, `POST
  /internal/v1/keys` (dev and bootstrap only until the ceremony), `POST /internal/v1/keys/{keyId}/rotate`,
  `GET /internal/v1/keys/{keyId}/public` (PEM and JWK per live version plus a JWK Set); ticket 63 the
  operations `/internal/v1/pin/{verify,derive}`, `/jws/sign`, `/jwks/{purpose}`, `/hmac/{sign,verify}`,
  `/tokens`, `/envelope/{encrypt,decrypt}`. Checked by `contracts/ProducedContractsTest` and
  `contracts/OperationContractsTest`.

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
