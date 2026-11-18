# mw-adapter-telco

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.adapter.telco`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Telco adapter (spec WP14 part, 7.3 rule 5, 7.4, 11): consumes `mw.partner.request.telco.v1`,
  checks a `PAY` / `STATUS_QUERY` request (`mapper/PartnerCallMapper`), calls the partner once through
  the adapter framework (`partner` package, below) with our stable reference, and publishes
  `mw.partner.reply.v1` through the outbox (inbox-deduplicated on `requestId`).
- Outcomes (`mapper/PartnerReplyMapper`): SUCCESS (`partnerRef` = partner ref, `attributes.partnerTxnRef`
  = our reference); FAILED with `MW-PTR-<PARTNER>-<suffix>` (partner's `error-map`, unlisted -> `UNMAPPED`);
  FAILED `MW-SYS-PARTNER_{UNAVAILABLE,CIRCUIT_OPEN,BUSY,DISABLED,MISCONFIGURED}` when the call never left
  (retryable); UNKNOWN `MW-TMO-PARTNER_{TIMEOUT,CONNECTION_RESET,HTTP_5XX,MALFORMED_REPLY,UNEXPECTED_STATUS,AMBIGUOUS_CODE}`
  (never retryable by itself: status inquiry resolves it); PENDING for an async-callback partner's ack.
  For the OCS only 422 with a code and 400 (`HTTP_400`) are definite rejections; 408/409/429, other 4xx and
  1xx/3xx are UNKNOWN (fail closed: a rejection releases the hold). FAILED `MW-VAL-INVALID_PARTNER_REQUEST`
  for requests that cannot be mapped (wrong family/operation, amount <= 0, credit party not an MSISDN).
- Stable partner reference (`service/PartnerReferenceService`, table `partner_reference` in schema
  `partner_ref`, spec 4.1): `BITS` + txnId hex, upper case, stored on first use. Every request for the same
  txnId sends the same reference; the OCS returns the original outcome for a repeated one. The
  orchestrator's `partnerTxnRef` is ignored.
- The partner call runs on a virtual-thread executor (`telcoCallExecutor` hands the request off,
  the partner route runs the HTTP call inside its circuit breaker and time limit), never on the Kafka consumer thread.
  The offset is committed when the request is handed over; a crash before the reply is written leaves
  the transaction without a reply and the orchestrator's deadline turns it UNKNOWN (TSQ, spec 7.4).
- Status inquiry (ticket 18, spec 7.4): `STATUS_QUERY` -> OCS `GET /ocs/v1/topup/{reference}` with the
  stable reference, same Resilience4j instance, one attempt. 200 SUCCESS -> SUCCESS (`partnerRef`);
  200 FAILED -> FAILED with the mapped code (ambiguous code -> UNKNOWN); 404 with the OCS'
  NOT_FOUND body -> `NOT_FOUND`; anything else, and a call that never left (circuit open, bulkhead,
  connect, disabled) -> UNKNOWN `MW-TMO-PARTNER_*`, never FAILED (a FAILED answer releases a hold the
  partner may have used). A fresh `requestId` per inquiry is the orchestrator's; the reply carries it.
  The orchestrator asks again at its next slot.
- A PAY consumed after its `deadlineAt` is not sent: FAILED `MW-SYS-PARTNER_REQUEST_EXPIRED`
  (retryable). This is what lets the orchestrator trust a later NOT_FOUND.
- Not yet: the callback HTTP endpoint and `mw.partner.inbound.v1` (ticket 22; the framework already
  verifies and maps a callback, `PartnerGateway.inbound`).
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
- Dev provisioning (`provision_mw_adapter_telco` in `deploy/provision/services.sh`, run by the one-shot `provision` on every `docker compose up`, idempotent, dev only): role
  `adapter_telco_rw` and schema `partner_ref`, `secret/mw-adapter-telco` (DB password, never
  overwritten) and the AppRole `mw-adapter-telco` (configtree volume at `/run/mw/approle`).

## Partner engine (`partner` package; ADR D26, ticket 84: the copy of mw-adapter-billpay's)

A partner is a record holding a Camel YAML DSL route built from the shipped `mw-*` Kamelets; the
engine, its guardrails and its Kamelets are billpay's (see mw-adapter-billpay CLAUDE.md "Partner
engine": record, route contract, Kamelets, loader allowlist, `routeConfiguration`, no redelivery of a
PAY, lifecycle and swap, dry-run candidate `~candidate~`, family filter, hosts and
`PartnerHttpComponent` + `PartnerDnsResolver`, headers, secrets through `SpringVaultPropertiesFunction`,
health and metrics). The `partner/` trees (main and the shared tests) and `src/main/resources/kamelets`
are identical in the three adapters apart from the package: a fix goes into all three (check with
`diff -r` after normalising the package). Kafka is Spring Kafka (ADR D25), as billpay. SOAP partners (ticket 85:
`mw-soap-call`, camel-cxf PAYLOAD, XSLT stylesheets as `*.xsl` mappings) are billpay's "SOAP partners".

- **Seed** `ocs` (`application-dev.yml`, dev profile only; the packaged `application.yml` seeds none):
  PAY `POST /ocs/v1/topup {transactionId, msisdn, amount:"20.00"}` (only 422 with a code and 400 are
  rejections; anything else non-2xx UNKNOWN), STATUS_QUERY `GET /ocs/v1/topup/{reference}` (404 with the
  NOT_FOUND body is NOT_FOUND), auth none, base URL `OCS_BASE_URL`, hosts `MW_OCS_ALLOWED_HOSTS`, timeout
  20 s, resilience4j instance `ocs` (bulkhead 100, 16 s slow-call threshold), error map `2001`
  TOPUP_REJECTED, `9876` ambiguous. mw-admin-api's dev seed `partner-seeds/dev/ocs.json` is a copy: keep
  them identical.
- **Aliases** (D26, user decision 2026-11-18): `mw.partners.partner-aliases` maps every operator id the
  channels send (`gp`, `robi`, `banglalink`, `teletalk`: mw-app-gateway, mw-ussd's operator list and MNO
  operators, the test console) to `ocs`; a partner record of its own wins over the alias; any other id is
  FAILED `MW-PTR-UNKNOWN-PARTNER`, not retryable, never sent (`TelcoRequestRouteIT`).
- **Family** `telco` (`mw.partners.family`): records of billpay or bank are skipped (`config.other_family`).
- **Route status**: every record of version >= 1 ends LIVE (with its Kamelets) or REJECTED on
  `mw.partner.route.status.v1` (`messaging/PartnerRouteStatusPublisher`, outbox), proved by the shared
  `PartnerRouteDryRunIT`. A REJECTED current version makes mw-admin-api republish the last LIVE one (D26
  amendment), so a restart replays a good record.
- **Batch partners over SFTP** (ticket 86): billpay's `from: kamelet:mw-sftp-source` routes ending in
  `mw-recon`, publishing `mw.partner.recon.v1` (see mw-adapter-billpay CLAUDE.md "Batch partners over
  SFTP"; shared `SftpReconRouteIT`).
- **No Kamelet usage endpoint** here (billpay's `GET /internal/v1/partners/kamelet-usage` is in its own
  contract): mw-admin-api builds Kamelet usage from the status reports.
- Tests specific to this module: `partner/config/PartnerConfigReloadIT` (a gp record replaces the alias
  without a restart), `PartnerConfigStartupIT` (records already on the topic when the config consumer
  starts: it ends on the latest; route records), `route/TelcoRequestRouteIT` (every outcome, the aliases,
  the unknown id, a redelivered PAY sent exactly once, no PAY resent after a timeout).

## Contracts owned

- None. (The partner topics belong to mw-orchestrator; the OCS wire format belongs to mw-simulators.)

## Contracts used

- Machine-readable list: `contracts.yml` (checked by `./gradlew :contractsLint`, tested by
  `contracts/ProducedContractsTest` / `ConsumedContractsTest`). No APIs served or called (the OCS wire
  format has no contract file).
- `events/mw-audit-dw/mw.audit.event.v1` out (`partner.call.completed`, `messaging/PartnerAuditPublisher`);
  own type `dto/AuditEvent`.
- `events/mw-orchestrator/mw.partner.request.v1` (family `telco`, shared payload) in; own type
  `dto/PartnerRequest`.
- `events/mw-orchestrator/mw.partner.reply.v1` (family `telco`, shared payload) out; own type
  `dto/PartnerReply`. `dto/PartnerMessagesSchemaTest` keeps both aligned with the samples.
- `events/mw-admin-api/mw.config.changed.v1` in (PARTNER records, `configKey` `partner/<id>`, `value` =
  a full `PartnerConfig`); own type `dto/ConfigChangedEvent`. The `value` shape is this adapter's reading
  of an open object; mw-admin-api publishes it in this shape since ticket 38 (`dto/PartnerConfigValue`
  there; its dev seed `ocs` is a copy of this module's `application-dev.yml` seed, keep them identical).
  An approved DISABLE makes the next call NOT_SENT -> FAILED `MW-SYS-PARTNER_DISABLED` without a
  restart; a changed `timeoutMs` builds a new time limiter for the next call. `PartnerConfigReloadIT`
  (record while running) and `PartnerConfigStartupIT` (records already on the topic when the config
  consumer starts: it ends on the latest version; ticket 38) cover both; the test config allows
  `robi: localhost` for the latter.
- `events/mw-adapter-billpay/mw.partner.route.status.v1` out (owner mw-adapter-billpay; LIVE / REJECTED of
  each record version, `messaging/PartnerRouteStatusPublisher`, own type `dto/PartnerRouteStatusEvent`).
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
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field. Party ids are
  `PARTY_ID` (ticket 23): masked by the party's `type` (wallet/MSISDN as an MSISDN, a bank account or any
  other type last 4 only).
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
  Add context with `log.atInfo().addKeyValue("event", "...")` or the MDC.
- `config/MwServiceProperties`: config binding; secrets from OpenBao when `VAULT_ENABLED=true`.
- Spring Kafka (ADR D25, the billpay copy): `config/KafkaConfig` (retry topics `<topic>.<group>.retry.1..3`
  10 s / 1 min / 10 min then `.dlq`), `messaging/EventPublisher`, outbox/inbox, `scheduler/OutboxRelayScheduler`,
  the request listener acknowledges after the hand-over, `partner/config/PartnerConfigKafkaListener` replays
  `mw.config.changed.v1` from the beginning on every start (no retry topics).
- `application.yml`: Resilience4j defaults (19.2), own schema `partner_ref` migrated by Flyway on
  start from `db/migration` (16.4); rename the schema to the spec 16.2 name if it has one.

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-adapter-telco:test` | every test in one JVM: unit, slice and contract tests (`contracts/ProducedContractsTest`, `ConsumedContractsTest`), the Testcontainers `*IT` classes and the `@Conformance` suite; one PostgreSQL and one Kafka per test JVM, a fresh database per Spring context; the `*IT` and conformance classes are skipped without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-adapter-telco:build` | `test`, then the boot jar `build/libs/mw-adapter-telco.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-adapter-telco:bootJar && docker build -t mw-adapter-telco:dev services/mw-adapter-telco` | image, as spec 4.3 rule 1 |
| `./gradlew clean build` | the full gate: every module, plus the repo checks (`:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`); unchanged modules come from the build cache |
| `./gradlew :mw-adapter-telco:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-adapter-telco` | rebuild this service's image (`mfs-mw/mw-adapter-telco:local`) and restart it with what it depends on (dev timers from `deploy/.env`) |
