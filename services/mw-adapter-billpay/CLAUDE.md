# mw-adapter-billpay

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.adapter.billpay`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Bill pay adapter (spec WP15 part, 7.3 rule 5, 7.4, 11): consumes `mw.partner.request.billpay.v1`
  (`route/BillpayRequestRouteBuilder`, retry topics + DLQ), checks a `PAY` / `STATUS_QUERY` request
  (`mapper/PartnerCallMapper`: family `billpay`, `partner.billerCode` and a `BILLER_ACCOUNT` credit
  party with a bill number for PAY), calls the aggregator once through the adapter framework
  (`partner` package, below) with our stable reference, and publishes `mw.partner.reply.v1` through
  the outbox (inbox-deduplicated on `requestId`). `service/BillPaymentService` is the copy of telco's
  TopupService; replies, outcomes and safety rules are telco's (see mw-adapter-telco CLAUDE.md):
  SUCCESS (`partnerRef` = aggregator's `paymentRef`), FAILED `MW-PTR-<AGGREGATOR>-<suffix>` from
  the instance's `error-map` (unlisted `UNMAPPED`), FAILED `MW-SYS-PARTNER_*` when nothing left
  (retryable), UNKNOWN `MW-TMO-PARTNER_*` (status inquiry resolves it), NOT_FOUND for a
  STATUS_QUERY only, a PAY past its `deadlineAt` refused unsent (`MW-SYS-PARTNER_REQUEST_EXPIRED`),
  FAILED `MW-VAL-INVALID_PARTNER_REQUEST` for requests that cannot be mapped.
- Stable partner reference (`service/PartnerReferenceService`, table `partner_reference` in own
  schema `billpay_ref`; mw-adapter-telco owns `partner_ref`): `UPAY` + txnId hex, stored on first
  use, sent as the aggregator's `transactionId`; a repeated one returns the original outcome.
- The aggregator call runs on a virtual-thread executor (`billpayCallExecutor`), never on the
  Kafka consumer thread; the offset is committed at hand-over (crash -> orchestrator deadline ->
  UNKNOWN -> TSQ, as telco).
- Biller catalogue (`config/BillerProperties`, `mw.billers.<code>: {name, category, partner-id}`):
  which partner instance (aggregator) serves which biller. Static seed (DESCO, DPDC, WASA, TITAS ->
  `billagg`) until mw-admin-api publishes the catalogue (spec 11.2, ticket 38). Routing and failover
  between aggregators (FR-09) are not built: one aggregator per biller.
- Internal API (ticket 21; contract `api/mw-adapter-billpay/billpay.v1.openapi.yaml`, owned here),
  `controller/BillerController` + `service/BillFetchService`: `GET /billpay/v1/billers` (catalogue
  with `partnerId`) and `POST /billpay/v1/billers/{code}/bills/fetch {billNumber}` -> `{billerCode,
  billNumber, amount {valueMinor, currency}, dueDate, billPeriod}`. The fetch is one framework call
  with operation `INQUIRY` (same instance, circuit breaker, bulkhead and time limit as payments; no
  reference, no txnId, nothing written). Errors: unknown biller 404 `MW-VAL-003`; refusal 502
  `MW-PTR-<AGGREGATOR>-<suffix>` (e.g. `BILL_NOT_FOUND`); not sent 503 `MW-SYS-PARTNER_*`; timeout,
  ambiguous code, unreadable amount 504 `MW-TMO-PARTNER_*` (both retryable). Runs on the HTTP
  request's (virtual) thread. Only client: mw-app-gateway, over the `app` network (no APISIX route,
  no published port; the zone is the access control, as for every service-to-service call in the
  MVP). See "Design decision: synchronous bill fetch" below.
- Not yet: the aggregator callback endpoint and `mw.partner.inbound.v1` (ticket 22; the copied
  framework already verifies and maps a callback, `PartnerGateway.inbound`); audit events on
  `mw.audit.event.v1` (the framework audits to the log).
- Init (`mw-adapter-billpay-init`, label `mw.oneshot`, `init/init.sh`, idempotent, dev only): role
  `adapter_billpay_rw` and schema `billpay_ref`, `secret/mw-adapter-billpay` (DB password, never
  overwritten) and the AppRole `mw-adapter-billpay` (configtree volume at `/run/mw/approle`); waits
  for OpenBao's `dev-init.json` and retries the database step on "tuple concurrently updated".

## Design decision: synchronous bill fetch (ticket 21)

The app's `POST /app/v1/billers/{code}/fetch-bill` needs an answer within the HTTP call, but the
partner topics run orchestrator -> adapter -> orchestrator and `mw.partner.reply.v1` is consumed
by mw-orchestrator only. Chosen: **mw-app-gateway calls this adapter's internal HTTP API** for the
catalogue and the fetch. Why:
- A fetch moves no money and has no saga: it needs no idempotency key, txnId, outbox or audit, and
  spec 2.1's "no direct service-to-service calls on the money path" does not apply. The payment
  itself still goes app -> Kafka -> orchestrator -> `mw.partner.request.billpay.v1` -> this adapter.
- No Kafka thread ever waits: the gateway waits on its HTTP (virtual) thread, this adapter calls
  the aggregator on its HTTP (virtual) thread.
- Module isolation holds: the dependency is a contract (`contracts/api/mw-adapter-billpay/`), not
  code; each side has its own DTOs. No contract of another owner changes.
- Rejected alternative: Kafka request/reply with the gateway's reply-waiter. It would put the
  gateway on `mw.partner.request.billpay.v1` (orchestrator-owned, produced only by the orchestrator)
  and route INQUIRY replies to a topic the orchestrator consumes (it would see replies for txnIds it
  does not know), or need two new topics and contracts plus per-instance reply routing, for a read.
- Ticket 22 (async callbacks) builds on the payment path, not on this API: the callback endpoint
  goes on this module behind the APISIX route `/partner/{partnerId}/callback/*`, verified and
  mapped by the copied framework (`PartnerGateway.inbound`, `rest-json-async-callback` template,
  PENDING outcome), deduplicated on the partner reference, published on `mw.partner.inbound.v1`.

## Adapter framework (`partner` package; copied from mw-adapter-telco, D13/D14)

Identical to mw-adapter-telco's (see its CLAUDE.md for the SPI, route templates, config store,
security rules and the fail-closed rules a mapping cannot override), copied with its tests after
the ticket 20 target-guard fix (safe operation paths, same-origin check, no redirects). One
billpay extension: `PartnerResponse.Success` carries `attributes` (text values the response
mapping put under `attributes`; empty otherwise), used by the INQUIRY mapping to return the
amount due. A fix to the framework must be applied to both copies.

The aggregator is the seed instance `billagg` in `application.yml` (`mw.partners`; wire format of
the mw-simulators aggregator: `POST /billagg/v1/payments {transactionId, billerCode, billNumber,
amount:"667.00"}`, `GET /billagg/v1/payments/{reference}`, `POST /billagg/v1/bills/fetch
{billerCode, billNumber}`), base URL `BILLAGG_BASE_URL`, host allowlist `MW_BILLAGG_ALLOWED_HOSTS`,
timeout 25 s (below the orchestrator's 30 s partner deadline; spec 11.1 says 30 s for the
aggregator), Resilience4j instance `billagg` (bulkhead 100, circuit breaker 20 s slow-call
threshold), `default-partner: billagg`. Error map: `3001` BILL_NOT_FOUND, `3002`
BILL_ALREADY_PAID, `3003` PAYMENT_REFUSED, `3004` UNKNOWN_BILLER; `9876` ambiguous. Partner
secrets would be `mw.partner-secrets.<instance>.<name>` (OpenBao `secret/mw-adapter-billpay`);
the simulator needs none. Never add a retry to a partner call.

## Contracts owned

- `api/mw-adapter-billpay/billpay.v1.openapi.yaml` (internal API; client mw-app-gateway).

## Contracts used

- `events/mw-orchestrator/mw.partner.request.v1` (family `billpay`, shared payload) in; own type
  `dto/PartnerRequest`. Canonical BILL_PAY: `partner {partnerId: <aggregator>, productCode: BILL,
  billerCode}`, credit party `{type: BILLER_ACCOUNT, id: <bill number>}`.
- `events/mw-orchestrator/mw.partner.reply.v1` (family `billpay`) out; own type `dto/PartnerReply`.
  `dto/PartnerMessagesSchemaTest` keeps both aligned with the billpay samples.
- `events/mw-admin-api/mw.config.changed.v1` in (PARTNER records), as telco.

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field (bill
  numbers as ACCOUNT, last 4 only).
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
- `config/MwServiceProperties`: config binding; secrets from OpenBao when `VAULT_ENABLED=true`.
- `messaging/EventPublisher`, outbox/inbox, `route/RetryTopicRouteBuilder` +
  `messaging/RetryDelayListener` (retry topics 10 s / 1 min / 10 min then DLQ; no sleeps on Kafka
  threads).
- `application.yml`: Resilience4j defaults (19.2), own schema `billpay_ref` migrated by Flyway.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | unit, slice and Testcontainers tests (Docker needed for `*IT`), JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-adapter-billpay:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-adapter-billpay` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make up M=mw-adapter-billpay` | run this service's `compose.yml` (platform project must be up) |
