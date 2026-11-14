# mw-auth

Service module (spec 4.2, 4.3), a subproject of the root build. Package `mfs.mw.auth`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Wallet authentication and PIN custody (spec 18.7, ADRs D20-D22): the one place a wallet user proves
  who they are. It never sees a clear PIN (the PIN ciphertext goes to mw-crypto). Built by tickets 67
  (this scaffold: data model, wallet projection, mw-crypto client, internal port), 68 (device, OTP,
  sessions moved from mw-app-gateway), 69 (login, tokens, JWKS at the edge), 70 (PIN lifecycle, lockout,
  `auth.wallet-lock.v1`), 71 (transaction authorisation assertion), 72 (channels switch over).
- Schema `auth` (role `auth_rw`, `V4__auth_model.sql`): `wallet` (projection, below), `credential`
  (PIN HMAC under the mw-crypto `PIN_HMAC` key + its kid, policy version, set at, `last_hmacs` for the
  reuse rule), `attempt` (wrong-PIN counter of the window), `lock` (`PIN` levels with `until`, null =
  permanent; `ADMIN` locks), `device` (one bound device per wallet: partial unique index), `refresh_token`
  (hash, family, device, expiry, rotation, revoked), `pin_reset` (`SELF` / `CARE`, one open per wallet),
  plus the chassis `outbox`, `inbox`, `shedlock`; `device_otp` (V5, ticket 68; V6 adds its `purpose`
  BIND/LOGIN and `refresh_token.amr`). Code so far: `wallet` (`repository/WalletRepository`), `device`
  (`repository/DeviceRepository`), `device_otp` (`repository/DeviceOtpRepository`), `credential`,
  `attempt`, `lock` (`repository/CredentialRepository`), `refresh_token` (`repository/RefreshTokenRepository`).
- Wallet projection (D5; `route/CoreWalletEventRoute` -> `messaging/CoreWalletEventListener` ->
  `service/WalletProjectionService`, `mapper/WalletEventMapper`, `dto/WalletStateChanged`): consumes
  `core.wallet.event.v1` in group `mw-auth` (retry topics + DLQ; `deploy/kafka/topics.txt`). Only
  `WalletStateChanged` is read; the topic's other events (replies to mw-orchestrator) are skipped without
  an inbox row. Every WalletStateChanged goes through the inbox (consumer `mw-auth.wallet-projection`,
  eventId) and replaces the row only with a higher `version` (`upsertIfNewer` / `updateIfNewer`), so
  duplicates, the dev snapshot and out-of-order events change nothing. A wallet seen for the first time
  gets its MSISDN (normalised to `01XXXXXXXXX`) tokenised (`msisdn_token`, keyed hash under
  `TOKENISE_MSISDN`, plus its kid; unique: the login lookup) and encrypted (`msisdn_enc`, the mw-crypto
  envelope under `KEK_COLUMN` as jsonb, AAD the walletId); the MSISDN columns are never rewritten
  afterwards (a token-key rotation needs a re-tokenise job: later). `wallet_id` is the core's id, which in
  mw-core-mock is the MSISDN itself. mw-crypto down: `MW-SYS-050` before anything is written, the
  record goes to the retry topics (fail closed). Switch: `mw.auth.consumer.enabled` (tests off).
