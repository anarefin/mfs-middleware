# mw-adapter-bank

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.adapter.bank`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Bank adapter (spec 4.1, 11.1 "Partner banks", WP16, ticket 23). Today it serves C2W /
  ADD_MONEY_BANK only: consumes `mw.partner.request.bank.v1` (`messaging/BankRequestHandler`, a Spring Kafka listener,
  retry topics + DLQ), checks a `PAY` / `STATUS_QUERY` / `REVERSE` (ticket 97: partner and a positive amount, never refused for `deadlineAt`; engine rules in mw-adapter-billpay CLAUDE.md "REVERSE, health, payload store") request (`mapper/PartnerCallMapper`: family
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
  ip allow-list, 16 KiB body cap, 600/min per source address in Redis; path and body forwarded
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
  and the `request`/`response` bodies as the framework masked them (`sensitive-fields`, scrub, 4 KB
  excerpts), `payloadRef` the full masked bodies in `partner_payload` (ticket 97). Key txnId (else partnerId; a call without a txnId is audited too, ticket 97). A failing publisher is logged
  `partner.call.audit_failed` and never fails the call. Identical in telco, billpay and bank.
- Dev provisioning (`provision_mw_adapter_bank` in `deploy/provision/services.sh`, run by the one-shot `provision` on every `docker compose up`, idempotent, dev only): role
  `adapter_bank_rw` and schema `bank_ref`, `secret/mw-adapter-bank` (DB password, never
  overwritten), `secret/mw-adapter-bank/c2wbank` (field `hmac-secret`, synced from the generated
  `C2WBANK_HMAC_SECRET` on every run) and the AppRole `mw-adapter-bank` (configtree volume at
  `/run/mw/approle`).

## Design decision: C2W is a normal request path with a CALLBACK, not a new inbound kind

`mw.partner.inbound.v1` has kinds CALLBACK, REMITTANCE and NPSB_CREDIT. C2W is initiated by our
customer: the orchestrator mints the txnId, sends PAY (debit the account) and the bank confirms
that request, so its confirmation is a CALLBACK of an existing transaction, exactly the billpay
chain (spec 11.2 inbound path). REMITTANCE / NPSB_CREDIT are partner-initiated money with an
adapter-minted txnId (REMIT_IN, QR_PAY_INTEROP inbound) and stay for their own tickets. So no
contract changes: only orchestrator-owned samples for the bank family (`valid-c2w-pay.json`,
`valid-c2w-callback.json`) were added.

## Partner engine (`partner` package; ADR D28, ticket 84: the copy of mw-adapter-billpay's)

A partner is a record holding a Camel YAML DSL route built from the shipped `mw-*` Kamelets; the
engine, its guardrails and its Kamelets (with the auth options of ticket 87: basic, OAuth2 client
credentials, JWS, mTLS contexts `mw.partners.tls.contexts`) are billpay's (see mw-adapter-billpay CLAUDE.md "Partner
engine"; its packages `partner/config/`, `partner/route/`, `partner/kamelet/`, `partner/auth/` and
`partner/mapping/` (JSONata, XSLT and the JSON/XML convention of SOAP) are billpay's "Packages"). The
`partner/` trees (main and the shared tests) and `src/main/resources/kamelets` are
identical in the three adapters apart from the package: a fix goes into all three (check with `diff -r`
after normalising the package). Kafka is Spring Kafka (ADR D27), as billpay. SOAP partners (ticket 85:
`mw-soap-call`, camel-cxf PAYLOAD, XSLT stylesheets as `*.xsl` mappings or `request=json`/`response=json`, Basic
and mTLS as `mw-http-call`) are billpay's "SOAP partners".

- **Seed** `c2wbank` (`application-dev.yml`, dev profile only), an async-callback route (example:
  `deploy/partner-routes/examples/rest-async-callback.yaml`): PAY `POST /c2wbank/v1/debits
  {transactionId, accountNumber, amount:"500.00"}` (202 PENDING `{status, code: "100", debitRef}`; 200
  SUCCESS and 422 FAILED understood; `pending=true`), STATUS_QUERY `GET /c2wbank/v1/debits/{reference}`,
  hmac-sha256 both ways with the field `hmac-secret` of Vault `secret/mw-adapter-bank/c2wbank` (dev
  value `C2WBANK_HMAC_SECRET`, `deploy/provision/services.sh`; tests: the fake vault function's
  `fake-c2wbank-hmac-secret-for-tests`), base URL `C2WBANK_BASE_URL`, hosts `MW_C2WBANK_ALLOWED_HOSTS`,
  timeout 25 s (below the orchestrator's 30 s deadline), resilience4j instance `c2wbank`, error map
  `4001` ACCOUNT_NOT_LINKED, `4002` DEBIT_REFUSED, `4003` INSUFFICIENT_FUNDS, `9876` ambiguous,
  `CALLBACK.response` for the callback. mw-admin-api's dev seed `partner-seeds/dev/c2wbank.json` is a
  copy: keep them identical.
- Only the outbound call is the route; the callback still goes APISIX -> `controller/
  PartnerCallbackController` -> `PartnerGateway.inbound` (the record's `auth` and `CALLBACK.response`)
  -> `mw.partner.inbound.v1` (above).
- **Aliases** (D28): the channels send `c2wbank` itself, so `partner-aliases` is empty; any other id is
  FAILED `MW-PTR-UNKNOWN-PARTNER`, not retryable, never sent (`BankRequestRouteIT`).
- **Family** `bank`; **route status** on `mw.partner.route.status.v1` as telco (shared
  `PartnerRouteDryRunIT`); no Kamelet usage endpoint (mw-admin-api uses the status reports).
- **Batch partners over SFTP** (ticket 86, e.g. a bank's daily settlement file): billpay's
  `from: kamelet:mw-sftp-source` routes ending in `mw-recon`, publishing `mw.partner.recon.v1` (see
  mw-adapter-billpay CLAUDE.md "Batch partners over SFTP"; shared `SftpReconRouteIT`).
- Service-layer rules stay Java (`mapper/PartnerCallMapper`): PAY only for `ADD_MONEY_BANK` / `C2W`,
  debit party `BANK_ACCOUNT`, credit party `WALLET`.

## Callback replay protection (ticket 99, FRS PLT-008 / PLT-014)

A callback signed with `hmac-sha256` must carry `X-Nonce` (16-128 of `A-Z a-z 0-9 . _ -`), signed as its own
line between the timestamp and the body hash (`partner/auth/HmacSha256AuthScheme.sign(..., nonce, body)`;
an outbound request carries none and keeps the four-line string), and a timestamp within `max-skew-seconds`.
After the signature verifies, `PartnerGateway.inbound` lets the nonce through once per partner for twice
the skew plus 30 s (`partner/auth/CallbackNonces`, table `callback_nonce`, Flyway V7; PostgreSQL, the
adapters have no Redis); a reused nonce is 401 `MW-AUTH-001` like a bad signature. A partner's resend
takes a fresh nonce and is then deduplicated by reference as before (mw-simulators' `CallbackSigner` signs a
fresh UUID per send). Identical in the three adapters (telco has no callback endpoint yet). Tests
`AuthSchemesTest`, `controller/CallbackReplayIT`.

## Contracts owned

- None.

## Contracts used

- Machine-readable list: `contracts.yml` (checked by `./gradlew :contractsLint`, tested by
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
- `events/mw-admin-api/mw.config.changed.v1` in (PARTNER route records, family `bank`), as telco.
- `events/mw-adapter-billpay/mw.partner.route.status.v1` out (owner mw-adapter-billpay; LIVE / REJECTED
  of each record version, `messaging/PartnerRouteStatusPublisher`, own type `dto/PartnerRouteStatusEvent`).
- `events/mw-adapter-billpay/mw.partner.recon.v1` out (owner mw-adapter-billpay, ticket 86; the lines of
  a partner's SFTP file, `messaging/PartnerReconPublisher`, own type `dto/PartnerReconEvent`).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (written inline in
  `build.gradle.kts`; drop once the Boot / Camel BOMs ship them); the shared dev provisioning image `deploy/provision/Dockerfile` runs `apk upgrade` and removes gosu. `scripts/security.sh <this module>` scans the SBOM and the
  `mfs-mw/*:local` images (HIGH/CRITICAL fail; accepted ones in `.trivyignore.yaml` with a reason and expiry).
- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3 (+ `PARTY_ID`, above).
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
- `config/MwServiceProperties`: config binding; secrets from Vault when `VAULT_ENABLED=true`.
- Spring Kafka (ADR D27, the billpay copy): `config/KafkaConfig` (retry topics `<topic>.<group>.retry.1..3`
  10 s / 1 min / 10 min then `.dlq`), `messaging/EventPublisher`, outbox/inbox, `scheduler/OutboxRelayScheduler`,
  `partner/config/PartnerConfigKafkaListener` (replays `mw.config.changed.v1` on every start).
- `application.yml`: Resilience4j defaults (19.2), own schema `bank_ref` migrated by Flyway.

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-adapter-bank:test` | every test in one JVM: unit, slice and contract tests (`contracts/ProducedContractsTest`, `ConsumedContractsTest`), the Testcontainers `*IT` classes and the `@Conformance` suite; one PostgreSQL and one Kafka per test JVM, a fresh database per Spring context; the `*IT` and conformance classes are skipped without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-adapter-bank:build` | `test`, then the boot jar `build/libs/mw-adapter-bank.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-adapter-bank:bootJar && docker build -t mw-adapter-bank:dev services/mw-adapter-bank` | image, as spec 4.3 rule 1 |
| `./gradlew clean build` | the full gate: every module, plus the repo checks (`:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`); unchanged modules come from the build cache |
| `./gradlew :mw-adapter-bank:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-adapter-bank` | rebuild this service's image (`mfs-mw/mw-adapter-bank:local`) and restart it with what it depends on (dev timers from `deploy/.env`) |
