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
  BIND/LOGIN and `refresh_token.amr`; V7, ticket 70: purpose RESET, `pin_reset` status VERIFIED and its
  `device_id`, `expires_at`, `change_request_id`, `command_id`, `reason`); `channel_otp` + `channel_otp_quota` (V8, ticket 72: the checkout OTP, its own slot and quota, `repository/ChannelOtpRepository`). Code so far: `wallet` (`repository/WalletRepository`), `device`
  (`repository/DeviceRepository`), `device_otp` (`repository/DeviceOtpRepository`), `credential`,
  `attempt`, `lock` (`repository/CredentialRepository`), `refresh_token` (`repository/RefreshTokenRepository`),
  `pin_reset` (`repository/PinResetRepository`, ticket 70).
- Wallet projection (D5; `messaging/CoreWalletEventListener` (`@KafkaListener`) ->
  `service/WalletProjectionService`, `mapper/WalletEventMapper`, `dto/WalletStateChanged`): consumes
  `core.wallet.event.v1` in group `mw-auth` (retry topics + DLQ; `deploy/kafka/topics.txt`). Only
  `WalletStateChanged` is read for the projection (and, ticket 70, `CustomerFactorsVerified` /
  `CustomerFactorsRejected` for the PIN reset, below); the topic's other events (replies to
  mw-orchestrator) are skipped without an inbox row. Every WalletStateChanged goes through the inbox (consumer `mw-auth.wallet-projection`,
  eventId) and replaces the row only with a higher `version` (`upsertIfNewer` / `updateIfNewer`), so
  duplicates, the dev snapshot and out-of-order events change nothing. A wallet seen for the first time
  gets its MSISDN (normalised to `01XXXXXXXXX`) tokenised (`msisdn_token`, keyed hash under
  `TOKENISE_MSISDN`, plus its kid; unique: the login lookup) and encrypted (`msisdn_enc`, the mw-crypto
  envelope under `KEK_COLUMN` as jsonb, AAD the walletId); the MSISDN columns are never rewritten
  afterwards (a token-key rotation needs a re-tokenise job: later). `wallet_id` is the core's id, which in
  mw-core-mock is the MSISDN itself. mw-crypto down: `MW-SYS-050` before anything is written, the
  record goes to the retry topics (fail closed). Switch: `mw.auth.consumer.enabled` (both listeners and
  their retry consumers; tests off). The core's account model (ticket 94, FRS 7.1): `ORGANISATION` is stored
  as this service's `ORG` (no app role either way), the statuses `DORMANT` and `CLOSED` are projected
  (`V9__core_wallet_statuses.sql`) and, like every status but ACTIVE, refused at login (`MW-AUTH-043`);
  `kycLevel` is ignored (`mapper/WalletEventMapperTest`).
