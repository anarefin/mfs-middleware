# mw-adapter-bank

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.adapter.bank`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Bank adapter (spec 4.1, 11.1 "Partner banks", WP16, ticket 23). Today it serves C2W /
  ADD_MONEY_BANK only: consumes `mw.partner.request.bank.v1` (`route/BankRequestRouteBuilder`,
  retry topics + DLQ), checks a `PAY` / `STATUS_QUERY` request (`mapper/PartnerCallMapper`: family
  `bank`; PAY only for txnType `ADD_MONEY_BANK` / `C2W`, amount > 0, debit party `{type:
  BANK_ACCOUNT, id: [0-9]{6,20}}`, credit party `{type: WALLET}`), asks the bank once through the
  adapter framework (`partner` package, below) to debit the account with our stable reference,
  and publishes `mw.partner.reply.v1` through the outbox (inbox-deduplicated on `requestId`).
  `service/BankDebitService` is the copy of billpay's BillPaymentService; replies, outcomes and
  safety rules are telco's/billpay's: SUCCESS (`partnerRef` = the bank's `debitRef`), PENDING
  (accepted; the outcome follows by callback), FAILED `MW-PTR-C2WBANK-<suffix>` from the
  instance's `error-map` (unlisted `UNMAPPED`), FAILED `MW-SYS-PARTNER_*` when nothing left
  (retryable), UNKNOWN `MW-TMO-PARTNER_*` (status inquiry resolves it), NOT_FOUND for a
  STATUS_QUERY only, a PAY past its `deadlineAt` refused unsent (`MW-SYS-PARTNER_REQUEST_EXPIRED`),
  FAILED `MW-VAL-INVALID_PARTNER_REQUEST` for requests that cannot be mapped. W2B, AVL, card,
  UCB ATM / IMT and CyberSource are later tickets (new instances, operations or SPI modules here).
- Stable partner reference (`service/PartnerReferenceService`, table `partner_reference` in own
  schema `bank_ref`): `BITS` + txnId hex, stored on first use, sent as the bank's
  `transactionId`; a repeated one returns the original outcome.
- The bank call runs on a virtual-thread executor (`bankCallExecutor`), never on the Kafka
  consumer thread; the offset is committed at hand-over (crash -> orchestrator deadline ->
  UNKNOWN -> TSQ).
- Partner callbacks (ticket 23, the billpay chain of ticket 22 copied; `controller/
  PartnerCallbackController`, `service/PartnerCallbackService`, `mapper/PartnerInboundMapper`, own
  type `dto/PartnerInbound`): `POST /partner/{partnerId}/callback/**`, reached from outside only
  through the APISIX route of that partner (`deploy/apisix/routes/partner-c2wbank-callback.json`:
  ip allow-list, 16 KiB body cap, 600/min per source address in Valkey; path and body forwarded
  unchanged). Steps: (0) the path's partner id must
  match `^[a-z0-9][a-z0-9-]{0,63}$` (`@Pattern` on the path variable; else 400 `MW-VAL-001`, detail
  `partnerId`), and `mapper/InboundCallMapper` builds the framework's `InboundCall` (T7); (1) the partner in the URL must have config of its own (no default-partner
  fallback) else 404; (2) the framework verifies the signature with that partner's auth scheme
  and maps the body (CALLBACK operation, returns our `reference`): not authentic 401 `MW-AUTH-001`
  (`security.partner_callback_rejected`), unreadable 400 `MW-VAL-002`; (3) the callback's
  `reference` must be one this adapter issued for that partner (`partner_reference`), giving the
  txnId, else 404 `MW-VAL-003`; (4) inbox row (consumer `mw-adapter-bank.callbacks`, key
  `inboundId` = name UUID of `<partnerId>:<reference>:<outcome>`) and a synchronous Kafka send
  (`acks=all`) of `mw.partner.inbound.v1` (family `bank`, kind `CALLBACK`, key txnId) in one DB
  transaction, then 200 `{status: ACCEPTED, inboundId, duplicate}`. Kafka down: rolled back, 503
  `MW-SYS-EVENT_NOT_WRITTEN` (retryable; the bank sends again). A duplicate or replayed callback
  (same partner, reference, outcome) is acknowledged `duplicate: true` and not published again;
  FAILED carries `errorCode` `MW-PTR-<PARTNER>-<suffix>`; an ambiguous FAILED code is published
  as PENDING (fail closed); `partnerRef` = the bank's `debitRef`, else our reference.
- Partner-call audit (S6, spec 12.2, 17): `PartnerAudit.publishing` (wired in
  `PartnerFrameworkConfiguration`) logs `partner.call.audit` and hands every masked entry to
  `messaging/PartnerAuditPublisher`, which writes an `AuditEvent` (`dto/AuditEvent`, contract
  `mw.audit.event.v1` owned by mw-audit-dw, unchanged) to the outbox in its own transaction
  (REQUIRES_NEW): action `partner.call.completed`, actor `system`/<service>, resource
  `partner/<partnerId>/<operation>`, outcome SUCCESS (partner SUCCESS/PENDING) else FAILURE,
  `after` = `partnerInstance`, `httpMethod`, `path`, `httpStatus`, `partnerOutcome`, `durationMs`
  and the `request`/`response` bodies as the framework masked them (`sensitive-fields`, scrub, 4 KB);
  raw bodies never, no `payloadRef`. Key txnId (else partnerId). A failing publisher is logged
  `partner.call.audit_failed` and never fails the call. Identical in telco, billpay and bank.
- Init (`mw-adapter-bank-init`, label `mw.oneshot`, `init/init.sh`, idempotent, dev only): role
  `adapter_bank_rw` and schema `bank_ref`, `secret/mw-adapter-bank` (DB password, never
  overwritten; `mw.partner-secrets.c2wbank.hmac-secret` synced from the generated
  `C2WBANK_HMAC_SECRET` on every run) and the AppRole `mw-adapter-bank` (configtree volume at
  `/run/mw/approle`); waits for OpenBao's `dev-init.json` and retries the database step on "tuple
  concurrently updated".

## Design decision: C2W is a normal request path with a CALLBACK, not a new inbound kind

`mw.partner.inbound.v1` has kinds CALLBACK, REMITTANCE and NPSB_CREDIT. C2W is initiated by our
customer: the orchestrator mints the txnId, sends PAY (debit the account) and the bank confirms
that request, so its confirmation is a CALLBACK of an existing transaction, exactly the billpay
chain (spec 11.2 inbound path). REMITTANCE / NPSB_CREDIT are partner-initiated money with an
adapter-minted txnId (REMIT_IN, QR_PAY_INTEROP inbound) and stay for their own tickets. So no
contract changes: only orchestrator-owned samples for the bank family (`valid-c2w-pay.json`,
`valid-c2w-callback.json`) were added.

## Adapter framework (`partner` package; copied from mw-adapter-billpay, D13/D14)

Identical to mw-adapter-telco's and mw-adapter-billpay's (see telco's CLAUDE.md for the SPI,
route templates, config store, security rules and the fail-closed rules a mapping cannot
override), copied with its tests including the ticket 20 target guard (safe operation paths,
same-origin check at call time, `Redirect.NEVER`, credential prefix `mw.partner-secrets.`,
static host allowlist). The one documented extension, billpay's `PartnerResponse.Success`
`attributes` (text values the response mapping put under `attributes`), is in all three copies
since ticket 23 (applied to telco too, so the three stay identical). A fix to the framework must
be applied to all three copies (`diff -r` of the `partner` packages, package names aside, shows
only test fixtures).

The C2W bank is the seed instance `c2wbank`, a dev seed in `application-dev.yml` (profile `dev`, D23: set by its service in `deploy/docker-compose.yml`
and the test config; the packaged `application.yml` seeds no partner, so a production start has none
until a `partner/<id>` record arrives, and the image's AOT training run needs no http override) (`mw.partners`; wire format of
the mw-simulators bank: `POST /c2wbank/v1/debits {transactionId, accountNumber, amount:"500.00"}`
(202 PENDING `{status, code: "100", debitRef}`; 200 SUCCESS and 422 FAILED are understood too),
`GET /c2wbank/v1/debits/{reference}` (200 the real outcome, 404 NOT_FOUND), callback `POST
/partner/c2wbank/callback/debits` with `{transactionId, status SUCCESS|FAILED|PENDING, code,
message, debitRef}`), template `rest-json-async-callback`, auth `hmac-sha256` both ways (secret
`mw.partner-secrets.c2wbank.hmac-secret`, OpenBao `secret/mw-adapter-bank`; dev value generated
as `C2WBANK_HMAC_SECRET`; tests use an obviously fake one), base URL `C2WBANK_BASE_URL`, host
allowlist `MW_C2WBANK_ALLOWED_HOSTS`, timeout 25 s (below the orchestrator's 30 s partner
deadline; spec 11.1 says 30 s for banks), Resilience4j instance `c2wbank` (bulkhead 100, circuit
breaker 20 s slow-call threshold), `default-partner: c2wbank`, `sensitive-fields: accountNumber:
ACCOUNT`. Error map: `4001` ACCOUNT_NOT_LINKED, `4002` DEBIT_REFUSED, `4003` INSUFFICIENT_FUNDS;
`9876` ambiguous. Never add a retry to a partner call.

Masking: party ids are `@Sensitive(PARTY_ID)`, masked by the party's `type` (`util/MaskingPolicy`
ticket 23 extension, same in mw-orchestrator and mw-app-gateway): a wallet / MSISDN id as an
MSISDN, a bank account (and any other type) last 4 only.

## Contracts owned

- None.

## Contracts used

- Machine-readable list: `contracts.yml` (checked by `make contracts-lint`, tested by
  `contracts/ProducedContractsTest` / `ConsumedContractsTest`). No APIs served or called with a contract file (the partner callback endpoint and the bank wire format have none).
- `events/mw-audit-dw/mw.audit.event.v1` out (`partner.call.completed`, `messaging/PartnerAuditPublisher`);
  own type `dto/AuditEvent`.
- `events/mw-orchestrator/mw.partner.request.v1` (family `bank`, shared payload) in; own type
  `dto/PartnerRequest`. Canonical ADD_MONEY_BANK: `partner {partnerId: c2wbank, productCode:
  C2W}`, debit party `{type: BANK_ACCOUNT, id: <account>}`, credit party `{type: WALLET}`.
- `events/mw-orchestrator/mw.partner.reply.v1` (family `bank`) out; own type `dto/PartnerReply`.
  `dto/PartnerMessagesSchemaTest` keeps both aligned with the bank samples.
- `events/mw-orchestrator/mw.partner.inbound.v1` (family `bank`, kind `CALLBACK`) out; own type
  `dto/PartnerInbound`, checked by `mapper/PartnerInboundMapperTest` and
  `controller/PartnerCallbackIT`.
- `events/mw-admin-api/mw.config.changed.v1` in (PARTNER records), as telco.

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (`security-*` in
  `gradle/libs.versions.toml`; drop once the Boot / Camel BOMs ship them); the Dockerfile runs
  `apt-get upgrade` in the runtime stage and `init/Dockerfile` runs `apk upgrade` and removes gosu. `make security M=<this module>` scans the SBOM and the
  `mfs-mw/*:local` images (HIGH/CRITICAL fail; accepted ones in `.trivyignore.yaml` with a reason and expiry).
- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3 (+ `PARTY_ID`, above).
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
- `config/MwServiceProperties`: config binding; secrets from OpenBao when `VAULT_ENABLED=true`.
- `messaging/EventPublisher`, outbox/inbox, `route/RetryTopicRouteBuilder` +
  `messaging/RetryDelayListener` (retry topics 10 s / 1 min / 10 min then DLQ; no sleeps on Kafka
  threads).
- `application.yml`: Resilience4j defaults (19.2), own schema `bank_ref` migrated by Flyway.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew test` | unit, slice and contract tests (`contracts/ProducedContractsTest`, `ConsumedContractsTest`), no Docker |
| `./gradlew integrationTest` | Testcontainers `*IT` classes (Docker needed) |
| `./gradlew conformance` | the `@Conformance` suite (Docker needed) |
| `./gradlew check` | all three + JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-adapter-bank:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-adapter-bank` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make verify-changed [BASE=main]` | the gate for the modules changed since BASE (this one when it, or a contract in its `contracts.yml`, changed) |
| `make build M=mw-adapter-bank` | `bootJar`, then this service's images (`mfs-mw/mw-adapter-bank:local`, `-init`) |
| `make up M=mw-adapter-bank` | start this service and what it depends on from `deploy/docker-compose.yml` (`TIMERS=fast` for short test timers) |
