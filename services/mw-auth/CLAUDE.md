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
  plus the chassis `outbox`, `inbox`, `shedlock`; `device_otp` (V5, ticket 68). Code so far: `wallet`
  (`repository/WalletRepository`), `device` (`repository/DeviceRepository`), `device_otp`
  (`repository/DeviceOtpRepository`).
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
  tokenise MSISDN, envelope encrypt / decrypt. mw-crypto's `mw.crypto.authz` already allows `mw-auth`
  for `TOKENISE_MSISDN`, `KEK_COLUMN`, PIN and `JWT_SIGN` / `ASSERTION_SIGN`; nothing logs a value.
- Ports (`config/PublicConnectorConfig`, `config/SecurityConfig`, `security/InternalCallerPolicy`):
  `server.port` 8443 = the internal API `/internal/v1/**`, the same server setup as mw-crypto
  (`server.ssl` on bundle `mw-service`, `client-auth: need`, trust = the `pki_int` intermediate only;
  renewals reload without a restart); 8080 (`mw.auth.public-port`) = an added plain connector for
  `/auth/v1/**` behind APISIX (tickets 68-69); 8081 = actuator, plain HTTP, never published (Dockerfile
  and compose healthchecks). `/internal/**` is its own filter chain: Keycloak JWT (signature, expiry,
  exact issuer, `aud` `mw-auth`), `azp` in `mw.internal-auth.allowed-clients` (mw-app-gateway, mw-ussd,
  mw-checkout, mw-admin-api), `azp` == client certificate CN, and never on the public port. 401
  `MW-AUTH-001`, 403 `MW-AUTH-002` with WARN `security.internal_call_denied` (reasons `PUBLIC_PORT`,
  `CLIENT_NOT_ALLOWED`, `NO_CLIENT_CERT`, `CERT_MISMATCH`). `/auth/v1/**` needs the app token (below);
  everything else is open. `mw.tls.enabled` defaults to true here (tests off).