- **WalletStateChanged envelope decision (ticket 67):** the `core.event.v1` envelope is kept as it is
  (no relaxed required fields): mw-core-mock fills the command fields of this unsolicited event with a
  fresh UUIDv7 `txnId`, `commandId` `wallet-state:<txnId>`, `coreRef` `WALLET-V<version>`, outcome
  `SUCCESS`, key = walletId (one wallet's states stay ordered on one partition). mw-orchestrator, which
  parses `txnId` as a UUID on every event of the topic, finds no transaction and drops it
  (`txn.unknown`), as it already does with `WalletUnlocked`; nothing changes there.
- mw-crypto client (`client/CryptoClient`, `client/ServiceTokenClient`, `config/AuthProperties`
  `mw.auth.crypto.*`): `https://mw-crypto:8080` over mTLS with this service's Vault certificate
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
  wallet moves here), the "new device bound" SMS (`DEVICE_BOUND`, TXN; param `deviceId` = the new device: mw-notify revokes the push tokens of the wallet’s other devices on it, ticket 110) in the outbox. Afterwards the
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
    then the PIN through `service/PinVerifier` (lockout, below; wrong: `401 MW-AUTH-040` with `triesLeft`,
    counted in `auth.attempt`; a match resets the count and stores a rotated key's rehash) or, for
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
    without a credential, else `403 MW-AUTH-002`): the PIN policy (`service/PinService.newPin`, below), the
    HMAC stored, the OTP family revoked, new tokens with `amr` [pin, device].
  - Own-token verifier (`security/OwnTokenDecoder`, logout, first PIN and PIN change): RS256 only, `typ at+jwt`, key
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
- Dev only, the same switch `mw.auth.seed-api.enabled`: the test console's account test API (contract
  `api/mw-auth/auth-test.v1.openapi.yaml`, `controller/TestAccountController`, `service/TestAccountService`,
  `dto/TestAccount`; APISIX test route `test-auth`, `/test/auth/v1/accounts/*` with `MW_E2E_ROUTES=1`):
  `GET /test/v1/accounts/{walletId}` (pinSet, locked/lockedUntil, failedPinAttempts, pinLock, adminLocked,
  deviceOtp, checkoutOtpSends, devices), `POST .../unlock` (ADMIN + PIN lock and count, device OTP and its
  send window, checkout OTP quota), `POST .../unbind` (every device unbound, refresh tokens revoked, sessions
  closed), `POST .../pin {encryptedPin}` (HMAC derived as for a seeded PIN, replaces the current one, history
  forgotten); each answers the account afterwards; unknown wallet 404 `MW-VAL-003`. No token (chain 0, with
  the seed path); off, nothing answers `/test/**` (404).
