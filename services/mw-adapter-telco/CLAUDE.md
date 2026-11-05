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
  `partner_ref`, spec 4.1): `UPAY` + txnId hex, upper case, stored on first use. Every request for the same
  txnId sends the same reference; the OCS returns the original outcome for a repeated one. The
  orchestrator's `partnerTxnRef` is ignored.
- The partner call runs on a virtual-thread executor (`telcoCallExecutor` hands the request off,
  `partnerCallExecutor` runs the HTTP call under the time limiter), never on the Kafka consumer thread.
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
- Init (`mw-adapter-telco-init`, label `mw.oneshot`, `init/init.sh`, idempotent, dev only): role
  `adapter_telco_rw` and schema `partner_ref`, `secret/mw-adapter-telco` (DB password, never
  overwritten) and the AppRole `mw-adapter-telco` (configtree volume at `/run/mw/approle`); waits
  for OpenBao's `dev-init.json` and retries the database step when another init races it.

## Adapter framework (`partner` package, spec 12, WP11; copied, not a library: D13/D14)

Partners of a known kind are config, not code. Layout:

- `partner/` SPI: `PartnerAdapter` (`execute`, `inquire`, `onInbound`), `AuthScheme` (`apply`/`verify`),
  `PartnerCall` (canonical call: partnerId, operation, txnType, reference, `input` document),
  `PartnerResponse` (Success, Rejected, Ambiguous, NotFound, Pending, NotSent, Unknown), `PartnerContext`,
  `InboundCall`/`InboundResult`, `CredentialRef`/`CredentialResolver`, `PartnerFrameworkConfiguration`
  (Spring wiring; every other class is a plain constructor so tests assemble it without Spring).
- `partner/template/`: `PartnerRouteTemplates` (Camel Java-DSL route templates `rest-json-request-reply`
  and `rest-json-async-callback`, parameter `partner`, endpoint `direct:partner-<id>`),
  `PartnerRouteRegistrar` (one route per partner instance via `TemplatedRouteBuilder`, re-created on a
  template change, removed on delete), `PartnerGateway` (entry point: resolves partnerId -> instance,
  else `mw.partners.default-partner`, else NOT_SENT DISABLED), `PartnerCallWrapper` (bulkhead -> circuit
  breaker -> time limiter, one attempt, metrics, audit), `RestJsonAdapter` (HTTP + JSONata + fail-closed
  classification), `PartnerInstance(Factory)`, `PartnerAudit`.
- `partner/config/`: `PartnerConfig` (one instance; same field names in `mw.partners.instances.<id>` and in
  a config-changed `value`), `PartnerConfigStore` (immutable snapshot swapped atomically; newer
  `version` wins; a value that is incomplete or does not compile is rejected and the old config stays;
  seeds are version 0), `PartnerConfigRouteBuilder` + `PartnerConfigListener` (`mw.config.changed.v1`,
  plain Kafka route: own consumer group per start, from the beginning, no commits, never throws; the
  topic has no retry/DLQ topics), `PartnerProperties` (seeds).
- `partner/mapping/JsonataMapping` (com.dashjoin:jsonata), `partner/auth/` (`none`, `api-key` (header
  param, default `X-Api-Key`), `hmac-sha256` (Base64 HMAC over method, path+query, epoch-seconds
  timestamp, hex SHA-256 of body; `X-Signature`, `X-Timestamp`, optional `X-Key-Id`; 300 s skew),
  `EnvironmentCredentialResolver` (secret = Spring property named by the ref, filled from OpenBao;
  refuses anything outside `mw.partner-secrets.`)).

Security (config-changed records come from Kafka and ACLs are deferred, so they are untrusted):
- `auth.credentials-ref` is a bare secret name (`^[a-z0-9][a-z0-9-]{0,63}$`), resolved as the property
  `mw.partner-secrets.<instance>.<name>` (OpenBao `secret/mw-adapter-telco`). A partner cannot name another
  partner's secret or any other property (DB password, Vault token); the resolver re-checks the prefix.
- `mw.partners.security` is static config only (application.yml, per-environment overrides, OpenBao):
  `allowed-hosts.<instance>` (hosts its `baseUrl` may name; no entry = the partner cannot be configured)
  and `insecure-http-hosts` (plain http allowed; `MW_PARTNER_INSECURE_HTTP_HOSTS`, empty by default in
  `application.yml` (ticket 50); only this module's `compose.yml` (dev stack) sets `mw-simulators` and the
  test config allows `mw-simulators,localhost`; `PartnerSecurityDefaultsTest` guards the default, identical
  in all three adapters). Everything else needs https, no user info. A seed with an http `baseUrl` therefore
  fails start-up outside the dev stack.
  Instance ids are `^[a-z0-9][a-z0-9-]{0,63}$`. Adding a partner by config therefore needs its host on the
  static allowlist first (`MW_OCS_ALLOWED_HOSTS` for the seed).
- Operation paths must match `^/[A-Za-z0-9._~/{}-]*$` with no `..` segment and no `//` (so no `@ : ? # \ %`);
  anything else is a security refusal. At call time `RestJsonAdapter.target` re-checks that the request URI
  keeps the baseUrl's scheme, host and port, has no user info and stays under the base path, else the call
  is NOT_SENT `MISCONFIGURED` (`security.partner_target_refused`). Partner HTTP clients never follow
  redirects (`Redirect.NEVER`): a 3xx is UNKNOWN `UNEXPECTED_STATUS`.
