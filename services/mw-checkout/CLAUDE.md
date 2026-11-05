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
  bytes before binding (`filter/SessionBodySchemaAdvice` -> `service/SessionBodySchema`, copied from
  mw-app-gateway: duplicate keys, trailing content, non-objects refused; `400 MW-VAL-001`; over 64 KiB
  `413 MW-VAL-BODY_TOO_LARGE`), then Bean Validation (patterns). No header `401 MW-AUTH-001`; a consumer that is not in
  `mw.checkout.merchants.<id>` (wallet, name, secret) `403 MW-AUTH-002`.
  `POST /checkout/v1/sessions {orderId, amount, successUrl, failUrl, cancelUrl, ipnUrl (https only,
  no fragment; `security/IpnUrlPolicy`: http and internal addresses `400 MW-VAL-001` field ipnUrl
  unless the host is in `mw.checkout.ipn.dev-hosts`), description?, locale bn|en (default bn)}` -> 201 `{sessionId (UUIDv7), checkoutUrl
  = page-base-url + pay token, status OPEN, expiresAt = now + session-ttl (15 min)}`; one session
  per (merchant, orderId): a repeat with the same amount returns it (200), another amount
  `409 MW-IDEM-002`. `GET /checkout/v1/sessions/{id}` -> `{status, amount, txnId, payer (masked),
  errorCode, ...}`; another merchant's session 404.
- Hosted page API (`controller/HostedPageController`, `service/CheckoutPayService`), APISIX route
  `checkout-pay` (no login; the pay token, 32 random bytes base64url, is the bearer):
  `GET /checkout/pay/v1/{token}` (merchant name, amount, status, masked wallet, `pinKey` = the core's
  PIN public key from OpenBao for RSA-OAEP SHA-256 in the page, `redirectUrl` once terminal);
  `POST .../otp {wallet}` 202 (6-digit OTP, SecureRandom, stored only as sha256(sessionId:otp), valid
  `otp-ttl` 2 min; at most `otp-max-sends` 3 per session `429 MW-VAL-022`) sent as
  `mw.notify.request.v1` (template `CHECKOUT_OTP` seeded in mw-notify, priority OTP, SMS, expiresAt
  = OTP expiry, messageId `checkout-otp:<sessionId>:<n>`) through the outbox with the session update;
  once relayed, the row loses `params.otp` within seconds (review A8: `messaging/OutboxOtpScrubber`, beside
  the relay, run by `scheduler/OutboxOtpScrubJob` on `mw.outbox.otp-scrub.cron` every 5 s, ShedLock
  `outbox-otp-scrub`; partial index `outbox_otp_unscrubbed_idx`, V6), so no OTP stays in clear for the 7 day purge;
  `POST .../confirm {otp, encryptedPin}`: no/expired OTP `401 MW-AUTH-011`, wrong `401 MW-AUTH-010`,
  after `otp-max-attempts` 3 checks `401 MW-AUTH-012` until a new OTP. Every guess first reserves one
  check atomically (`SessionRepository.reserveOtpAttempt`: `otp_attempts < max` in the UPDATE, row
  count 0 = locked), so parallel guesses never get more than 3 comparisons; the wrong guess that used
  the last check voids the OTP hash, and the claim also requires `otp_attempts <= max`. Then OPEN -> PAYING
  (conditional update, OTP spent) and MERCHANT_PAY (`mapper/CheckoutMapper`: channel WEB_CHECKOUT,
  initiator/debit the customer wallet CUSTOMER, credit the merchant wallet MERCHANT, PIN ciphertext
  untouched, idempotency key `checkout:<sessionId>`, metadata checkoutSessionId/merchantId/orderId)
  published with `mw-reply-to = instanceId`; waits `reply-budget` (25 s) on `service/ReplyWaiters`
  (`route/StatusReplyRoute`, group `mw-checkout.reply.<instanceId>`). Answer 200 `{status PAID|FAILED,
  txnId, error?, redirectUrl}` or 202 PAYING (page polls GET). Kafka down `503 MW-SYS-010` and the
  session is OPEN again. `POST .../cancel` -> CANCELLED. `GET .../return` -> 303 to the signed URL
  (409 `MW-VAL-021` while not terminal). Expired (OPEN past expiresAt, shown EXPIRED, never stored)
  `410 MW-VAL-020` on otp/confirm/cancel; not OPEN `409 MW-VAL-021`. One payment per session: a
  failed one ends it (FAILED); the merchant creates a new session.
