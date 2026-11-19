# mw-checkout

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.checkout`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Web checkout (spec 9.3, WP21 part, ticket 31): merchant sessions, the hosted page API, one
  MERCHANT_PAY per session, the signed redirect, the signed IPN with retries (ticket 32). Not
  built yet: refunds (`POST /checkout/v1/refunds`), the page front-end (open point 16).
- Merchant API (`controller/MerchantSessionController`, `service/MerchantSessionService`) behind the
  APISIX routes `checkout-v1-sessions` (POST, hmac-auth with body Digest, 16 KiB cap) and
  `checkout-v1-session-get` (GET, hmac-auth); APISIX forwards the consumer name in `X-Mw-Merchant-Id`
  (`deploy/apisix/README.md`). The create body is checked against the derived schema on the exact
  bytes before binding (`filter/BodySchemaAdvice` -> `service/BodySchema`, copied from
  mw-app-gateway: duplicate keys, trailing content, non-objects refused; `400 MW-VAL-001`; over 64 KiB
  `413 MW-VAL-BODY_TOO_LARGE`), then Bean Validation (patterns). The same advice checks the hosted
  page bodies (`OtpRequest`, `ConfirmRequest`; `cancel` has no body); a new body type needs its
  derived schema added to the advice. No header `401 MW-AUTH-001`; a consumer that is not in
  `mw.checkout.merchants.<id>` (wallet, name, secret) `403 MW-AUTH-002`.
  `POST /checkout/v1/sessions {orderId, amount, successUrl, failUrl, cancelUrl, ipnUrl (https only,
  no fragment; `security/IpnUrlPolicy`: http and internal addresses `400 MW-VAL-001` field ipnUrl
  unless the host is in `mw.checkout.ipn.dev-hosts`), description?, locale bn|en (default bn)}` -> 201 `{sessionId (UUIDv7), checkoutUrl
  = page-base-url + pay token, status OPEN, expiresAt = now + session-ttl (15 min)}`; one session
  per (merchant, orderId): a repeat with the same amount returns it (200), another amount
  `409 MW-IDEM-002`. `GET /checkout/v1/sessions/{id}` -> `{status, amount, txnId, payer (masked),
  errorCode, ...}`; another merchant's session 404.
- Replay protection (ticket 99, FRS PLT-008; `security/MerchantReplayInterceptor` registered by
  `config/ReplayProtectionConfig` on `POST /checkout/v1/sessions`, `service/RequestReplayGuard`,
  `repository/MerchantNonceRepository`, table `checkout.merchant_nonce` V8): the signed `Date` must be less
  than 5 min away (`401 MW-AUTH-005`) and the signed `X-Nonce` (16-128 of `A-Z a-z 0-9 . _ -`) unused by the
  merchant within 10.5 min (`401 MW-AUTH-006`); APISIX requires `x-nonce` among the hmac-auth signed headers.
  PostgreSQL, not Valkey/Redis (this service has none). `mw.checkout.replay-protection.enabled` (off in the
  test config); `security/HmacReplayTest`, `repository/MerchantNonceRepositoryIT`. The session GET is not checked.
- Hosted page API (`controller/HostedPageController`, `service/CheckoutPayService`), APISIX route
  `checkout-pay` (no login; the pay token, 32 random bytes base64url, is the bearer):
  `GET /checkout/pay/v1/{token}` (merchant name, amount, status, masked wallet, `pinKey {keyId,
  algorithm, publicKey}` = the mw-crypto `PIN_TRANSPORT` key from mw-auth `GET /internal/v1/pin-key`
  (the internal mTLS port with the service token; security review of ticket 72: never the plain public
  port, and a key whose `purpose` is not `PIN_TRANSPORT` is refused, `dto/ChannelPinKey`)
  (cached `pin-key-ttl` 5 min, the last copy kept while mw-auth is down; left out when none was ever
  fetched, and on a session that is no longer OPEN) for WebCrypto RSA-OAEP SHA-256 in the page,
  `redirectUrl` once terminal);
  `POST .../otp {wallet}` 202: counted on the session first (`otp_sends`, at most `otp-max-sends` 3 per
  session `429 MW-VAL-022`, payer recorded), then sent by mw-auth (ticket 72: `POST /internal/v1/otp`,
  purpose `CHECKOUT`, bound to the sessionId, template `CHECKOUT_OTP` with amount (taka) and merchant
  name; mw-auth's own rules: 2 min, 3 checks, its own slot per wallet and session (never replacing a
  device OTP), its own quota of 5 checkout OTPs per wallet in 15 min `429 MW-VAL-022`, unknown wallet `403 MW-AUTH-002`, blocked `403 MW-AUTH-043`); answer `{wallet (masked),
  otpExpiresAt, sendsLeft}` with the lower of the session's and mw-auth's sends left;
  `POST .../confirm {otp, encryptedPin}` (ticket 72): no OTP asked on the session `401 MW-AUTH-011`;
  mw-auth checks and spends the OTP (`POST /internal/v1/otp/verifications`: `401 MW-AUTH-010` wrong,
  `011` none/expired/spent, `012` checks used up); then MERCHANT_PAY is built (`mapper/CheckoutMapper`:
  channel WEB_CHECKOUT, initiator/debit the customer wallet CUSTOMER, credit the merchant wallet
  MERCHANT, idempotency key `checkout:<sessionId>`, metadata checkoutSessionId/merchantId/orderId) and
  mw-auth `POST /internal/v1/txn-authorisations` is asked with exactly its values
  (`CheckoutMapper.toAuthorisation`: msisdn, channel, the PIN ciphertext untouched, txn {txnType,
  amount = valueMinor, currency, payer = debitParty, payee = creditParty, idempotencyKey}; decision
  of ticket 71: anything else fails the core's txnHash check): wrong PIN `401 MW-AUTH-040` (details
  `triesLeft`), lock `423 MW-AUTH-041`/`042`, `403 MW-AUTH-043`/`045`/`002`, `409 MW-IDEM-001`, all
  passed through with code, status, details and Bangla text. Only then OPEN -> PAYING (conditional
  update: still OPEN, unexpired, the same payer) and the request goes out with `assertion` (no PIN
  material on Kafka), published with `mw-reply-to = instanceId`; every refusal leaves the session
  OPEN. **OTP spent decision (ticket 72):** the OTP is spent by its check, before the PIN, so a wrong
  PIN needs a new OTP (the session allows 3; mw-auth's PIN lockout counts the PINs); the OTP check
  comes first so the PIN is never tried without the second factor. No mw-auth call runs inside a
  database transaction. Waits `reply-budget` (25 s) on `service/ReplyWaiters`
  (`config/StatusReplyConsumerConfig`, group `mw-checkout.reply.<instanceId>`). Answer 200 `{status PAID|FAILED,
  txnId, error?, redirectUrl}` or 202 PAYING (page polls GET). Kafka down `503 MW-SYS-010` and the
  session is OPEN again. `POST .../cancel` -> CANCELLED. `GET .../return` -> 303 to the signed URL
  (409 `MW-VAL-021` while not terminal). Expired (OPEN past expiresAt, shown EXPIRED, never stored)
  `410 MW-VAL-020` on otp/confirm/cancel; not OPEN `409 MW-VAL-021`. One payment per session: a
  failed one ends it (FAILED); the merchant creates a new session.
- Outcomes (`service/SessionOutcomes`): PAYING -> PAID (COMPLETED) / FAILED (FAILED, REJECTED, with
  the error code; statuses compared only through `dto/TerminalStatus`), from the paying request or from `messaging/StatusProjectionListener` (shared group
  `mw-checkout` on `mw.txn.status.v1`, channel WEB_CHECKOUT only, retry topics + DLQ) for late answers;
  first one wins. Every `service/SessionOutcomeListener` bean is called in that same transaction
  with the finished session (never calls out over the network).
- Signed IPN (ticket 32): `service/IpnOutcomeListener` writes attempt 1 of `checkout.ipn_delivery`
  (V5, one row per attempt; body `dto/IpnPayload` via `mapper/IpnMapper`, stored as the exact signed
  text) with the outcome. `scheduler/IpnDeliveryJob` (ShedLock `checkout-ipn-delivery`, every
  `mw.checkout.ipn.poll-period`; `enabled=false` in tests) runs `service/IpnDeliveryService.deliverDue`:
  per due row, `IpnUrlPolicy.lookup` (https only; the host resolved once and every address checked: no
  loopback/private/link-local/CGNAT/unspecified/multicast IPv4, literal or resolved, unless a dev host;
  IPv6 (review A7): the IPv4 inside NAT64 `64:ff9b::/96`, 6to4 `2002::/16` and IPv4-compatible `::/96` is
  extracted and checked, NAT64 local-use `64:ff9b:1::/48` refused outright (its embedding depends on a
  local prefix length), else only global unicast `2000::/3` minus `2001:db8::/32` and `2001::/23`
  (`100::/64` and the rest refused)), the merchant's current secret, headers `X-Bits-Ipn-Id` (sessionId), `X-Bits-Attempt`, `X-Bits-Timestamp` (epoch s of the
  attempt), `X-Bits-Signature` = `security/IpnSigner` (hex HMAC-SHA256 over `bits-checkout-ipn.v1\n`
  + ts + `\n` + body), one POST by `client/MerchantIpnClient` (Apache HttpClient 5, a new client per call
  whose DNS answers the URL's host with the checked addresses only, so DNS rebinding cannot move the
  connect; the name stays the Host header, the TLS SNI name and the name the certificate is verified
  against (default hostname verifier, JVM trust store bean `merchantIpnTls`); no redirects, proxy,
  cookies or retries; connect timeout, Resilience4j time limiter `merchant-ipn` 10 s). 2xx DELIVERED; else FAILED with
  `http_status`/`error` (`HTTP_<n>`, TIMEOUT, CONNECT_FAILED, TLS_FAILED, IO_ERROR, NO_SECRET, `URL_REFUSED: ...`)
  and the next row due after `service/IpnRetrySchedule` (`retry-delays` 1m,5m,30m,2h,6h; 6 attempts).
  CANCELLED and EXPIRED sessions get no IPN (no payment was made; the merchant's GET shows them). Logs: `checkout.ipn.delivered|failed|
  exhausted` with the host only. Dev stack (`deploy/docker-compose.yml`): dev host `mw-simulators` (merchant IPN
  receiver simulator) and a compressed schedule 10s,20s,30s,1m,2m.
- Signed result (`security/ResultSigner`, `service/ResultLinks`): PAID -> successUrl, CANCELLED ->
  cancelUrl, FAILED/EXPIRED -> failUrl, query `sessionId, orderId, status, txnId?, amount, currency,
  errorCode?, ts, signature` = hex HMAC-SHA256(merchant secret, `bits-checkout-result.v1\n` + the
  fields joined by `\n`); described for merchants in the contract.
- Table `checkout.ipn_delivery` (V5): one row per IPN attempt (see above).
- Table `checkout.session` (V4): token, merchant, order, amount, URLs, status, payer, OTP sends,
  txn_id, error_code, times (V7, ticket 72, dropped the OTP hash, expiry and attempts: mw-auth's now).
- mw-auth client (ticket 72; `client/AuthClient`, `client/ServiceTokenClient` (mw-app-gateway's copy),
  `config/AuthClientProperties` `mw.checkout.auth.*`, `config/ServiceCertificateManager` + `TlsProperties`
  from the template, `mw.tls.enabled` off in the image default and tests, `MW_TLS_ENABLED=true` in
  compose; PKI role `mw-checkout`): internal API `https://mw-auth:8443` (`MW_AUTH_URL`) over mTLS with this
  service's OpenBao certificate and a Keycloak realm `services` client-credentials JWT of client
  `mw-checkout` (audience mapper `aud-mw-auth`; secret `mw.checkout.auth.client-secret` in
  `secret/mw-checkout`, synced from `KC_MW_CHECKOUT_SECRET`; on mw-auth's allow-list and its
  `mw.auth.assertion.callers`); the PIN key on the same internal port (`GET /internal/v1/pin-key`). Timeout 2 s, Resilience4j breaker `mw-auth` (count based, 4xx
  ignored). mw-auth's 4xx envelope passes through; only a 401 `MW-AUTH-001` (this service's token) drops
  the token and retries once (a wrong PIN is never sent twice); unreachable, timeout, open breaker, 5xx,
  unreadable answer: `503 MW-SYS-012` (retryable, fail closed). Nothing sent or received is logged.