- Seeds and config-changed records pass the same `PartnerEndpointPolicy`; a broken seed fails start-up, a
  refused record keeps the last good config and logs `security.partner_config_refused` (configKey, version,
  changedBy/approvedBy, reasons; never secret values or the refused ref). So a record can change operational
  fields (enabled, timeouts, mappings, error maps, secret names) but not leave the allowlists.

Rules the framework enforces whatever the mapping says (fail closed): 5xx -> UNKNOWN before mapping;
SUCCESS/PENDING only on a 2xx (PENDING only for the async template, SUCCESS needs a partnerRef); FAILED
only with a code and on an operation's `definite-statuses`; NOT_FOUND only for `STATUS_QUERY` and on a
definite status; an `ambiguous-codes` code -> AMBIGUOUS; anything else -> UNKNOWN. A refused/timed-out
connect, an open circuit, a full bulkhead, a disabled partner, a missing operation/credential or a failing
request mapping -> NOT_SENT (nothing left). Only exceptions count towards the circuit.

Response mapping contract: input `{status, body}` (`body` null when not JSON), output `{outcome, partnerRef,
code, message, reason}`. Request mapping input: `PartnerCall.input` + `reference`, `operation`, `txnId`.
`{reference}` in an operation path is replaced (URL-encoded). Headers sent: static `headers`,
`X-Correlation-Id`, the auth scheme's.

Wrapper output: metrics `mw_partner_call_total`, `mw_partner_call_duration_seconds` (histogram; labels
`partner`, `txn_type`, `operation`, `status`), `mw_partner_circuit_state` (0 closed, 1 open, 2 half-open);
one `partner.call.audit` log event per call with the raw request/response masked (`sensitive-fields` by
rule, then free-text scrubbing; headers never), cut at 4 KB, and the same entry as a
`partner.call.completed` audit event on `mw.audit.event.v1` (above).

The OCS is the seed instance `ocs`, a dev seed in `application-dev.yml` (profile `dev`, D23: set by this module's `compose.yml`
and the test config; the packaged `application.yml` seeds no partner, so a production start has none
until a `partner/<id>` record arrives, and the image's AOT training run needs no http override) (`mw.partners`, base URL from `OCS_BASE_URL`,
timeout 20 s, Resilience4j instance `ocs`: bulkhead 100, circuit breaker with 16 s slow-call threshold)
and `default-partner: ocs`, so gp/robi/banglalink/teletalk are all served by it until they get their own
`partner/<id>` record. Never add a retry to a partner call.

Copies live in mw-adapter-billpay (ticket 21) and mw-adapter-bank (ticket 23). Billpay's one extension,
`PartnerResponse.Success.attributes` (text values a response mapping puts under `attributes`, empty
otherwise; `RestJsonTemplateTest.aSuccessCarriesTheMappedAttributesAsText`), was applied here too in
ticket 23, so the three `partner` packages are identical apart from package names; a framework fix goes
into all three.

Copying it into another adapter (tickets 21, 23): copy `partner/` with its tests
(`src/test/.../partner/`, including the `PartnerFramework` harness and the security tests), rename the
package, add `jsonata` to the module's version catalog and build, copy the `mw.partners` block shape into
`application.yml` (security, default partner) and its seed partners into `application-dev.yml` (dev
profile only; the test config activates it), `security.allowed-hosts` per instance (and
`insecure-http-hosts` for its simulator only) and Resilience4j instances, keep secrets under
`mw.partner-secrets.<instance>.<name>` in OpenBao, and in the test config set
`mw.partners.config-consumer.enabled: false` and allow localhost. Keep operation paths plain (no query
strings: put parameters in the body or a `{reference}` segment). Never relax the path rule, the target
guard or `Redirect.NEVER`. The service layer stays the adapter's own (request checks, reference, reply
mapping, inbox/outbox).

## Contracts owned

- None. (The partner topics belong to mw-orchestrator; the OCS wire format belongs to mw-simulators.)

## Contracts used

- Machine-readable list: `contracts.yml` (checked by `make contracts-lint`, tested by
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

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (`security-*` in
  `gradle/libs.versions.toml`; drop once the Boot / Camel BOMs ship them); the Dockerfile runs
  `apt-get upgrade` in the runtime stage and `init/Dockerfile` runs `apk upgrade` and removes gosu. `make security M=<this module>` scans the SBOM and the
  `:dev` images (HIGH/CRITICAL fail; accepted ones in `.trivyignore.yaml` with a reason and expiry).
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
- `application.yml`: Resilience4j defaults (19.2), own schema `partner_ref` migrated by Flyway on
  start from `db/migration` (16.4); rename the schema to the spec 16.2 name if it has one.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew test` | unit, slice and contract tests (`contracts/ProducedContractsTest`, `ConsumedContractsTest`), no Docker |
| `./gradlew integrationTest` | Testcontainers `*IT` classes (Docker needed) |
| `./gradlew conformance` | the `@Conformance` suite (Docker needed) |
| `./gradlew check` | all three + JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-adapter-telco:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-adapter-telco` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make verify-changed [BASE=main]` | the gate for the modules changed since BASE (this one when it, or a contract in its `contracts.yml`, changed) |
| `make up M=mw-adapter-telco` | run this service's `compose.yml` (platform project must be up) |
