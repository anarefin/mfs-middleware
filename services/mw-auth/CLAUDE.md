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
  plus the chassis `outbox`, `inbox`, `shedlock`. Only `wallet` has code so far (`repository/WalletRepository`).
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
  `CLIENT_NOT_ALLOWED`, `NO_CLIENT_CERT`, `CERT_MISMATCH`). Everything else is open until 68-69 add
  their own rules (mw-auth's tokens, not Keycloak's). `mw.tls.enabled` defaults to true here (tests off).
- Error codes so far: `MW-AUTH-001/002`, `MW-SYS-050/051`; the 18.7 codes (`MW-AUTH-040..048`) come with
  their tickets.

## Contracts owned

- None yet. The internal API contract (`api/mw-auth/...`) comes with its first operation (ticket 71),
  `auth.wallet-lock.v1` with ticket 70.

## Contracts used

- `events/mw-core-mock/core.event.v1.schema.json#WalletStateChanged` (own type `dto/WalletStateChanged`;
  `contracts/ConsumedContractsTest` reads every sample through `mapper/WalletEventMapper`).
- `api/mw-crypto/crypto-internal.v1.openapi.yaml` as a client (`/tokens`, `/envelope/encrypt`,
  `/envelope/decrypt`; own types `dto/Crypto*`).
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
- `contracts/ConsumedContractsTest` (no Spring, no Docker).

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
  `mw.auth.crypto.client-secret` from `KC_MW_AUTH_SECRET`), AppRole volume `auth-approle`; PKI role
  `mw-auth` from `PKI_SERVICES`. Keycloak realm `services` client `mw-auth` (audience `mw-crypto`): a
  running stack needs `deploy/reset.sh` to import it.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew :mw-auth:test` | every test in one JVM: unit, slice, contract, Testcontainers `*IT` and `@Conformance` (`*IT` skipped without Docker) |
| `./gradlew :mw-auth:build` | the tests plus the boot jar `build/libs/mw-auth.jar` |
| `./gradlew :mw-auth:bootJar && docker build -t mfs-mw/mw-auth:local services/mw-auth` | image, as spec 4.3 rule 1 |