- **WalletStateChanged envelope decision (ticket 67):** the `core.event.v1` envelope is kept as it is
  (no relaxed required fields): mw-core-mock fills the command fields of this unsolicited event with a
  fresh UUIDv7 `txnId`, `commandId` `wallet-state:<txnId>`, `coreRef` `WALLET-V<version>`, outcome
  `SUCCESS`, key = walletId (one wallet's states stay ordered on one partition). mw-orchestrator, which
  parses `txnId` as a UUID on every event of the topic, finds no transaction and drops it
  (`txn.unknown`), as it already does with `WalletUnlocked`; nothing changes there.
- mw-crypto client (`client/CryptoClient`, `client/ServiceTokenClient`, `config/AuthProperties`
  `mw.auth.crypto.*`): `https://mw-crypto:8080` over mTLS with this service's OpenBao certificate
  (`config/ServiceCertificateManager.clientSslContext()`, CN `mw-auth`) and a Keycloak realm `services`
  client-credentials JWT of client `mw-auth` (audience mapper `aud-mw-crypto`; secret
  `mw.auth.crypto.client-secret` in `secret/mw-auth`, synced from `KC_MW_AUTH_SECRET`; cached until 30 s
  before expiry, dropped and retried once on a 401). Timeout 2 s, Resilience4j breaker `mw-crypto`
  (count based, 20 calls, 4xx ignored). Fail closed: unreachable, timeout, breaker open, 5xx =
  `MW-SYS-050` (503, retryable); another refusal `MW-SYS-051` (not retryable). Operations so far:
  tokenise MSISDN, envelope encrypt / decrypt (ticket 67); PIN verify / derive, JWS sign (`JWT_SIGN`,
  RS256) and the JWK Sets of `JWT_SIGN` and `PIN_TRANSPORT` (ticket 69). mw-crypto's `mw.crypto.authz`
  already allows `mw-auth` for all of them (no mw-crypto change in 69); nothing logs a value.
- Ports (`config/PublicConnectorConfig`, `config/SecurityConfig`, `security/InternalCallerPolicy`):
  `server.port` 8443 = the internal API `/internal/v1/**`, the same server setup as mw-crypto
  (`server.ssl` on bundle `mw-service`, `client-auth: need`, trust = the `pki_int` intermediate only;
  renewals reload without a restart); 8080 (`mw.auth.public-port`) = an added plain connector for
  `/auth/v1/**` behind APISIX (tickets 68-69); 8081 = actuator, plain HTTP, never published (Dockerfile
  and compose healthchecks). `/internal/**` is its own filter chain: Keycloak JWT (signature, expiry,
  exact issuer, `aud` `mw-auth`), `azp` in `mw.internal-auth.allowed-clients` (mw-app-gateway, mw-ussd,
  mw-checkout, mw-admin-api), `azp` == client certificate CN, and never on the public port. 401
  `MW-AUTH-001`, 403 `MW-AUTH-002` with WARN `security.internal_call_denied` (reasons `PUBLIC_PORT`,
  `CLIENT_NOT_ALLOWED`, `NO_CLIENT_CERT`, `CERT_MISMATCH`). `/auth/v1/**` is open except logout and
  the first PIN, which take mw-auth's own access token (below); dev only, with
  `mw.auth.seed-api.enabled`, `/internal/v1/seed/**` without a token (chain 0, below); everything else is
  open. `mw.tls.enabled` defaults to true here (tests off).
- Device binding, the OTP and sessions (spec 9.1, 18.6, 18.7; built in mw-app-gateway by ticket 29 and
  review A5, moved here by ticket 68 with the same behaviour and codes; since ticket 69 without any bearer
  token: the wallet is named by its MSISDN; `controller/DeviceController`, `service/DeviceBindingService`,
  `service/DeviceOtpService`, `service/SignedRequestCheck`, `service/RequestSignatures`,
  `security/SignedRequests`, `repository/DeviceSessionStore`, `mapper/DeviceNotificationMapper`,
  `util/OtpCodes`, `config/DeviceBindingProperties` `mw.auth.device-binding.*`). Public API (contract
  `api/mw-auth/auth.v1.openapi.yaml`, APISIX route `auth-v1`, no rewrite): `POST /auth/v1/otp`
  (`X-Device-Id`, unsigned, `{msisdn, purpose?, locale?}`) -> 202 `{wallet (masked), otpExpiresAt,
  sendsLeft}`; purpose `BIND` (default, template `DEVICE_BIND_OTP`) or `LOGIN` (first PIN, template
  `LOGIN_OTP`, only for a wallet without a PIN, else `403 MW-AUTH-002`); an unknown MSISDN `403
  MW-AUTH-002`. The OTP goes by SMS (`mw.notify.request.v1`, priority OTP, straight to Kafka, never at rest
  in clear; Kafka down `503 MW-SYS-010`; hash only in `device_otp` with its purpose, one current OTP per
  wallet, an OTP of the other purpose `MW-AUTH-011`, 3 checks reserved atomically, `max-sends` 5 per 15
  min (dev compose `MW_AUTH_DEVICE_OTP_MAX_SENDS` 20) else `429 MW-VAL-022`). `POST /auth/v1/devices
  {msisdn, publicKey, otp, encryptedPin?}` signed with the key being registered (proof of possession),
  then the wallet's status and locks (as at login), the OTP (`MW-AUTH-010/011/012`) and, when the wallet
  has a PIN, the PIN (`MW-AUTH-040`; missing `400 MW-VAL-001`); then in one transaction: OTP spent, the
  wallet's other bound device unbound, the device id bound to this wallet (a device id bound to another
  wallet moves here), the "new device bound" SMS (`DEVICE_BOUND`, TXN) in the outbox. Afterwards the
  sessions of every device that lost its binding and of this device are closed and the wallet's (and a
  moved device's old wallet's) refresh tokens revoked; 201 `{deviceId, wallet (masked), boundAt}`, no
  session: log in next. Signed requests: timestamp within `clock-skew` 5 min (`MW-AUTH-005`), signature
  (`MW-AUTH-004`), nonce once per device (`MW-AUTH-006`). One bound device per wallet: the `auth.device`
  partial unique index. Bodies checked against the derived schemas (`derived-schemas/auth-v1-*-request.json`:
  otp, device-binding, login, refresh, set-pin; `service/BodySchema`, `filter/BodySchemaInterceptor`,
  `filter/CachedBodyFilter`, `config/BodySchemaConfig`).
