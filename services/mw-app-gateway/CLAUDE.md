# mw-app-gateway

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.app.gateway`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- App Middleware part 1 (spec 9.1, WP08): `POST /app/v1/transactions` (P2P, CASH_IN, CASH_OUT, MERCHANT_PAY, ADD_MONEY_BANK,
  TOPUP and BILL_PAY, `mw.app-gateway.supported-txn-types`), `GET /app/v1/transactions/{txnId}`,
  `GET /app/v1/billers`, `POST /app/v1/billers/{code}/fetch-bill` and `GET /app/v1/merchant/collections` behind the APISIX
  route `/app/v1/*` (`deploy/apisix/routes/app-v1.json`, `openid-connect` bearer-only on realm
  `customers`).
- Auth (`config/SecurityConfig`, `security/`): bearer JWT of Keycloak realm `customers` on
  `/app/**`, verified again here (JWKS `mw.app-gateway.jwt.jwk-set-uri`, expiry, issuer must end
  with `/realms/customers`: the host part follows the URL the client used). The payer wallet is
  the `msisdn` claim (user attribute + `mw-app` client mapper in `customers-realm.json`), the
  channel follows the realm role: `customer` -> `CUSTOMER_APP`/`CUSTOMER`, `agent` ->
  `AGENT_APP`/`AGENT`, `merchant` -> `MERCHANT_APP`/`MERCHANT` (WP20, ticket 30; a token with several of
  these roles is served by the first in that order, any other role is 403).
  Role per `txnType` (`mw.app-gateway.txn-type-roles`, checked at submit after the type check):
  P2P, CASH_OUT, MERCHANT_PAY, TOPUP, BILL_PAY and ADD_MONEY_BANK customer only, CASH_IN agent only; the merchant
  initiates nothing (`AppGatewayConfigTest` checks no type lists MERCHANT) (switch here, nowhere else). Deny by
  default: a type with no entry is refused for every role; `AppGatewayConfigTest` fails when a
  txnType of the OpenAPI enum has no entry.
  No token / bad token `401 MW-AUTH-001`; no wallet, other role or a type the role may not
  initiate `403 MW-AUTH-002`, nothing published. Other paths
  (actuator) are open inside the app network. Keycloak is reached over `data` (MVP deviation of
  18.1), not `ops`.
- Device binding (ticket 29, spec 9.1/18.6; `config/DeviceBindingConfig`, `security/DeviceBindingInterceptor`
  + `@DeviceGate`, `service/DeviceBindingService`, `service/RequestSignatures`, `repository/DeviceSessionStore`,
  table `app_device` V6): after the JWT, every `/app/**` handler needs `X-Device-Id` of a device ACTIVE for the
  token's wallet (`401 MW-AUTH-003`) and a live Valkey session `sess:{sub}:{deviceId}` (sliding idle timeout
  `mw.app-gateway.device-binding.idle-timeout` 5 min, per channel `idle-timeout-by-channel`; `401 MW-AUTH-007`).
  `POST /app/v1/transactions` (`@DeviceGate(SIGNED)`) also needs `X-Timestamp` (epoch ms, strictly less than
  `clock-skew` 5 min either way, `MW-AUTH-005`), `X-Nonce` (once per device: Valkey `nonce:{deviceId}:{nonce}` SET NX,
  TTL 2x skew + 30 s, above the 2x skew acceptance window; `MW-AUTH-006`) and `X-Signature` (ECDSA P-256 SHA-256, DER or P1363, over `METHOD\nPATH\nhex(sha256(body))\n
  TIMESTAMP\nNONCE\nDEVICE-ID`; `MW-AUTH-004`). The body hashed is the received body (`filter/CachedBodyFilter`, at most 64 KiB read with
  `readNBytes(limit+1)`, a larger one `413 MW-VAL-BODY_TOO_LARGE` as at the edge): exactly the bytes the
  schema check and the controller parse; no hash is taken from a header. That is why APISIX runs no
  request-validation on this route (it forwards a validated body re-encoded). Order:
  device, timestamp, signature, nonce, session. `POST /app/v1/devices {publicKey, otp}` binds (signed with that key, OTP below,
  re-bind replaces the key) and opens a session; `POST /app/v1/sessions` (signed, empty body) opens a new one.
  `single-active-device` (true): binding replaces the wallet's other devices (REPLACED) and binding/opening
  ends their sessions. Valkey unreachable: `503 MW-SYS-012` (fail closed; not in health groups). Valkey
  password `spring.data.redis.password` synced into `secret/mw-app-gateway` by the init step. Tests not
  about it run with `device-binding.enabled=false` (test `config/application.yml`); `DeviceBindingIT` runs it
  on real PostgreSQL + Valkey (`ValkeyInfrastructure`).
- Binding OTP (review A5; `service/DeviceOtpService`, `repository/DeviceOtpRepository`, table `app_device_otp` V8,
  `mapper/DeviceNotificationMapper`, `mw.app-gateway.device-binding.otp.*`): `POST /app/v1/devices/otp`
  (`X-Device-Id`, unsigned, optional `{locale: bn|en}`) -> `202 {wallet (masked), otpExpiresAt, sendsLeft}`; a
  6-digit OTP (`util/OtpCodes`, stored only as SHA-256 of `wallet:device:otp`) goes by SMS to the token's wallet:
  `mw.notify.request.v1` template `DEVICE_BIND_OTP`, priority OTP, published straight to Kafka (never through the
  outbox, so the code is never at rest in clear; Kafka down `503 MW-SYS-010`). A new OTP replaces the wallet's
  previous one. `POST /app/v1/devices` then needs `{publicKey, otp}`; after the signature and nonce each guess
  reserves one of `max-attempts` (3) checks with a conditional UPDATE before it is compared (as mw-checkout's
  ticket 31 fix), the third wrong one voids it; a match is spent (conditional UPDATE, once, only for the device it
  was asked for, before `ttl` 2 min) in the binding's transaction, which also writes the "new device bound" SMS
  (`DEVICE_BOUND`, TXN priority) to the outbox. Errors: wrong `401 MW-AUTH-010`, none for this device / expired /
  spent `MW-AUTH-011`, checks used up `MW-AUTH-012`, more than `max-sends` (5, dev compose 20) OTPs per wallet in
  `send-window` (15 min) `429 MW-VAL-022`. Templates live in mw-notify (`V9`).
- Validation: body schema first (`service/BodySchema` via `filter/BodySchemaInterceptor`, one per endpoint,
  `config/BodySchemaConfig`; after the JWT, before the device gate; ticket 29 and review A6, APISIX validates no
  body): `derived-schemas/app-v1-transaction-request.json` (`TransactionRequest`), `app-v1-device-otp-request.json`
  (`DeviceOtpRequest`, optional: no bytes at all pass), `app-v1-device-binding-request.json` (`DeviceBindingRequest`)
  and `app-v1-fetch-bill-request.json` (`FetchBillRequest`), derived from the contract by
  `scripts/derived-schemas.sh --write` (`make contracts-lint` fails on drift; a new JSON body endpoint needs its
  schema registered in `BodySchemaConfig`; `POST /app/v1/sessions` has no body), validated
  with json-schema-validator (runtime dependency, on Boot's Jackson) on the cached bytes; duplicate keys,
  trailing content or a non-object `400 MW-VAL-001`, schema errors `400 MW-VAL-001` with dotted fields (no
  pattern/format in the schema, so no request value in a message). Then `Idempotency-Key` required, 1..64
  visible ASCII (`MW-VAL-001`, detail field `Idempotency-Key`); body by Bean Validation (`MW-VAL-001`, `amount.valueMinor` > 0, PIN
  ciphertext required); unsupported `txnType` `MW-VAL-010`; for P2P/CASH_IN/CASH_OUT/MERCHANT_PAY a
  counterparty equal to the caller's own wallet `MW-VAL-012` (the core enforces the real wallet
  types per txnType: `MW-CORE-INVALID_COUNTERPARTY`); `attributes` keys allow-listed
  (`mw.app-gateway.allowed-attributes`, only `reference`), so a client can never set reserved
  keys (`duplicate`, `requestTxnId`). MSISDNs accepted as `01XNNNNNNNN`, `8801...`, `+8801...`
  (`util/Msisdn`) and forwarded only in the canonical `01XNNNNNNNN` form.
- TOPUP (ticket 17): body `partner {partnerId: gp|robi|banglalink|teletalk, productCode:
  PREPAID|POSTPAID}` is required, `creditParty.type` absent or `MSISDN`; any other type takes no
  `partner` and only `WALLET` (`MW-VAL-001` naming `partner` / `creditParty.type`). Forwarded as
  `partner` and credit party `{type: MSISDN, id}`. An operator refusal arrives as FAILED with
  `MW-PTR-<OPERATOR>-*` and is answered 502.
- BILL_PAY (ticket 21): body `creditParty {type: BILLER_ACCOUNT (required), id: <bill number>}` and
  `partner {billerCode}` only (no partnerId/productCode: the middleware picks the aggregator);
  `MW-VAL-001` naming `partner.billerCode` / `creditParty.type` otherwise; `creditParty.id` is
  checked by its type (`dto/CounterPartyId`: bill number `[0-9A-Za-z-]{1,32}` for BILLER_ACCOUNT,
  an accepted MSISDN for every other type). The canonical partner is `{partnerId: <aggregator of
  the biller>, productCode: BILL, billerCode}` from the catalogue (`service/BillerService.partnerFor`;
  unknown biller `MW-VAL-001` on `partner.billerCode`, catalogue unavailable `503 MW-SYS-011`),
  credit party `{type: BILLER_ACCOUNT, id}`. A refusal is FAILED `MW-PTR-<AGGREGATOR>-*` -> 502.
- ADD_MONEY_BANK (ticket 23, C2W): body `debitParty {type: BANK_ACCOUNT, id: [0-9]{6,20}}`
  (required, masked last 4) and `partner {partnerId: c2wbank}` only (`TransactionRequest.BANKS`);
  `creditParty` is refused (the money goes to the caller's own wallet). `MW-VAL-001` naming
  `debitParty` / `partner.partnerId` / `creditParty` otherwise; every other type refuses
  `debitParty` and requires `creditParty` (no longer `required` in the OpenAPI schema, checked
  here). Forwarded as debit party `{type: BANK_ACCOUNT, id}`, credit party the token's wallet
  (its PIN authorises), partner `{partnerId, productCode: C2W}` (`RequestMapper.partnerOf`). A
  bank refusal is FAILED `MW-PTR-C2WBANK-*` -> 502; an outcome not known within the budget 202.
- Billers and bill fetch (ticket 21, `controller/BillerController`, `service/BillerService`,
  `client/BillPayClient`): read-only calls to mw-adapter-billpay's internal API
  (`contracts/api/mw-adapter-billpay/billpay.v1.openapi.yaml`, `mw.app-gateway.billpay.base-url`
  `http://mw-adapter-billpay:8080` over the `app` network, read timeout 28 s > the adapter's 25 s
  partner limit). `GET /app/v1/billers` -> `{billers: [{code, name, category}]}` (the aggregator
  is not shown); the catalogue is reused for `catalogue-ttl` (60 s) and the last copy kept when the
  adapter cannot be asked. `POST /app/v1/billers/{code}/fetch-bill {billNumber}` -> `{billerCode,
  billNumber, amount, dueDate, billPeriod}`; the adapter's envelope passes through with its code and
  status (404 `MW-VAL-003`, 502 `MW-PTR-*`, 503 `MW-SYS-*`, 504 `MW-TMO-*`); adapter unreachable or
  unreadable `503 MW-SYS-011` (retryable). Resilience4j instance `billpay`: retry (2 retries, these
  are idempotent reads) and circuit breaker on `BillPayUnavailableException` only; the envelope
  (`BillPayRefusedException`) is a normal answer. Any authenticated app user with a wallet may call
  them. Why HTTP and not Kafka: see "Design decision: synchronous bill fetch" in
  mw-adapter-billpay's CLAUDE.md (no money moves; no Kafka thread waits; contract, not code,
  dependency; Kafka request/reply would misuse orchestrator-owned partner topics).
- Submit (`service/TransactionService`): mints a UUIDv7 txnId (`util/UuidV7`), maps to the
  canonical `mw.txn.request.v1` (`mapper/RequestMapper`; initiator MSISDN = token wallet; debit = token
  wallet (its own PIN), credit = body wallet (walletType AGENT for CASH_OUT, MERCHANT for MERCHANT_PAY, else CUSTOMER); PIN ciphertext copied untouched, never logged), registers a waiter, then
  publishes directly (not via the outbox) with header `mw-reply-to = instanceId`. Kafka refusing
  or timing out (`max-block-ms`/`delivery-timeout-ms` 5 s/10 s) -> `503 MW-SYS-010`, retryable,
  nothing recorded. Idempotency is decided by mw-orchestrator (7.3); this service only requires
  and forwards the key.
- Sync wait (7.5): `service/ReplyWaiters` (`txnId -> future`); `route/StatusReplyRoute` consumes
  `mw.txn.status.v1` with group `mw-app-gateway.reply.<instanceId>` (latest offset, auto-commit,
  no retry), keeps records whose `mw-reply-to` is this instance and only completes futures, so
  no Kafka thread ever waits. The HTTP (virtual) thread waits at most `reply-budget` (25 s).
  A record completes a waiter only when terminal (COMPLETED/FAILED/REJECTED/REVERSED) or a
  duplicate answer (`attributes.duplicate=true`, matched on `attributes.requestTxnId`), AND its
  `idempotencyKey` and `initiator.id` equal the waiting request's. Answers: COMPLETED/REVERSED
  200 with the view; FAILED/REJECTED the error envelope with the status's code (`MW-CORE-*` 422,
  `MW-IDEM-001` 409, `MW-VAL-*` 400, `MW-TMO-*` 504) and txnId; duplicate still running or no
  answer in time `202 {txnId, status: PROCESSING}` + `Location`. A duplicate answer carries the
  STORED txnId.
- Status view (`GET`): table `app_transaction` (own schema `app_gateway`), the latest status of
  every app-channel transaction, kept by `route/StatusProjectionRoute` (shared group
  `mw-app-gateway`, retry topics + DLQ) and seeded with RECEIVED at submit. A duplicate answer is
  not projected as a status; it links the txnId minted for the repeat (`attributes.requestTxnId`)
  to the stored txnId (`app_transaction.stored_txn_id`, V5), so when the answer comes after the
  reply budget the 202's txnId still resolves: GET on it returns the stored transaction's view
  (its txnId, up to its final status), 404 for anyone else (S3). Only the initiator's wallet can read a row; anything else is 404 `MW-VAL-003`.
- MERCHANT_PAY and collections (ticket 30, WP20): the customer pays the merchant wallet named in
  `creditParty` (no partner, no fee in mw-core-mock, which checks CUSTOMER -> MERCHANT:
  `MW-CORE-INVALID_COUNTERPARTY`). `GET /app/v1/merchant/collections?limit=1..100` (default 50,
  `MW-VAL-001` otherwise; `controller/MerchantController`, `service/CollectionService`,
  `mapper/CollectionMapper`): merchant role only (`403 MW-AUTH-002`), wallet from the token, so a
  merchant only ever sees payments to its own wallet. Rows: `app_transaction.payee_id` (V7, the
  merchant wallet of a MERCHANT_PAY, set by the projection and at submit,
  `TransactionView.merchantPayee`), status COMPLETED or REVERSED only, newest first (UUIDv7 txnId
  order). Each entry `{txnId, status, amount, payer, coreRef, requestedAt, updatedAt}` with the payer's
  MSISDN masked (`0171****789`); no fee, no PIN.
- Init (`mw-app-gateway-init`, label `mw.oneshot`, `init/init.sh`, idempotent, dev only): role
  `app_gateway_rw`, schema `app_gateway`, `secret/mw-app-gateway` (DB password), AppRole; retries
  the database step when another init races it on a fresh stack, and waits for OpenBao's
  `dev-init.json` first.

## Contracts owned

- `api/mw-app-gateway/app.v1.openapi.yaml` (app API v1). This service checks every JSON request body against
  its schema in `src/main/resources/derived-schemas/` (`TransactionRequest`, `DeviceOtpRequest`,
  `DeviceBindingRequest`, `FetchBillRequest`; `scripts/derived-schemas.sh --write` after changing one;
  `make contracts-lint` fails until then). The edge answers 401/413/429 itself (`deploy/apisix/README.md`).

## Contracts used

- `api/mw-adapter-billpay/billpay.v1.openapi.yaml` (client; own types `dto/Biller`, `dto/Bill`).
- `events/mw-orchestrator/mw.txn.request.v1` (produced; own type `dto/TxnRequest`, validated
  against the schema in `RequestMapperTest` and `SyncReplyIT`).
- `events/mw-notify/mw.notify.request.v1` (produced: the binding OTP and the new device SMS; own type
  `dto/NotificationRequest`, validated against the schema in `DeviceNotificationMapperTest`).
- `events/mw-orchestrator/mw.txn.status.v1` (consumed; own type `dto/StatusMessage`, unknown
  fields ignored) and the orchestrator's `mw-reply-to` / duplicate-answer conventions.

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field. Party ids are
  `PARTY_ID` (ticket 23): masked by the party's `type` (wallet/MSISDN as an MSISDN, a bank account or any
  other type last 4 only).
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
  Add context with `log.atInfo().addKeyValue("event", "...")` or the MDC.
- `config/MwServiceProperties`: config binding; secrets from OpenBao when `VAULT_ENABLED=true`.
- `messaging/EventPublisher`: Kafka records with the 6.1 headers, idempotent `acks=all` producer.
- `route/RetryTopicRouteBuilder` + `messaging/RetryDelayListener`: subclass per consumed topic; retry
  topics 10 s / 1 min / 10 min then DLQ (19.2); per-record commit; no sleeps on Kafka threads.
- `application.yml`: Resilience4j defaults (19.2), own schema `app_gateway` migrated by Flyway on
  start from `db/migration` (16.4); spec 16.2 names no schema for this service.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | unit, slice and Testcontainers tests (Docker needed for `*IT`), JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-app-gateway:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-app-gateway` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make up M=mw-app-gateway` | run this service's `compose.yml` (platform project must be up) |