- Device binding, the binding OTP and sessions (spec 9.1, 18.6, 18.7; built in mw-app-gateway by ticket 29 and
  review A5, moved here by ticket 68 with the same behaviour and codes; `controller/DeviceController`,
  `service/DeviceBindingService`, `service/DeviceOtpService`, `service/RequestSignatures`, `security/SignedRequests`,
  `repository/DeviceSessionStore`, `mapper/DeviceNotificationMapper`, `util/OtpCodes`, `config/DeviceBindingProperties`
  `mw.auth.device-binding.*`). Public API (contract `api/mw-auth/auth.v1.openapi.yaml`, APISIX route `auth-v1`, no
  rewrite): `POST /auth/v1/otp` (`X-Device-Id`, unsigned, optional `{locale}`) -> 202 `{wallet (masked),
  otpExpiresAt, sendsLeft}`, the 6-digit OTP by SMS (`mw.notify.request.v1`, template `DEVICE_BIND_OTP`, priority OTP,
  straight to Kafka, never at rest in clear; Kafka down `503 MW-SYS-010`; hash only in `device_otp`, 3 checks reserved
  atomically, `max-sends` 5 per 15 min (dev compose `MW_AUTH_DEVICE_OTP_MAX_SENDS` 20) else `429 MW-VAL-022`);
  `POST /auth/v1/devices {publicKey, otp}` signed with the key being registered (proof of possession), then the OTP
  (`MW-AUTH-010/011/012`), then in one transaction: OTP spent, the wallet's other bound device unbound
  (`unbound_at`), the device id bound to this wallet (a device id bound to another wallet moves here and that
  session ends), and the "new device bound" SMS (`DEVICE_BOUND`, TXN) in the outbox; the old sessions are closed
  and a new one opened; `POST /auth/v1/sessions` (signed, empty body) opens a new session on the wallet's bound
  device (`MW-AUTH-003` otherwise). Signed requests: timestamp within `clock-skew` 5 min (`MW-AUTH-005`), signature
  (`MW-AUTH-004`), nonce once per device (`MW-AUTH-006`). One bound device per wallet is no longer a switch: the
  `auth.device` partial unique index enforces it. The token's wallet must be in `auth.wallet` (else `403
  MW-AUTH-002`). Bodies checked against the derived schemas (`derived-schemas/auth-v1-otp-request.json`,
  `auth-v1-device-binding-request.json`; `service/BodySchema`, `filter/BodySchemaInterceptor`,
  `filter/CachedBodyFilter`, `config/BodySchemaConfig`, as in mw-app-gateway).
- App token on `/auth/v1/**` until ticket 69 (`config/SecurityConfig` chain 2, `config/CustomerJwtProperties`
  `mw.auth.customer-jwt.*`, `security/RealmIssuerValidator`, `security/AppUserResolver`): the Keycloak realm
  `customers` token as mw-app-gateway checks it (JWKS, expiry, issuer ending in `/realms/customers`; wallet = `msisdn`
  claim, app = realm role). Its decoder is built in the chain, not a bean, so `/internal/**` keeps the services
  realm decoder.
- Valkey (`repository/DeviceSessionStore`, `config/DeviceBindingConfig` fail-fast): sessions
  `sess:{walletId}:{deviceId}` (value = the idle timeout in seconds, TTL sliding: `idle-timeout` 5 min, per channel
  `idle-timeout-by-channel`) and nonces `nonce:{deviceId}:{nonce}` (SET NX, 2x skew + 30 s; mw-app-gateway uses the
  same key for its signed submits, so a nonce is used once across both). Valkey down `503 MW-SYS-012`; not in the
  health groups. Password `spring.data.redis.password` from `secret/mw-auth`.
- Internal device lookups for mw-app-gateway's device gate (contract `api/mw-auth/auth-internal.v1.openapi.yaml`,
  `controller/InternalDeviceController`, internal mTLS port): `GET /internal/v1/devices/{deviceId}` -> `{deviceId,
  walletId, publicKey, boundAt}` of a bound device (404 `MW-AUTH-003`); `GET /internal/v1/sessions/{walletId}/{deviceId}`
  extends the live session and returns `{walletId, deviceId, idleTimeoutSeconds}` (404 `MW-AUTH-007`).
  **Session check decision (ticket 68):** the gateway asks this lookup on every app request (it caches a confirmation
  10 s and a device key 5 min), rather than a session claim in the token; ticket 69 may revisit with mw-auth's own
  tokens. There is no `device.unbound` notice: the gateway's session check sees an ended session.
- Error codes so far: `MW-AUTH-001..007`, `010..012`, `MW-VAL-001`, `MW-VAL-022`, `MW-VAL-BODY_TOO_LARGE`,
  `MW-SYS-010`, `MW-SYS-012`, `MW-SYS-050/051`; the 18.7 codes (`MW-AUTH-040..048`) come with their tickets.

## Contracts owned

- `api/mw-auth/auth.v1.openapi.yaml` (public device API, ticket 68; `contracts/ApiContractsTest`, derived body
  schemas in `src/main/resources/derived-schemas/`, `scripts/derived-schemas.sh --write` after a change).
- `api/mw-auth/auth-internal.v1.openapi.yaml` (internal API: the device lookups of ticket 68; tickets 70-71 add
  their operations). `auth.wallet-lock.v1` comes with ticket 70.

## Contracts used

- `events/mw-core-mock/core.event.v1.schema.json#WalletStateChanged` (own type `dto/WalletStateChanged`;
  `contracts/ConsumedContractsTest` reads every sample through `mapper/WalletEventMapper`).
- `api/mw-crypto/crypto-internal.v1.openapi.yaml` as a client (`/tokens`, `/envelope/encrypt`,
  `/envelope/decrypt`; own types `dto/Crypto*`).
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
  `mapper/DeviceNotificationMapperTest`, `config/DeviceBindingPropertiesTest`, `util/MsisdnTest`,
  `security/RealmIssuerValidatorTest`; `ValkeyInfrastructure`.

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
  `AUTH_MEM_LIMIT` 320M, heap `AUTH_XMX` 112m; waits for a healthy mw-crypto). `deploy/provision/services.sh`
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