- Wallet lookup and checks (`service/WalletAccess`): the canonical MSISDN tokenised by mw-crypto ->
  `auth.wallet.msisdn_token`; not `ACTIVE` at the core `403 MW-AUTH-043`; locks (`auth.lock`): `ADMIN`
  `403 MW-AUTH-043`, `PIN` with `until` in the future `423 MW-AUTH-041` (detail `retryAfter` seconds),
  without `until` `423 MW-AUTH-042`. **Has a PIN = a `credential` row** (decision of ticket 69: mw-auth
  owns PIN custody; the projection's `has_pin` is the core's view and is not used for login).
- Login and tokens (spec 18.7, D21, ticket 69; `controller/LoginController`, `service/LoginService`,
  `service/TokenService`, `service/PinService`, `config/TokenProperties` `mw.auth.tokens.*`):
  - `POST /auth/v1/sessions {msisdn, encryptedPin | otp}` signed by the bound device: wallet status and
    locks, the device bound to this wallet (else or unknown MSISDN `401 MW-AUTH-047`) and its signature,
    then the PIN through mw-crypto `/pin/verify` (wrong: `401 MW-AUTH-040`, counted in `auth.attempt`;
    a match resets the count and stores a rotated key's rehash; the lockout levels are ticket 70) or, for
    a wallet without a PIN, a `LOGIN` OTP (`amr` [otp, device]; with a PIN the OTP is `400 MW-VAL-001`;
    no PIN but a PIN sent `403 MW-AUTH-045`). Opens `sess:{walletId}:{deviceId}` with the app's idle
    timeout. 200 `{accessToken, tokenType, expiresIn, refreshToken, refreshExpiresIn, sessionId,
    idleTimeoutSeconds, pinSet}`.
  - Access JWT: RS256 by mw-crypto `JWT_SIGN` (D21 amendment; header `typ at+jwt`, `kid <keyId>.v<n>`),
    5 min (`MW_AUTH_ACCESS_TTL`); claims `iss` (`mw.auth.tokens.issuer`, `mw-auth`), `aud` `mw-app`,
    `sub` walletId, `msisdn`, `role` (wallet type: customer, agent, merchant, distributor, dso; an ORG
    wallet `403 MW-AUTH-002`), `deviceId`, `amr`, `sid` (= the refresh family), `jti`, `iat`, `exp`.
  - Refresh token: 32 random bytes base64url, only its SHA-256 (hex) stored with family, wallet, device,
    30 days and the session's `amr`. `POST /auth/v1/tokens/refresh {refreshToken}` signed by the bound
    device: rotated by one conditional update; a rotated or revoked token again revokes the family `401
    MW-AUTH-048`; another device revokes it `401 MW-AUTH-047`; unknown or expired `401 MW-AUTH-001`; the
    wallet checked again; new tokens in the same family (the MSISDN claim decrypted from `msisdn_enc`).
  - `POST /auth/v1/sessions/logout` (bearer): family revoked, session closed, 204.
  - First PIN: `POST /auth/v1/pin {encryptedPin}` (bearer of the OTP login, its family still live, wallet
    without a credential, else `403 MW-AUTH-002`): mw-crypto `/pin/derive` (weak `400 MW-AUTH-044`, the
    rule in `details`; policy inputs come with ticket 70), the HMAC stored, the OTP family revoked, new
    tokens with `amr` [pin, device].
  - Own-token verifier (`security/OwnTokenDecoder`, logout and first PIN): RS256 only, `typ at+jwt`, key
    by kid from the cached JWT_SIGN set (an unknown kid refetches it, at most every 10 s), expiry, exact
    issuer, audience, `sid`. Not `JwtValidators.createDefault*`: since Spring Security 7 they accept `typ
    JWT` only. A bearer sent to an open endpoint is ignored (bearer resolver only on the two token paths).
- Key documents (`controller/WellKnownController`, `service/JwksService`, cache
  `mw.auth.tokens.jwks-cache-ttl` 5 min): `GET /auth/v1/.well-known/jwks.json` (JWT_SIGN, public members
  only), `GET /auth/v1/.well-known/openid-configuration` (`issuer`, `jwks_uri` =
  `mw.auth.tokens.jwks-uri`, the in-stack URL; only APISIX reads it, the edge does not route it), `GET
  /auth/v1/pin-key` (the newest `PIN_TRANSPORT` version as PEM; the apps, mw-seeder, the test console and
  the Newman suite encrypt the PIN with it). mw-crypto down and nothing cached: `503 MW-SYS-050`.
- Dev only: `POST /internal/v1/seed/pins` (`controller/SeedController`, `service/SeedPinService`,
  `mw.auth.seed-api.enabled`, compose `MW_AUTH_SEED_API_ENABLED`, true only in `dev.env.defaults`):
  mw-seeder's first PINs, each an `encryptedPin` under the transport key, the HMAC derived by mw-crypto;
  per wallet `CREATED`, `UNCHANGED` (has a PIN), `UNKNOWN_WALLET` (not projected yet: the seeder retries),
  `WEAK`. No token and reachable on the plain public port (the seeder has no client certificate); APISIX
  never routes `/internal/*`; off, the path is the internal chain's (401).
- Valkey (`repository/DeviceSessionStore`, `config/DeviceBindingConfig` fail-fast): sessions
  `sess:{walletId}:{deviceId}` (value = the idle timeout in seconds, TTL sliding: `idle-timeout` 5 min, per channel
  `idle-timeout-by-channel`) and nonces `nonce:{deviceId}:{nonce}` (SET NX, 2x skew + 30 s; mw-app-gateway uses the
  same key for its signed submits, so a nonce is used once across both). Valkey down `503 MW-SYS-012`; not in the
  health groups. Password `spring.data.redis.password` from `secret/mw-auth`.
- Internal device lookups for mw-app-gateway's device gate (contract `api/mw-auth/auth-internal.v1.openapi.yaml`,
  `controller/InternalDeviceController`, internal mTLS port): `GET /internal/v1/devices/{deviceId}` -> `{deviceId,
  walletId, publicKey, boundAt}` of a bound device (404 `MW-AUTH-003`); `GET /internal/v1/sessions/{walletId}/{deviceId}`
  extends the live session and returns `{walletId, deviceId, idleTimeoutSeconds, deviceKeyFingerprint, boundAt}`
  (fingerprint = hex SHA-256 of the key's SPKI DER; 404 `MW-AUTH-003` when the device is not bound to that wallet,
  `MW-AUTH-007` without a session). **Session check decision (ticket 68, kept in ticket 69):** the gateway asks this
  lookup on every app request (it caches a confirmation 10 s and a device key 5 min) rather than trusting a `sid`
  claim: a logout, a lock or a new binding must end app access at once, which a 5 min token cannot. The fingerprint
  (security review of 68, fixed in 69) lets the gateway drop a cached key of a replaced binding within the 10 s.
