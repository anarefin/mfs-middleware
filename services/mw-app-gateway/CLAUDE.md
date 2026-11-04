# mw-app-gateway

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.app.gateway`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- App Middleware part 1 (spec 9.1, WP08): `POST /app/v1/transactions` (P2P, CASH_IN,
  CASH_OUT: `mw.app-gateway.supported-txn-types`) and `GET /app/v1/transactions/{txnId}` behind the APISIX
  route `/app/v1/*` (`deploy/apisix/routes/app-v1.json`, `openid-connect` bearer-only on realm
  `customers`).
- Auth (`config/SecurityConfig`, `security/`): bearer JWT of Keycloak realm `customers` on
  `/app/**`, verified again here (JWKS `mw.app-gateway.jwt.jwk-set-uri`, expiry, issuer must end
  with `/realms/customers`: the host part follows the URL the client used). The payer wallet is
  the `msisdn` claim (user attribute + `mw-app` client mapper in `customers-realm.json`), the
  channel follows the realm role: `customer` -> `CUSTOMER_APP`/`CUSTOMER`, `agent` ->
  `AGENT_APP`/`AGENT` (WP20; merchant not served yet; a token with both roles is the customer).
  Role per `txnType` (`mw.app-gateway.txn-type-roles`, checked at submit after the type check):
  P2P customer only, CASH_IN/CASH_OUT agent only; a type not listed is open to every served role.
  No token / bad token `401 MW-AUTH-001`; no wallet, other role or a type the role may not
  initiate `403 MW-AUTH-002`, nothing published. Other paths
  (actuator) are open inside the app network. Keycloak is reached over `data` (MVP deviation of
  18.1), not `ops`.
- Validation: `Idempotency-Key` required, 1..64 visible ASCII (`MW-VAL-001`, detail field
  `Idempotency-Key`); body by Bean Validation (`MW-VAL-001`, `amount.valueMinor` > 0, PIN
  ciphertext required); unsupported `txnType` `MW-VAL-010`; `attributes` keys allow-listed
  (`mw.app-gateway.allowed-attributes`, only `reference`), so a client can never set reserved
  keys (`duplicate`, `requestTxnId`). MSISDNs accepted as `01XNNNNNNNN`, `8801...`, `+8801...`
  (`util/Msisdn`) and forwarded only in the canonical `01XNNNNNNNN` form.
- Submit (`service/TransactionService`): mints a UUIDv7 txnId (`util/UuidV7`), maps to the
  canonical `mw.txn.request.v1` (`mapper/RequestMapper`; initiator MSISDN = token wallet; debit = token
  wallet, credit = body wallet, except CASH_OUT: debit = body (customer) wallet with the
  customer's PIN, credit = agent wallet; PIN ciphertext copied untouched, never logged), registers a waiter, then
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
  `mw-app-gateway`, retry topics + DLQ) and seeded with RECEIVED at submit. Duplicate answers are
  not projected. Only the initiator's wallet can read a row; anything else is 404 `MW-VAL-003`.
- Init (`mw-app-gateway-init`, label `mw.oneshot`, `init/init.sh`, idempotent, dev only): role
  `app_gateway_rw`, schema `app_gateway`, `secret/mw-app-gateway` (DB password), AppRole; retries
  the database step when another init races it on a fresh stack, and waits for OpenBao's
  `dev-init.json` first.

## Contracts owned

- `api/mw-app-gateway/app.v1.openapi.yaml` (app API v1). APISIX validates submit bodies with
  `deploy/apisix/schemas/app-v1-transaction-request.json`, derived from `TransactionRequest`
  (`scripts/apisix-schemas.sh --write` after changing it; `make contracts-lint` fails until
  then). The edge also answers 401/413/429 itself (`deploy/apisix/README.md`).

## Contracts used

- `events/mw-orchestrator/mw.txn.request.v1` (produced; own type `dto/TxnRequest`, validated
  against the schema in `RequestMapperTest` and `SyncReplyIT`).
- `events/mw-orchestrator/mw.txn.status.v1` (consumed; own type `dto/StatusMessage`, unknown
  fields ignored) and the orchestrator's `mw-reply-to` / duplicate-answer conventions.

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