- Outcomes (`service/SessionOutcomes`): PAYING -> PAID (COMPLETED) / FAILED (FAILED, REJECTED, with
  the error code; statuses compared only through `dto/TerminalStatus`), from the paying request or from `route/StatusProjectionRoute` (shared group
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
  (`100::/64` and the rest refused)), the merchant's current secret, headers `X-Upay-Ipn-Id` (sessionId), `X-Upay-Attempt`, `X-Upay-Timestamp` (epoch s of the
  attempt), `X-Upay-Signature` = `security/IpnSigner` (hex HMAC-SHA256 over `upay-checkout-ipn.v1\n`
  + ts + `\n` + body), one POST by `client/MerchantIpnClient` (Apache HttpClient 5, a new client per call
  whose DNS answers the URL's host with the checked addresses only, so DNS rebinding cannot move the
  connect; the name stays the Host header, the TLS SNI name and the name the certificate is verified
  against (default hostname verifier, JVM trust store bean `merchantIpnTls`); no redirects, proxy,
  cookies or retries; connect timeout, Resilience4j time limiter `merchant-ipn` 10 s). 2xx DELIVERED; else FAILED with
  `http_status`/`error` (`HTTP_<n>`, TIMEOUT, CONNECT_FAILED, TLS_FAILED, IO_ERROR, NO_SECRET, `URL_REFUSED: ...`)
  and the next row due after `service/IpnRetrySchedule` (`retry-delays` 1m,5m,30m,2h,6h; 6 attempts).
  CANCELLED and EXPIRED sessions get no IPN (no payment was made; the merchant's GET shows them). Logs: `checkout.ipn.delivered|failed|
  exhausted` with the host only. Dev stack (`compose.yml`): dev host `mw-simulators` (merchant IPN
  receiver simulator) and a compressed schedule 10s,20s,30s,1m,2m.
- Signed result (`security/ResultSigner`, `service/ResultLinks`): PAID -> successUrl, CANCELLED ->
  cancelUrl, FAILED/EXPIRED -> failUrl, query `sessionId, orderId, status, txnId?, amount, currency,
  errorCode?, ts, signature` = hex HMAC-SHA256(merchant secret, `upay-checkout-result.v1\n` + the
  fields joined by `\n`); described for merchants in the contract.
- Table `checkout.ipn_delivery` (V5): one row per IPN attempt (see above).
- Table `checkout.session` (V4): token, merchant, order, amount, URLs, status, payer, OTP hash /
  expiry / attempts / sends, txn_id, error_code, times.
- Init (`mw-checkout-init`, `init/init.sh`, dev only): role `checkout_rw`, schema `checkout`,
  `secret/mw-checkout` (DB password; `mw.checkout.merchants.merchant_demo.secret` from
  `CHECKOUT_DEMO_MERCHANT_SECRET`, the same value as the test-only APISIX consumer; the core's PIN
  public key + key id copied from `secret/mw-core-mock`), AppRole `mw-checkout`.

## Contracts owned

- `api/mw-checkout/checkout.v1.openapi.yaml` (merchant API, hosted page API, signed result, signed
  IPN: webhook `ipn`, schema `Ipn`, `IpnUrl`). The service (not the edge) validates create-session
  bodies with `src/main/resources/derived-schemas/checkout-v1-create-session.json`
  (`scripts/derived-schemas.sh --write` after changing `CreateSessionRequest`; `make contracts-lint`
  fails on drift).

## Contracts used

- `events/mw-orchestrator/mw.txn.request.v1` (produced; `dto/TxnRequest`, checked in `CheckoutMapperTest`).
- `events/mw-orchestrator/mw.txn.status.v1` (consumed; `dto/StatusMessage`) and the `mw-reply-to` convention.
- `events/mw-notify/mw.notify.request.v1` (produced; `dto/NotificationRequest`, checked in `CheckoutMapperTest`).

## Tests

- `CheckoutFlowIT` (PostgreSQL + Kafka, `messaging/OrchestratorStub`, `MutableClock`: create, OTP from
  the notify request, wrong/expired/locked OTP, pay, core rejection, late answer via projection,
  cancel, expiry, merchant auth, idempotent create, refused IPN URLs); `IpnDeliveryIT` (local merchant
  HTTP server, `MutableClock`: fail twice then 2xx on the schedule, six attempts then none, timeout,
  internal target refused); `client/MerchantIpnClientTest` (pinned address, Host kept),
  `client/MerchantIpnTlsTest` (keytool certificates: SNI and certificate checked against the name);
  `service/IpnRetryScheduleTest`, `security/IpnUrlPolicyTest` (one case per IPv6 rule),
  `security/IpnSignerTest` (openssl vector); `repository/SessionRepositoryIT`; `messaging/OutboxOtpScrubberIT`,
  `scheduler/OutboxOtpScrubJobIT` (relayed OTP gone within seconds);
  `security/ResultSignerTest` (vector from openssl); `mapper/CheckoutMapperTest` (contract schemas);
  `service/SessionBodySchemaTest`, `controller/MerchantSessionControllerTest` (schema before the service).
- e2e `@checkout` (`tests/e2e/src/test/resources/features/checkout.feature`).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs (the pay token stays out of the service's logs).
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field. OTP, pay
  token, PIN ciphertext and merchant secret are `SECRET`.
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
- `config/MwServiceProperties`: config binding; secrets from OpenBao when `VAULT_ENABLED=true`.
- `messaging/EventPublisher`: Kafka records with the 6.1 headers (+ extra headers such as
  `mw-reply-to`), idempotent `acks=all` producer (max-block 5 s, delivery timeout 10 s).
- `route/RetryTopicRouteBuilder` + `messaging/RetryDelayListener`: retry topics 10 s / 1 min / 10 min then DLQ.
- `application.yml`: Resilience4j defaults (19.2), own schema `checkout` (spec 16.2) migrated by Flyway.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | unit, slice and Testcontainers tests (Docker needed for `*IT`), JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-checkout:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-checkout` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make up M=mw-checkout` | run this service's `compose.yml` (platform project must be up) |
| `make e2e TAGS=@checkout` | session -> OTP -> pay -> signed redirect through the edge |