- Error codes so far: `MW-AUTH-001..007`, `010..012`, `040..045`, `047`, `048` (18.7; `046` is the core's),
  `MW-VAL-001`, `MW-VAL-022`, `MW-VAL-BODY_TOO_LARGE`, `MW-SYS-010`, `MW-SYS-012`, `MW-SYS-050/051`.

## Contracts owned

- `api/mw-auth/auth.v1.openapi.yaml` (public API: OTP and devices, ticket 68; login, tokens, key documents, ticket
  69; `contracts/ApiContractsTest`, derived body schemas in `src/main/resources/derived-schemas/`,
  `scripts/derived-schemas.sh --write` after a change).
- `api/mw-auth/auth-internal.v1.openapi.yaml` (internal API: the device lookups of ticket 68, the session's key
  fingerprint and the dev seed API of ticket 69; tickets 70-71 add their operations). `auth.wallet-lock.v1` comes
  with ticket 70.

## Contracts used

- `events/mw-core-mock/core.event.v1.schema.json#WalletStateChanged` (own type `dto/WalletStateChanged`;
  `contracts/ConsumedContractsTest` reads every sample through `mapper/WalletEventMapper`).
- `api/mw-crypto/crypto-internal.v1.openapi.yaml` as a client (`/tokens`, `/envelope/encrypt`,
  `/envelope/decrypt`, `/pin/verify`, `/pin/derive`, `/jws/sign`, `/jwks/{purpose}`; own types `dto/Crypto*`,
  `dto/EncryptedPin`, `dto/PinHmac`).
- `events/mw-notify/mw.notify.request.v1` (produced, ticket 68: the binding OTP and the new device SMS; own type
  `dto/NotificationRequest`; `contracts/ProducedContractsTest`, `mapper/DeviceNotificationMapperTest`).
- `contracts.yml` is the machine-readable list, checked by `./gradlew :contractsLint`.

## Tests

- `repository/WalletRepositoryIT`: upsert by version, MSISDN columns kept, the schema's checks and the
  other tables' keys (one bound device, one open reset, FKs).
- `messaging/WalletProjectionKafkaIT` (Kafka, `CryptoClient` mocked): new wallet hashed + encrypted,
  out-of-order versions, duplicate eventId, other core events skipped, mw-crypto down -> retry topic.
- `client/CryptoClientIT`: WireMock mw-crypto over HTTPS with `needClientAuth` (stores issued by
  `PkiTestAuthority.writeLocalhostStores`), WireMock token endpoint: bearer + body, token reuse, 401
  refresh, 5xx / timeout / open breaker / no client certificate = `MW-SYS-050`, 403 = `MW-SYS-051`.
- `controller/InternalApiMtlsIT` (`mw.tls.enabled=true`, OpenBao container as the Vault): the allow-list
  with matching certificates, cert/token mismatch, a client off the list, bad tokens, no client
  certificate (handshake), the public port refuses `/internal/**`, health on the management port.
- `contracts/ConsumedContractsTest`, `ProducedContractsTest`, `ApiContractsTest` (no Spring, no Docker).
- Ticket 68 (moved from mw-app-gateway): `security/DeviceBindingIT` (PostgreSQL + Valkey: binding, OTP rules,
  replay, tamper, stale timestamp, replacement, a device moving wallets, expired session, the internal lookups with
  a services token), `controller/DeviceControllerTest` (body schemas), `repository/DeviceOtpRepositoryIT`,
  `repository/DeviceSessionStoreTest` (Valkey down 503), `service/RequestSignaturesTest`,
  `mapper/DeviceNotificationMapperTest`, `config/DeviceBindingPropertiesTest`, `util/MsisdnTest`; `ValkeyInfrastructure`.
  Since ticket 69 `DeviceBindingIT` names the wallet by MSISDN (mw-crypto mocked), opens sessions with the
  first-PIN OTP login and checks the session's key fingerprint after a rebinding.
- Ticket 69: `service/TokenServiceTest` (claims, lifetimes, RS256 by an in-test key, refresh token shape and
  hash, rotation, reuse and wrong device revoke the family), `security/OwnTokenDecoderTest`, `security/LoginIT`
  (PostgreSQL + Valkey, mw-crypto a behaving mock: the PIN login and every refusal (unknown MSISDN, status,
  locks 041/042/043, device of another wallet, bad signature, replay, OTP vs PIN, mw-crypto down), refresh
  rotation and reuse, another device, logout, a foreign token, the first PIN flow, the JWK Set and discovery,
  the PIN key, the seed API), `controller/DeviceControllerTest` (body schemas of OTP, binding, login, refresh;
  no token needed, a stale bearer ignored, logout and first PIN need one).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field.
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
  Add context with `log.atInfo().addKeyValue("event", "...")` or the MDC.
- `config/MwServiceProperties`: config binding; secrets from OpenBao when `VAULT_ENABLED=true`.
- `config/ServiceCertificateManager` + `TlsProperties` (ticket 66, from the template).
- `messaging/EventPublisher`, `OutboxWriter` + `OutboxRelay`, `Inbox` (used by the projection).
- `route/RetryTopicRouteBuilder` + `messaging/RetryDelayListener`: subclass per consumed topic; retry
  topics 10 s / 1 min / 10 min then DLQ (19.2); per-record commit; no sleeps on Kafka threads.
- `application.yml`: Resilience4j defaults (19.2), own schema `auth` migrated by Flyway on start.

## Compose and provisioning

- `deploy/docker-compose.yml` block `mw-auth` (profiles `minimal` and `core`; port 8095 `MW_PORT_AUTH` =
  the public port 8080, the internal mTLS port 8443 is not published; healthcheck on 8081; limit
  `AUTH_MEM_LIMIT` 320M, heap `AUTH_XMX` 112m; waits for a healthy mw-crypto; `MW_AUTH_SEED_API_ENABLED`
  dev only). Ticket 69 adds no memory: the token work is a few small HTTP calls to mw-crypto per login, and no
  measurement on a running stack was taken in the ticket (deferred to the final integration test, when
  `docker stats` shows whether 320M still holds). `deploy/provision/services.sh`
  `provision_mw_auth`: role `auth_rw`, schema `auth`, `secret/mw-auth` (`spring.datasource.password`,
  `mw.auth.crypto.client-secret` from `KC_MW_AUTH_SECRET`, `spring.data.redis.password` from `VALKEY_PASSWORD`),
  AppRole volume `auth-approle`; PKI role
  `mw-auth` from `PKI_SERVICES`. Keycloak realm `services` client `mw-auth` (audience `mw-crypto`): a
  running stack needs `deploy/reset.sh` to import it.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew :mw-auth:test` | every test in one JVM: unit, slice, contract, Testcontainers `*IT` and `@Conformance` (`*IT` skipped without Docker) |
| `./gradlew :mw-auth:build` | the tests plus the boot jar `build/libs/mw-auth.jar` |
| `./gradlew :mw-auth:bootJar && docker build -t mfs-mw/mw-auth:local services/mw-auth` | image, as spec 4.3 rule 1 |