- Redis (`repository/DeviceSessionStore`, `config/DeviceBindingConfig` fail-fast): sessions
  `sess:{walletId}:{deviceId}` (value = the idle timeout in seconds, TTL sliding: `idle-timeout` 5 min, per channel
  `idle-timeout-by-channel`) and nonces `nonce:{deviceId}:{nonce}` (SET NX, 2x skew + 30 s; mw-app-gateway uses the
  same key for its signed submits, so a nonce is used once across both). Redis down `503 MW-SYS-012`; not in the
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
- PIN lifecycle (ticket 70, spec 18.7, D20; `config/PinProperties` `mw.auth.pin.*`, compose knobs
  `MW_AUTH_LOCK_TRIES`, `MW_AUTH_LOCK_LEVEL1`, `MW_AUTH_LOCK_LEVEL2`, `MW_AUTH_LOCK_DECAY`, short in
  `deploy/dev.env.defaults`: 1 min / 3 min):
  - **`service/PinVerifier` is the one PIN check** (login, device binding, PIN change; ticket 71's
    assertion through it too). Interface for callers:
    `public void verify(String walletId, EncryptedPin pin)` (returns only on a correct PIN; throws
    `MwException` 403 `MW-AUTH-043` ADMIN lock, 423 `MW-AUTH-041` with `retryAfter` / `MW-AUTH-042` PIN
    lock, 403 `MW-AUTH-045` no PIN, 401 `MW-AUTH-040` wrong with `triesLeft`, 503 `MW-SYS-050` mw-crypto
    down, nothing counted), `public void requireUnlocked(String walletId)` (the lock part only) and
    `public boolean clearPinLock(String walletId)` (reset, ops unlock; inside the caller's transaction).
    The caller checks the wallet's status first (`WalletAccess.check`, or `checkActive` where a PIN lock
    must not stop it). Order: the locks BEFORE mw-crypto (a locked wallet costs no HSM call and is no
    oracle), then mw-crypto `/pin/verify`; a wrong PIN runs one transaction under the wallet row's lock
    (`select ... for update`): a PIN lock taken meanwhile wins (not counted), else the count; the
    `tries`-th wrong PIN moves the PIN lock one level up (level 1 `level1` 1 h, level 2 `level2` 24 h, level 3
    until a reset), resets the count, audits `auth.pin.locked` and is answered with the new lock. A
    correct PIN resets the count, not the level: the `lock` row stays after its `until` as the level
    history, forgotten `decay` (30 d) after the lock was taken.
  - Policy (`service/PinService.newPin`; first PIN, change, reset): mw-crypto `/pin/derive` runs the
    weak-PIN rules on the clear PIN with `policyInput` {msisdn (canonical, decrypted from `msisdn_enc`
    where needed), birthYear (the projection's, when the core supplies it), lastHmac/lastHmacKeyId (the
    current PIN)}; the earlier PINs (`credential.last_hmacs`, `mw.auth.pin.history` 3 = the current and
    2 earlier) are compared here on the HMAC under the same kid (`LAST_PIN`). Weak: 400 `MW-AUTH-044`
    with the rule. The dev seed API passes no inputs.
  - Change: `PUT /auth/v1/pin {encryptedPin, newEncryptedPin}` (bearer of a PIN session; an OTP session
    `403 MW-AUTH-045`; family live): status and locks, the current PIN through `PinVerifier`, the policy;
    one transaction: credential replaced (old PIN into the history), audit `auth.pin.changed`, SMS
    `PIN_CHANGED`; the family revoked and new tokens with a fresh `amr` [pin, device].
  - Reset (`service/PinResetService`, `controller/PinResetController`, `entity/PinReset`): self-service
    `POST /auth/v1/otp` purpose `RESET` (bound device only, or any device while a care reset is
    APPROVED; else 403 `MW-AUTH-002`; template `PIN_RESET_OTP`), `POST /auth/v1/pin-resets {msisdn, otp,
    nidLast4, dateOfBirth}` signed by the bound device (wallet ACTIVE and no ADMIN lock; a PIN lock does
    not stop a reset; an open CARE reset `409 MW-VAL-030`): the RESET OTP, the factors tokenised by
    mw-crypto under `TOKENISE_NID` as `<walletId>:<nid4>` and `<walletId>:<YYYY-MM-DD>`
    (`CryptoClient.tokeniseNid`), then one transaction: OTP spent, an older open SELF reset expired, the
    reset `PENDING_KYC` (random UUIDv4 id = the device's capability, `ttl` 15 min) and
    `VerifyCustomerFactors` on `core.wallet.command.v1` through the outbox (`dto/VerifyCustomerFactorsCommand`,
    UUIDv7 txnId, commandId `pin-reset:<resetId>`). The core's `CustomerFactorsVerified` /
    `CustomerFactorsRejected` (inbox consumer `mw-auth.pin-reset`, matched on commandId) move it to
    `VERIFIED` / `REJECTED` (reason the core's code); a late answer changes nothing. `GET
    /auth/v1/pin-resets/{id}` and `PUT /auth/v1/pin-resets/{id}/pin {encryptedPin}` only for the reset's
    device (`X-Device-Id`, else 404 `MW-VAL-003`), the PUT only while `VERIFIED` and unexpired (409
    `MW-VAL-031`): the policy, then one transaction: `DONE`, credential replaced, the PIN lock and count
    cleared, audit `auth.pin_reset.done`, SMS `PIN_CHANGED`; afterwards every refresh token revoked and
    the bound device's session closed. Care (`PinResetService.approveCare`, idempotent on
    `changeRequestId`): from mw-admin-api's committed PIN_RESET on `auth.wallet-lock.v1` (security
    review of ticket 70: written in the approval's transaction, so a reject that wins the race opens
    nothing), or `POST /internal/v1/pin-resets {walletId, changeRequestId, makerId, checkerId, reason?,
    locale?}` (`controller/InternalPinResetController`, only client `mw-admin-api`, else 403; 201 new,
    200 the same reset; kept for a synchronous caller such as mw-care-api, unused by mw-admin-api): open
    resets expired, a `CARE` reset `APPROVED` for `care-ttl` 72 h, audit, SMS `PIN_RESET_APPROVED`; the
    customer then asks a RESET OTP on any device (a lost phone) and `POST /auth/v1/pin-resets {msisdn,
    otp}` unsigned takes it up: `VERIFIED` for that device for `ttl`; then the same PUT. Bind the new
    device after the reset (OTP + the new PIN).
  - **KYC decision:** the factors leave only as TOKENISE_NID hashes bound to the wallet; mw-core-mock
    cannot recompute them and checks only the wallet (open point for the real core: a shared
    tokenisation or its own check).
  - Wallet lock topic (`messaging/WalletLockListener` (`@KafkaListener`) ->
    `service/WalletLockService`, `mapper/WalletLockMapper`, `dto/WalletLockCommand`; owned contract
    `auth.wallet-lock.v1`, group `mw-auth`, retry topics + DLQ, `deploy/kafka/topics.txt`): once per
    `commandId` (inbox `mw-auth.wallet-lock`): `LOCK` = an ADMIN lock (403 `MW-AUTH-043` everywhere) +
    every refresh token revoked + session closed; `UNLOCK` = ADMIN lock and PIN lock (every level,
    count) removed (mw-admin-api's approved ops unlock, ticket 52; it also sends core `UnlockWallet`, which since
    ticket 72 lifts only a core-side account lock: the core has no PIN lockout, mw-auth's is the only one); `REVOKE_SESSIONS` = revoke and close
    only; `PIN_RESET` (with `changeRequestId`, `makerId`) = the approved care reset above (its own
    idempotency, no inbox row). The Redis session close runs on every delivery (idempotent). Unknown wallet: skipped
    (logged); malformed: `MW-VAL-002` (retry topics, DLQ). Audited `auth.wallet.locked|unlocked|sessions_revoked`.
  - Audit (`service/AuditTrail`, `mapper/AuditEventMapper`, `dto/AuditEvent`): `mw.audit.event.v1`
    through the outbox in the change's transaction, resource `wallet/<masked id>`, key the resource,
    only levels / statuses / reasons, never PIN material or factors. SMS through the outbox:
    `service/PinNotices` (`PIN_CHANGED`, `PIN_RESET_APPROVED`; mw-notify V12).
- Transaction authorisation assertion (spec 18.7, D22, ticket 71; `controller/TxnAuthorisationController`,
  `service/TxnAuthorisationService`, `service/PinVerification`, `repository/AssertionCache`, `util/TxnHash`,
  `util/CanonicalJson`, `util/UuidV7`, `config/AssertionProperties` `mw.auth.assertion.*`): `POST
  /internal/v1/txn-authorisations` on the internal mTLS port, callers `mw.auth.assertion.callers`
  (mw-app-gateway, mw-ussd, mw-checkout; mw-admin-api and anyone else 403 `MW-AUTH-002`). Body `{walletId |
  msisdn (exactly one, else 400 MW-VAL-001), channel, deviceId?, encryptedPin, txn: {txnType, amount (minor),
  currency, payer {type, id}, payee {type, id}, idempotencyKey}}`. Amount rule (`service/TxnAmountRule`, ADR D26):
  at least 1, except txnType `BALANCE` (balance inquiry): 0 with payee = payer (type and id), else 400 `MW-VAL-001`
  before any wallet lookup or PIN check. Order: wallet known (else 403 `MW-AUTH-002`),
  `WalletAccess.check` (043/041/042), a sent `deviceId` must be the wallet's bound device (401 `MW-AUTH-047`),
  then Redis `assert:{walletId}:{idempotencyKey}` (value `<exp ms>|<txnHash>|<newDevice>|<jws>`, TTL = life,
  `SET NX`; the life is also checked against the service clock): a live entry for the same `txnHash` is
  returned again **without a PIN check**, for another hash 409 `MW-IDEM-001`; then the PIN through
  `PinVerification` (lockout: wrong 401 `MW-AUTH-040`, counted, the tries-th locks 423; none 403 `MW-AUTH-045`) and the JWS by mw-crypto
  `ASSERTION_SIGN` (ES256, kid `<keyId>.v<n>`, header `typ txn-assertion+jwt`): `iss` mw-auth, `aud` core,
  `sub` walletId, `jti` UUIDv7, `iat`, `exp` = iat + 60 s, `txnHash`, `channel`, `authMethod` PIN,
  `deviceId` (when sent), `newDevice` (the wallet's bound device's `boundAt` within `new-device-window` 24 h).
  200 `{assertion, expiresAt, newDevice}`; INFO `auth.txn.authorised` carries only the jti.
  `txnHash` = base64url (no padding) SHA-256 of the RFC 8785 canonical JSON of `{txnType, amount, currency,
  payer {type, id}, payee {type, id}, idempotencyKey, channel}` with the values of the `mw.txn.request.v1`
  the channel then publishes (payer = debitParty, payee = creditParty, amount = valueMinor, the request's
  idempotencyKey); mw-core-mock has a byte-identical copy (`TxnHashTest` literals in both).
  **PIN check seam (ticket 71, for ticket 70):** the assertion uses the interface `service/PinVerification`,
  implemented by `service/PinVerifier` since ticket 70 (the lockout above: the locks before mw-crypto,
  a wrong PIN counted and escalated, `security/PinLifecycleIT` proves it for the assertion too).
  **Assertion JWK Set decision (ticket 71):** a separate public document `GET
  /auth/v1/.well-known/assertion-jwks.json` (`WellKnownController`, `JwksService.assertionJwks`, mw-crypto
  `/jwks/ASSERTION_SIGN`, public members only, cached `jwks-cache-ttl`), not mixed into the access tokens'
  `jwks.json` (APISIX reads that one and accepts RSA only). mw-core-mock fetches it over plain HTTP on the
  public port 8080 (`http://mw-auth:8080/...`); the `auth-v1` APISIX route also exposes it at the edge
  (public keys only). mw-crypto needed no change: `mw.crypto.authz.purposes.ASSERTION_SIGN` already names
  mw-auth, and ASSERTION_SIGN is a dev bootstrap purpose.
- Checkout OTP (ticket 72, spec 9.3; `controller/InternalOtpController`, `service/ChannelOtpService`,
  `dto/InternalOtpRequest`, `dto/InternalOtpVerification`; internal mTLS port, only client
  `mw-checkout` (`ChannelOtpService.CHECKOUT_CALLER`, any other allowed client 403 `MW-AUTH-002`)): `POST
  /internal/v1/otp {msisdn, purpose CHECKOUT, bindingId (the checkout sessionId, the device id rule),
  locale?, amount (taka "25.50"), merchant}` -> 202 `{wallet (masked), otpExpiresAt, sendsLeft}`: the
  wallet known (403 `MW-AUTH-002`) and `WalletAccess.checkActive` (403 `MW-AUTH-043`; a PIN lock is the
  assertion's to answer), then the OTP in **its own slot** (security review of ticket 72; V8
  `channel_otp`, one row per wallet, purpose and session, `repository/ChannelOtpRepository`; hash bound
  to wallet, purpose and session): it never replaces or uses up a device OTP (`device_otp`,
  BIND/LOGIN/RESET) nor another session's OTP, and the other way round. Its own send quota per wallet
  and purpose over all its sessions (`channel_otp_quota`, `mw.auth.channel-otp.*`
  (`config/ChannelOtpProperties`): ttl 2 min, 3 checks, `max-sends` 5 per `send-window` 15 min,
  `MW_AUTH_CHANNEL_OTP_MAX_SENDS`, dev compose 20), counted and the slot written in one conditional
  statement (429 `MW-VAL-022`); a wallet's expired checkout OTPs are deleted when it asks a new one.
  Template `CHECKOUT_OTP` (mw-notify V8; params `otp`, `amount`, `merchant`), straight to Kafka (503
  `MW-SYS-010`). `POST /internal/v1/otp/verifications {msisdn, purpose, bindingId, otp}` -> 204: only
  the same purpose and session match (401 `MW-AUTH-011` otherwise or when none, expired, spent or void;
  `010` wrong; `012` checks used up; each check reserved atomically, the wrong guess that uses the last
  one voids it), then spent once. Tested by `security/CheckoutOtpIT` (own slot both ways, two sessions,
  own quota both ways).
- Channel PIN transport key (security review of ticket 72; `controller/InternalPinKeyController`,
  `service/ChannelPinKeyService`, `dto/InternalPinKey`): `GET /internal/v1/pin-key` on the internal mTLS
  port for the assertion's callers (`mw.auth.assertion.callers`; anyone else 403 `MW-AUTH-002`) ->
  `{keyId, algorithm, publicKeyPem, purpose PIN_TRANSPORT}`, the same newest version as the public `GET
  /auth/v1/pin-key` (which stays for the apps only). mw-app-gateway, mw-ussd and mw-checkout read the key
  here, never from the plain public port, and refuse a key of any other purpose. Tested in
  `security/LoginIT`.
- Error codes so far: `MW-AUTH-001..007`, `010..012`, `040..045`, `047`, `048` (18.7; `046` is the core's),
  `MW-IDEM-001` (ticket 71), `MW-VAL-001`, `MW-VAL-003`, `MW-VAL-022`, `MW-VAL-030/031` (reset, ticket 70),
  `MW-VAL-BODY_TOO_LARGE`, `MW-SYS-010`, `MW-SYS-012`, `MW-SYS-050/051`.

## Contracts owned

- `api/mw-auth/auth.v1.openapi.yaml` (public API: OTP and devices, ticket 68; login, tokens, key documents, ticket
  69; the assertion JWK Set `EcJwkSet`, ticket 71; `contracts/ApiContractsTest`, derived body schemas in `src/main/resources/derived-schemas/`,
  `scripts/derived-schemas.sh --write` after a change).
- `api/mw-auth/auth-internal.v1.openapi.yaml` (internal API: the device lookups of ticket 68, the session's key
  fingerprint and the dev seed API of ticket 69; `POST /internal/v1/txn-authorisations`, ticket 71; the care PIN
  reset `POST /internal/v1/pin-resets`, ticket 70; the checkout OTP `POST /internal/v1/otp` and
  `/internal/v1/otp/verifications`, ticket 72, bodies checked in `contracts/ApiContractsTest`).
- `events/mw-auth/auth.wallet-lock.v1.schema.json` (ticket 70; consumed here, produced by mw-admin-api;
  `contracts/ConsumedContractsTest` reads the samples through `mapper/WalletLockMapper`).

## Contracts used

- `events/mw-core-mock/core.event.v1.schema.json#WalletStateChanged` (own type `dto/WalletStateChanged`;
  `contracts/ConsumedContractsTest` reads every sample through `mapper/WalletEventMapper`), and (ticket 70)
  `#CustomerFactorsVerified`, `#CustomerFactorsRejected` (`WalletEventMapper.readFactorsAnswer`).
- `events/mw-orchestrator/core.command.v1.schema.json#VerifyCustomerFactors` (produced, ticket 70: the
  owner mw-orchestrator's file, changed as this ticket's proposal; `contracts/ProducedContractsTest`).
- `events/mw-audit-dw/mw.audit.event.v1` (produced, ticket 70).
- `api/mw-crypto/crypto-internal.v1.openapi.yaml` as a client (`/tokens`, `/envelope/encrypt`,
  `/envelope/decrypt`, `/pin/verify`, `/pin/derive`, `/jws/sign`, `/jwks/{purpose}`; ticket 70 `/tokens` domain
  NID, allowed to mw-auth by mw-crypto's `TOKENISE_NID` entry; own types `dto/Crypto*`,
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
- `controller/InternalApiMtlsIT` (`mw.tls.enabled=true`, Vault container as the Vault): the allow-list
  with matching certificates, cert/token mismatch, a client off the list, bad tokens, no client
  certificate (handshake), the public port refuses `/internal/**`, health on the management port.
- `contracts/ConsumedContractsTest`, `ProducedContractsTest`, `ApiContractsTest` (no Spring, no Docker).
- Ticket 68 (moved from mw-app-gateway): `security/DeviceBindingIT` (PostgreSQL + Redis: binding, OTP rules,
  replay, tamper, stale timestamp, replacement, a device moving wallets, expired session, the internal lookups with
  a services token), `controller/DeviceControllerTest` (body schemas), `repository/DeviceOtpRepositoryIT`,
  `repository/DeviceSessionStoreTest` (Redis down 503), `service/RequestSignaturesTest`,
  `mapper/DeviceNotificationMapperTest`, `config/DeviceBindingPropertiesTest`, `util/MsisdnTest`; `RedisInfrastructure`.
  Since ticket 69 `DeviceBindingIT` names the wallet by MSISDN (mw-crypto mocked), opens sessions with the
  first-PIN OTP login and checks the session's key fingerprint after a rebinding.
- Ticket 69: `service/TokenServiceTest` (claims, lifetimes, RS256 by an in-test key, refresh token shape and
  hash, rotation, reuse and wrong device revoke the family), `security/OwnTokenDecoderTest`, `security/LoginIT`
  (PostgreSQL + Redis, mw-crypto a behaving mock: the PIN login and every refusal (unknown MSISDN, status,
  locks 041/042/043, device of another wallet, bad signature, replay, OTP vs PIN, mw-crypto down), refresh
  rotation and reuse, another device, logout, a foreign token, the first PIN flow, the JWK Set and discovery,
  the PIN key, the seed API), `controller/DeviceControllerTest` (body schemas of OTP, binding, login, refresh;
  no token needed, a stale bearer ignored, logout and first PIN need one).
- Ticket 71: `util/TxnHashTest` (canonical JSON and hash against literals worked out outside Java; the same
  txn in another field order gives the same JSON), `security/TxnAuthorisationIT` (PostgreSQL + Redis,
  mw-crypto a behaving mock signing ES256 with an in-test P-256 key; the clock a `@TestBean MutableClock`):
  claims verified against the served assertion JWK Set, USSD by MSISDN, newDevice, wrong PIN counted, PIN
  lock (no PIN check), blocked wallet, no PIN, reuse inside the life (one PIN check), same key other txn 409,
  a new assertion after the life, device of another wallet, callers, walletId xor msisdn, mw-crypto down, a
  BALANCE assertion and its refusals); `service/TxnAmountRuleTest` (ADR D26).

- Ticket 70: `security/PinLifecycleIT` (PostgreSQL + Redis, mw-crypto a behaving mock): the lockout
  levels through login (041 with retryAfter, 24 h, 042 permanent; no mw-crypto call while locked), the
  decay, device binding counting and refusing while locked, parallel wrong PINs never skipping a level,
  PIN change (step-up, policy inputs, LAST_PIN, family revoked), the self-service reset with the core's
  answer (command validated against core.command.v1, factors never in clear, wrong device 404, not ready
  409, weak 044, lock cleared, tokens revoked), the rejection and a duplicate answer, the reset's
  device/signature/OTP rules, the care reset (only mw-admin-api, idempotent, a new device);
  `messaging/WalletLockKafkaIT` (Kafka + Redis: LOCK, UNLOCK, duplicate, REVOKE_SESSIONS, unknown
  wallet, malformed, the core's answer on core.wallet.event.v1); `service/PinServiceTest` (policy inputs,
  every rule mw-crypto names, LAST_PIN on the history, the history kept).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field.
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
  Add context with `log.atInfo().addKeyValue("event", "...")` or the MDC.
- `config/MwServiceProperties`: config binding; secrets from Vault when `VAULT_ENABLED=true`.
- `config/ServiceCertificateManager` + `TlsProperties` (ticket 66, from the template).
- `messaging/OutboxWriter` + `OutboxRelay`, `Inbox` (used by the projection and the wallet lock topic).
- Kafka through Spring Kafka (ADR D27; no Camel in this module, copied from the template):
  `messaging/EventPublisher` sends records with the 6.1 headers through `KafkaTemplate` (idempotent,
  `acks=all`, at most 5 in flight; `publish` waits for the ack, `publishStoredAsync` sends an outbox row
  with exactly its stored headers). `config/KafkaConfig` puts every `@KafkaListener` on non-blocking retry
  topics `<topic>.<group>.retry.1..3` (10 s / 1 min / 10 min, `mw.kafka.retry.delays`) then
  `<topic>.<group>.dlq` (`messaging/RetryTopicNaming`, group = `spring.kafka.consumer.group-id`, the
  service name; declared in `deploy/kafka/topics.txt`); validation and JSON failures and records that are
  not UTF-8 go to the DLQ at once; `messaging/RetryTopicRecoverer` adds `mw-retry-attempt`,
  `mw-original-topic`, `mw-error-class`, `mw-retry-not-before`; `messaging/RetryDueTimeCap` caps a forged
  due time; `messaging/ConsumerContextInterceptor` puts the record's ids in the MDC. Listeners take
  `ConsumerRecord<String, String>` and `Acknowledgment` and call `ack.acknowledge()` after handling
  (per-record commit, AckMode `MANUAL_IMMEDIATE`); a delay pauses the partition, never sleeps on the
  consumer thread. Settings: `spring.kafka.*`, `mw.kafka.send-timeout`, `mw.kafka.listener.shutdown-timeout`
  (100 ms in tests), `mw.kafka.retry.exclude-topics`. `messaging/RetryTopicIT` (conformance) checks it all.
- `scheduler/OutboxRelayScheduler`: relays the transactional outbox (`messaging/OutboxRelay`, 6.4) every
  `mw.outbox.relay.period` after a run ends, ShedLock-guarded; off in tests (`mw.outbox.relay.enabled`).
  Tests that consume create their topics with retry topics and DLQ first (`KafkaTestTopics.createWithRetries`).
- `application.yml`: Resilience4j defaults (19.2), own schema `auth` migrated by Flyway on start.

## Compose and provisioning

- `deploy/docker-compose.yml` block `mw-auth` (profiles `minimal` and `core`; port 8095 `MW_PORT_AUTH` =
  the public port 8080, the internal mTLS port 8443 is not published; healthcheck on 8081; limit
  `AUTH_MEM_LIMIT` 320M, heap `AUTH_XMX` 112m; waits for a healthy mw-crypto; `MW_AUTH_SEED_API_ENABLED`
  dev only). Ticket 69 adds no memory: the token work is a few small HTTP calls to mw-crypto per login, and no
  measurement on a running stack was taken in the ticket (deferred to the final integration test, when
  `docker stats` shows whether 320M still holds). `deploy/provision/services.sh`
  `provision_mw_auth`: role `auth_rw`, schema `auth`, `secret/mw-auth` (`spring.datasource.password`,
  `mw.auth.crypto.client-secret` from `KC_MW_AUTH_SECRET`, `spring.data.redis.password` from `REDIS_PASSWORD`),
  AppRole volume `auth-approle`; PKI role
  `mw-auth` from `PKI_SERVICES`. Keycloak realm `services` client `mw-auth` (audience `mw-crypto`): a
  running stack needs `deploy/reset.sh` to import it.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew :mw-auth:test` | every test in one JVM: unit, slice, contract, Testcontainers `*IT` and `@Conformance` (`*IT` skipped without Docker) |
| `./gradlew :mw-auth:build` | the tests plus the boot jar `build/libs/mw-auth.jar` |
| `./gradlew :mw-auth:bootJar && docker build -t mfs-mw/mw-auth:local services/mw-auth` | image, as spec 4.3 rule 1 |