- Removed in ticket 72: the core's PIN key (`mw.checkout.pin.*`, `CheckoutProperties.Pin`), the
  service's own OTP (`util/OtpCodes` keeps only the pay token; `otp-ttl`, `otp-max-attempts`,
  `notify-topic`), the CHECKOUT_OTP outbox publish and `dto/NotificationRequest` (no
  `mw.notify.request.v1` produced here any more), and with it the OTP scrub in `messaging/OutboxRelay.markSent`
  (the relay is the template's copy again; V6 stays as history) and `messaging/OutboxRelayOtpIT`.
- Dev provisioning (`provision_mw_checkout` in `deploy/provision/services.sh`, run by the one-shot `provision` on every `docker compose up`, idempotent, dev only): role `checkout_rw`, schema `checkout`,
  `secret/mw-checkout` (DB password; the three dev merchants' `mw.checkout.merchants.<id>.secret`:
  `merchant_demo` (01911000001 Karim Traders) from `CHECKOUT_DEMO_MERCHANT_SECRET`, `merchant_pharmacy`
  (01911000002 Lalbagh Pharmacy) from `CHECKOUT_PHARMACY_MERCHANT_SECRET`, `merchant_fashion` (01911000003
  Gulshan Fashion House) from `CHECKOUT_FASHION_MERCHANT_SECRET`, each the same value as its test-only
  APISIX consumer;
  `mw.checkout.auth.client-secret` from `KC_MW_CHECKOUT_SECRET`), AppRole `mw-checkout`, PKI role
  `mw-checkout`. Keycloak realm `services` client `mw-checkout` (audience `mw-auth`).

## Contracts owned

- `api/mw-checkout/checkout.v1.openapi.yaml` (merchant API, hosted page API, signed result, signed
  IPN: webhook `ipn`, schema `Ipn`, `IpnUrl`). The service (not the edge) validates request
  bodies with `src/main/resources/derived-schemas/` `checkout-v1-create-session.json`
  (`CreateSessionRequest`), `checkout-pay-v1-otp.json` (`OtpRequest`) and
  `checkout-pay-v1-confirm.json` (`ConfirmRequest`) (`scripts/derived-schemas.sh --write` after
  changing one; `./gradlew :contractsLint` fails on drift).

## Contracts used

- `contracts.yml` (module root) is the machine-readable list of the events and APIs here, checked by
  `./gradlew :contractsLint` and tested without Docker by `contracts/ProducedContractsTest` (every produced
  event built by `CheckoutMapper` validates), `contracts/ConsumedContractsTest` (every valid sample reads
  into the own DTO) and `contracts/ApiContractsTest` (checkout API responses, the IPN body, and the
  mw-auth requests and answers below).
- `events/mw-orchestrator/mw.txn.request.v1` (produced with `assertion`; `dto/TxnRequest`, checked in `CheckoutMapperTest`).
- `events/mw-orchestrator/mw.txn.status.v1` (consumed; `dto/StatusMessage`) and the `mw-reply-to` convention.
- `api/mw-auth/auth-internal.v1.openapi.yaml` (client, ticket 72: `POST /internal/v1/otp`, `POST
  /internal/v1/otp/verifications`, `POST /internal/v1/txn-authorisations`; own types `dto/AuthOtpRequest`,
  `dto/AuthOtpVerification`, `dto/OtpSent`, `dto/TxnAuthorisationRequest`, `dto/TxnAuthorisation`; `GET
  /internal/v1/pin-key`, `dto/ChannelPinKey` -> `dto/TransportKey`).

## Tests

- `CheckoutFlowIT` (PostgreSQL + Kafka, `messaging/OrchestratorStub`, `MutableClock`, mw-auth played by
  `client/FakeAuth` behind a mocked `AuthClient`: create, the OTP through mw-auth bound to the session,
  wrong OTP, pay with the assertion (the authorisation asked with exactly the published values, no PIN
  on Kafka), wrong PIN / PIN lock / mw-auth down passed through with the session left OPEN, core
  rejection, late answer via projection, cancel, expiry, merchant auth, idempotent create, refused IPN
  URLs; mw-auth's own OTP rules are tested in mw-auth `security/CheckoutOtpIT`);
  `client/AuthClientTest` (local HTTP server as mw-auth and Keycloak: pass-through, own-token retry only
  on `MW-AUTH-001`, fail closed, PIN key cache); `client/MtlsClientIT` (template copy); `IpnDeliveryIT` (local merchant
  HTTP server, `MutableClock`: fail twice then 2xx on the schedule, six attempts then none, timeout,
  internal target refused); `client/MerchantIpnClientTest` (pinned address, Host kept),
  `client/MerchantIpnTlsTest` (keytool certificates: SNI and certificate checked against the name);
  `service/IpnRetryScheduleTest`, `security/IpnUrlPolicyTest` (one case per IPv6 rule),
  `security/IpnSignerTest` (openssl vector); `repository/SessionRepositoryIT` (one claim wins, the claim
  needs the wallet the last OTP went to, send limit);
  `security/ResultSignerTest` (vector from openssl); `mapper/CheckoutMapperTest` (contract schemas);
  `service/SessionBodySchemaTest`, `controller/MerchantSessionControllerTest`,
  `controller/HostedPageControllerTest` (schema before the service).
- API suite folder `09` (`tests/api/src/09-checkout/checkout.js`).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (written inline in
  `build.gradle.kts`; drop once the Boot BOM ships them); the shared dev provisioning image `deploy/provision/Dockerfile` runs `apk upgrade` and removes gosu. `scripts/security.sh <this module>` scans the SBOM and the
  `mfs-mw/*:local` images (HIGH/CRITICAL fail; accepted ones in `.trivyignore.yaml` with a reason and expiry).
- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs (the pay token stays out of the service's logs).
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field. OTP, pay
  token, PIN ciphertext, the assertion and merchant secret are `SECRET`.
- `config/ServiceCertificateManager` + `TlsProperties` (ticket 66 template, copied in ticket 72).
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
- `config/MwServiceProperties`: config binding; secrets from OpenBao when `VAULT_ENABLED=true`.
- `messaging/EventPublisher`: Kafka records with the 6.1 headers (+ extra headers such as `mw-reply-to`)
  through Spring Kafka's `KafkaTemplate` (ADR D27), idempotent `acks=all` producer; `publish` waits at most
  `mw.kafka.send-timeout`. The admin client (asked for the cluster id by the observed template's first
  send) is bounded like the producer (`spring.kafka.admin.operation-timeout`), so Kafka down still fails fast.
- Spring Kafka (ADR D27, copied from the template, ticket 80): `config/KafkaConfig` (non-blocking retry topics
  `<topic>.mw-checkout.retry.1..3` then `<topic>.mw-checkout.dlq` for every `@KafkaListener` on the default factory,
  `messaging/RetryTopicNaming`, `RetryTopicRecoverer` (`mw-retry-*` headers, class only), `RetryDueTimeCap`,
  `ConsumerContextInterceptor` (consumer MDC), `Utf8Deserializer`; validation/schema failures to the DLQ at
  once); retry topics 10 s / 1 min / 10 min (`mw.kafka.retry.delays`); per-record manual ack; no sleeps on
  Kafka threads. Consumers: `messaging/StatusProjectionListener` (retried, group `mw-checkout`) and
  `config/StatusReplyConsumerConfig`: the per-instance reply consumer of `mw.txn.status.v1`, a container built from its own
  `statusReplyContainerFactory` (not a `@KafkaListener`, so the retry configuration never applies to it), group
  `mw-checkout.reply.<instanceId>`, `auto-offset-reset=latest`, auto-commit, no retry topic or DLQ, an error handler
  that logs the exception class and skips; `messaging/StatusReplyListener.onReply` only completes futures.
  `messaging/StatusReplyConsumerIT` runs two instances: each gets its replies, none sent before it started.
- `scheduler/OutboxRelayScheduler`: the outbox relay schedule (`mw.outbox.relay.period`, ShedLock-guarded).
- `application.yml`: Resilience4j defaults (19.2), own schema `checkout` (spec 16.2) migrated by Flyway.

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-checkout:test` | every test in one JVM: unit, slice and contract tests (`contracts/ProducedContractsTest`, `ConsumedContractsTest`, `ApiContractsTest`), the Testcontainers `*IT` classes (PostgreSQL, Kafka) and the `@Conformance` suite (outbox, inbox, HTTP, purge); one PostgreSQL and one Kafka per test JVM, a fresh database per Spring context; the `*IT` and conformance classes are skipped without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-checkout:build` | `test`, then the boot jar `build/libs/mw-checkout.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-checkout:bootJar && docker build -t mw-checkout:dev services/mw-checkout` | image, as spec 4.3 rule 1 |
| `./gradlew clean build` | the full gate: every module, plus the repo checks (`:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`); unchanged modules come from the build cache |
| `./gradlew :mw-checkout:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-checkout` | rebuild this service's image (`mfs-mw/mw-checkout:local`) and restart it with what it depends on (dev timers from `deploy/.env`) |
| `scripts/api-test.sh 09` | session -> OTP -> pay -> signed redirect through the edge |
