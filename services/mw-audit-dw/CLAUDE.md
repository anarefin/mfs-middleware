# mw-audit-dw

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.audit.dw`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Audit trail in the MVP (spec 14.3, WP10; no DW stream or SFTP export yet).
- Ticket 130 (FRS AUD-01, AUD-03, AUD-04, AUD-05; CON-04 audit side; Flyway V7):
  - **Hop facts and payloads**: `mw.audit.event.v1` gained optional `hop` (CHANNEL_IN, ORCHESTRATOR,
    CORE_CALL, PARTNER_CALL, PARTNER_CALLBACK, NOTIFICATION), `direction` (IN/OUT seen from the
    middleware), `durationMs` and `payload[]` (`{direction, contentType, body?, payloadRef?}`, at most 4,
    body <= 64 KiB). `AuditService` stores the facts on `audit.event` (`hop`, `direction`, `duration_ms`)
    and each body in `audit.payload` (append-only, monthly partitions `payload_yyyy_MM` created by
    `ensure_month_partition` with the event's, linked by `audit_id` + `ordinal`, `size` and `sha256` of
    the stored body). Every body is scrubbed again on write (`util/PayloadScrub`: a key holding a secret word (camelCase, `_`, `-` split: pin, mpin, otp, passw*, cvv, cvc, secret, ...), also inside one word after known prefixes and before known suffixes (`newpin`, `encryptedpin`, `pinciphertext`, `otpcode`), or matching the pre-ticket whole-key regex, has its whole value blanked in JSON and `key=value` text (`spinner`, `shipping` kept there); markup is never parsed (review round 4, user decision): a non-JSON body holding a `<` that contains a secret-looking word anywhere (case-insensitive substring pin, otp, passw, pwd, passcode, passphrase, cvv, cvc, secret, also with `. - _ :` taken out, or a word the key rule flags) is stored as `****` whole, look-alikes such as `<spinner>` included (fail closed), else it is kept for the steps below; a body over 1 MiB is stored as `****`;
    a Luhn-valid PAN cut to its last 4,
    then `MaskingPolicy.scrub`; `PayloadScrubTest`). Chain: a row with hop facts or payloads appends
    `{hop, direction, durationMs, payloads[{ordinal, direction, contentType, payloadRef, size, sha256}]}` to
    its canonical array (`service/HashChain`); rows without them hash as before (golden vector kept), and
    the verifier recomputes each body's SHA-256 ("payload does not match its digest"); a changed body,
    digest, hop fact or a deleted payload row breaks the chain (`HashChainWithPayloadIT`). Producers: the
    three adapters' `PartnerAuditPublisher` (PARTNER_CALL, OUT, latency, request OUT / answer IN excerpts
    with the `partner_payload` ref). The hop of an event without one is inferred from its action
    (`util/Hops`: `txn.*` ORCHESTRATOR, `partner.call.completed` PARTNER_CALL, `partner.callback.*`
    PARTNER_CALLBACK, `core.*` CORE_CALL, `notification.*`/`notify.*` NOTIFICATION).
  - **Search projection** `audit.txn_index` (txnId, channel, txnType, latest status by `updatedAt`,
    amount, partner, `msisdn_tokens`, requestedAt): `messaging/TxnStatusListener` consumes
    `mw.txn.status.v1` (retry group `mw-audit-dw`) -> `service/TxnIndexService` ->
    `repository/TxnIndexRepository` (writer: SELECT/INSERT/UPDATE on it only; reader SELECT). The parties'
    MSISDNs (normalised by `util/Msisdns`) are tokenised once per transaction at mw-crypto
    (`client/CryptoClient` as `client/MsisdnTokeniser`, `/internal/v1/tokens` TOKENISE_MSISDN, already
    bound to this service) and never stored or logged; `mw.audit-dw.query.tokenise-msisdn` (off in tests).
  - **Search filters**: `partner`, `channel`, `status`, `amountMin`/`amountMax` (minor units), `msisdn`
    (tokenised, 400 when not a mobile number, 503 `MW-SYS-002` when tokenising is off) join the projection.
    `AuditSearchFiltersIT`; the 3 s target over 30 days (60,000 txns, 240,000 events) is
    `AuditSearchPerfIT` (`slow-wait`, `-PwithSlow`; every combination answered in well under 0.5 s).
  - **Timeline** `GET /internal/v1/audit/transactions/{txnId}/timeline` (`TimelineHop.Timeline`: hops in
    order of `at` with `durationMs`, `sinceStartMs`, `gapMs` and their payloads, `totalMs`, `anchor`; an
    unknown txn has no hops; `TimelineIT`). Not yet used by mw-admin-api (open item, see below).
  - **Export** `GET /internal/v1/audit/search/export.csv` (same filters, `X-Mw-Operator-Id` required,
    at most `mw.audit-dw.query.export-max-rows` rows (10,000), header `X-Mw-Export-Truncated`; RFC 4180,
    formula cells prefixed `'`): `service/AuditExportService` first writes one operator-action record
    into the chain (action `audit.search.exported`, actor `user`/<operator>, resource `audit/search`,
    `after` = operation, filters (an MSISDN only as "given"), rowCount, truncated); `ExportAuditedIT`.
  - Test-only mirrors `/test/v1/audit/transactions/{txnId}/timeline` and `/test/v1/audit/search/export.csv`
    (API suite folder 42).
  - Open items: the channels' request logging does not send CHANNEL_IN events yet, mw-orchestrator sends
    no CORE_CALL hop and mw-notify no NOTIFICATION hop (the timeline shows what producers send); mw-admin-api's
    transaction detail does not call the timeline yet (left out while ticket 100a changes mw-admin-api;
    UI in ticket 135).
- Internal audit search (ticket 35, contract `api/mw-audit-dw/audit-query.v1.openapi.yaml`;
  `controller/AuditSearchController` -> `service/AuditSearchService` -> `repository/AuditSearchRepository`):
  `GET /internal/v1/audit/events` (txnId, actorType, actorId, action, resource, outcome, `[from, to)` on
  `at`, limit 1..500, offset; ordered by `at`, then chain order), for mw-admin-api over the `app`
  network, **no APISIX route**. Auth (M3 gate decision, `config/SecurityConfig`,
  `security/InternalCallerPolicy`, `mw.internal-auth.*`, same as mw-orchestrator's): Keycloak
  client-credentials JWT of realm `services`, exact issuer, `aud` containing `mw-audit-dw`, `azp`
  mw-admin-api only (the search has no org filter, so mw-portal-api is refused); no / bad token
  `401 MW-AUTH-001`, other client `403 MW-AUTH-002`; `InternalApiAuthIT`. Reads as `audit_reader` (`config/AuditSearchConfig`: its own lazy
  read-only pool `audit-search`, max 4, same reader credentials `mw.audit-dw.chain-verify.reader-*`;
  not a `DataSource` bean). Values come back as stored (already masked); operator usernames are not
  masked. The transaction filters and the MSISDN filter of ticket 130 are above. `AuditSearchApiIT`. Consumes `mw.audit.event.v1` (`@KafkaListener` `messaging/AuditEventListener` ->
  `service/AuditService.record`, off with `mw.audit-dw.consumers.enabled=false`) with retry topics and DLQ
  (`mw.audit.event.v1.mw-audit-dw.retry.1..3`, `.dlq`); an unreadable event or one missing a required field
  throws and ends on the DLQ (`AuditEventKafkaIT`).
- Test-only audit API (ticket 58, contract `api/mw-audit-dw/audit-dw-test.v1.openapi.yaml`,
  `controller/AuditTestController`, `service/AuditTestService`, `AuditTestControllerTest`, `AuditTestServiceTest`): only with
  `mw.audit-dw.test-api.enabled=true` (`MW_AUDIT_DW_TEST_API_ENABLED`, set by `deploy/docker-compose.yml`
  for the dev stack; off, the paths do not exist). `GET /test/v1/audit/events` = the internal search
  (same filters, same `AuditPage`) without a token; `GET /test/v1/audit/chains/{month}` (yyyy-MM, else
  400 `MW-VAL-001`) -> `service/AuditTestService.verifyChain` -> `AuditChainVerifier.verify(month)` as `audit_reader`, 200 `{month, intact, rows,
  failedSeq, reason}` whatever the outcome; 503 `MW-SYS-002` when chain verification is switched off.
  Reached only through the test-only APISIX route `test-audit-dw` (`/test/audit-dw/v1/*`, GET, key-auth
  `mw_e2e`, `MW_E2E_ROUTES=1`); `SecurityConfig` guards only `/internal/**`, so `/test/**` needs no JWT.
  The Newman suite (`tests/api`) reads a bulk batch's trail (`resource=bulk-batch/<id>`) and checks the
  month's chain with it, instead of psql.
- `audit.event`: append-only, RANGE-partitioned by month on `recorded_at` (the writer's clock,
  UTC), partitions `event_yyyy_MM` created on the month's first event by the SECURITY DEFINER
  function `ensure_month_partition` (unique `seq` index + `month` check per partition). A
  trigger refuses UPDATE/DELETE even for the owner.
- Hash chain per partition (`service/HashChain`): `seq` from 1, `prev_hash` (first row: 32 zero
  bytes), `hash` = SHA-256 over the JSON array `[month, seq, auditId, recordedAt, at, txnId,
  correlationId, actorType, actorId, action, resource, outcome, service, sourceIp, payloadRef,
  before, after, prevHashHex]` (instants ISO-8601 UTC truncated to microseconds; before/after
  in PostgreSQL's `jsonb::text` form, obtained from PostgreSQL before hashing). `HashChainTest`
  pins the format with a golden vector. `audit.chain_head` (month, last seq, last hash) is
  locked `FOR UPDATE` to append (one writer per month at a time) and anchors the tail, so a
  deleted last row is detected too. The signed daily anchor (ticket 74) is below.
- Verification: `service/AuditChainVerifier.verify(month)` -> `ChainVerification` (intact, or the
  first broken `seq` and why); `months()` lists the chains. The service role cannot read
  `audit.event`, so the verifier gets a reading role's `JdbcTemplate`.
- Scheduled verification (ticket 50): `scheduler/AuditChainVerifyJob` (ShedLock `audit-chain-verify`,
  cron `mw.audit-dw.chain-verify.cron`, hourly at :30, `-` off as in tests) -> `service/ChainVerifyService.verifyAll()`
  verifies every month as the read-only role `audit_reader` (`config/AuditReaderConfig`: a lazy
  one-connection read-only Hikari pool on the service's JDBC URL, deliberately not a `DataSource`/
  `JdbcTemplate` bean; `mw.audit-dw.chain-verify.{enabled,reader-username,reader-password}`, password
  in Vault). A broken chain or an unreadable month -> ERROR `audit.chain.broken` (month, failedSeq,
  reason) and gauge `mw.audit.chain.broken` (broken months of the last run, 0 = intact); every run logs
  `audit.chain.verified`. `AuditChainVerifyIT` tampers a row as superuser and checks the reader can only
  read and the writer still cannot SELECT.
- Signed daily anchor (ticket 74, spec 14.3 decision 2026-11-14; `service/AnchorService`,
  `scheduler/AuditAnchorJob` ShedLock `audit-anchor`, cron `mw.audit-dw.anchor.cron` UTC hourly at :15,
  `-` off as in tests). **Shape (decision, the chains are monthly, not daily):** one anchor per UTC day,
  claims `dto/AuditAnchor` `{date, heads[{partition, hash, rowCount}], prevAnchorHash}`: a head per
  monthly chain that received rows that day = its last row recorded before midnight (`partition` =
  the chain's `yyyy-MM`, `hash` = that row's hash in hex, `rowCount` = its seq), read as `audit_reader`
  (`AuditChainVerifier.heads`, index `event_recorded_at_idx`); a day without writes gets an anchor with
  no heads, so there is one anchor per day without gaps from the first day of the first chain's month;
  `prevAnchorHash` = hex SHA-256 of the previous anchor's compact JWS (64 zeros for the first). Each run
  signs every past day not yet anchored (at most `catch-up-days`, 31), oldest first, and stops at the
  first failure (ERROR `audit.anchor.failed`; the day stays due). Signing: `client/CryptoClient` ->
  mw-crypto `POST /internal/v1/jws/sign` purpose `AUDIT_ANCHOR_SIGN` (ES256, header `typ`
  `mw-audit-anchor+jws`, bound to this service in mw-crypto's `mw.crypto.authz`) over mTLS (the JDK
  client is built once on `ServiceCertificateManager.clientSslContext()`; `MW_TLS_ENABLED=true` in the
  stack) with a client-credentials JWT from `client/ServiceTokenClient` (Keycloak realm `services`
  client `mw-audit-dw`, audience mapper `aud-mw-crypto`, secret `mw.audit-dw.anchor.crypto.client-secret`
  in Vault from `KC_MW_AUDIT_DW_SECRET`; config `mw.audit-dw.anchor.crypto.*`). Stored INSERT-only in
  `audit.anchor` (V6: date PK, payload, jws, kid, anchor_hash, prev_anchor_hash; append-only trigger;
  writer SELECT/INSERT, reader SELECT) as the writer, INFO `audit.anchor.signed`. Export: written to
  `mw.audit-dw.anchor.export-dir` (`/var/lib/mw/audit-anchors`, compose volume `audit-anchors`; blank =
  off) as `<date>.jws` (temp file + atomic move, never overwritten); the WORM target stays TBC; a failed
  write is ERROR `audit.anchor.export_failed` (the anchor stays stored, and the next verification
  reports "export missing" until an operator restores the file). The job never signs a day whose
  `<date>.jws` exists: a row missing next to its file is ERROR `audit.anchor.broken` ("anchor missing,
  export exists") and status BROKEN, not a catch-up. Verification: `AuditChainVerifyJob` runs
  `AnchorService.verifyAll()` after the chains, one result per day that has or should have an anchor.
  Expected days: every UTC day from day 1 of the first chain month whose end is more than
  `mw.audit-dw.anchor.grace` (3 h: the next hourly run plus a short mw-crypto outage) ago; a missing one
  is "anchor missing" (also when every anchor is gone), or "anchor missing, export exists", and an
  exported file without its row counts too. A stored anchor (first failure wins): the stored hash =
  SHA-256 of the JWS, ES256 signature and `typ` against mw-crypto's `GET
  /internal/v1/jwks/AUDIT_ANCHOR_SIGN` by kid ("signature does not verify"), the date, the anchor chain
  ("anchor chain broken"), the heads equal to the heads recomputed from `audit.event` now ("chain heads
  differ": catches a chain a superuser rewrote and re-sealed consistently, which the hash chain alone
  cannot), then its exported `<date>.jws` byte for byte ("export missing" / "export differs": catches a
  re-signed or deleted-and-re-signed anchor). A failure is ERROR `audit.anchor.broken` (date, reason)
  and gauge `mw.audit.anchor.broken`; an unreadable JWKS or an export folder that cannot be read is WARN
  `audit.anchor.unverifiable` and status UNVERIFIED (BROKEN wins). The last result is the `anchor` field of every audit search page
  (`dto/AnchorStatus` INTACT / BROKEN / UNVERIFIED (not checked since start) / OFF (anchoring off),
  lastAnchorDate, verifiedAt), shown by mw-admin-api as `auditAnchor` of the transaction detail and by the
  admin console's audit trail card. Off with `mw.audit-dw.anchor.enabled=false` (it also needs chain verification on).
  Tests: `service/AuditAnchorIT` (stand-in `client/CryptoStub` with its own EC key: one anchor per day,
  the chain, export, a consistently rewritten chain caught, a forged and a missing anchor caught, the
  newest anchor deleted (caught, never re-signed), every anchor deleted with and without the export,
  an anchor re-signed with the real key differing from its export, an unreadable export folder, the
  grace, the writer cannot change an anchor, mw-crypto down leaves the day due), `client/CryptoClientTest` (request =
  mw-crypto contract `JwsSignRequest`, token reuse, failures).
- Producers today: mw-orchestrator (`txn.state.changed`, `partner.callback.late`,
  `txn.outcome.conflict`, `partner.callback.rejected`) and the three adapters
  (`partner.call.completed`, S6: masked partner request/response in `after`), and mw-portal-api
  (`bulk.batch.uploaded|approved|rejected|expired|paused|resumed`, actor the operator). The consumer
  accepts any action that fits the contract; `AuditTrailIT` stores a partner-call event.
- Dedup: `audit.event_key` (auditId primary key, never purged, INSERT-only for the writer) in the
  same transaction, so a redelivery is refused however late it comes (the chassis inbox is
  purged after 7 d, so it is not used for this).
- Masking (18.3): producers mask; before storing, `MaskingPolicy.scrub` runs again over
  `correlationId`, `actor.id`, `resource`, `payloadRef` and every string, number and property
  name in `before`/`after` (a number the scrub changes is stored as the masked string; property
  names that mask alike are never overwritten, later ones get `#2`, `#3`... in order). The scrub
  has no UUID exemption, so only system ids escape it: a `resource` that is exactly
  `<type>/<uuid>` (type `[a-z][a-z0-9-]*`, a lower-case RFC 9562 UUID, version 1-8, variant 8-b;
  producers build these from system ids) and a `correlationId` that is such a UUID are stored as
  they are, on shape alone (`CorrelationIdFilter` is the gate: it replaces a caller correlation id
  with a 6+ digit run or one the scrub would change, e.g. a UUID spreading an MSISDN over its groups);
  any other resource, e.g. `txn/<crafted uuid shape>` or `foo/01712345678`, is scrubbed. `txnId` is parsed as a UUID. `AuditTrailIT` pins it. The hash
  covers the masked values.
- Roles (16.4): the service runs as `audit_writer` (INSERT only on `event` and `event_key`; SELECT/INSERT/UPDATE
  on `chain_head`; EXECUTE `ensure_month_partition`; DML on the chassis `inbox`/`outbox`/
  `shedlock`; SELECT/INSERT on `anchor`). Flyway runs as the schema owner `audit_dw_rw` (`SPRING_FLYWAY_USER` +
  `spring.flyway.password` from Vault); V4 grants the writer. `audit_reader` (V5): USAGE on the
  schema and SELECT on `event` and `chain_head` only (V6: and `anchor`). Nobody holds UPDATE/DELETE on
  `event` or `anchor`. A superuser can still bypass everything, which the chain detects (`AuditTrailIT`).
- Dev provisioning (`provision_mw_audit_dw` in `deploy/provision/services.sh`, run by the one-shot `provision` on every `docker compose up`, idempotent, dev only): roles
  `audit_dw_rw`, `audit_writer` and `audit_reader`, schema `audit`, `secret/mw-audit-dw` (the three DB
  passwords, never overwritten; the reader's is `mw.audit-dw.chain-verify.reader-password`; the mw-crypto
  client secret `mw.audit-dw.anchor.crypto.client-secret` from `KC_MW_AUDIT_DW_SECRET`) and the AppRole
  `mw-audit-dw` (configtree volume). The Keycloak client is in `deploy/keycloak/realms/services-realm.json`:
  a running dev stack needs `deploy/reset.sh` to import it.

## Contracts owned

- `events/mw-audit-dw/mw.audit.event.v1.schema.json` (+ samples): own type `dto/AuditEvent`,
  checked by `AuditEventSchemaTest`. Produced by every service, consumed here.
- `api/mw-audit-dw/audit-query.v1.openapi.yaml` (internal audit search, ticket 35; `AuditPage.anchor`,
  ticket 74).
- `api/mw-audit-dw/audit-dw-test.v1.openapi.yaml` (test-only audit search and chain check, ticket 58).

## Contracts used

- Its own `mw.audit.event.v1`, `events/mw-orchestrator/mw.txn.status.v1` (search projection, ticket
  130; own type `dto/TxnStatusEvent`), and `api/mw-crypto/crypto-internal.v1.openapi.yaml` (`/jws/sign`,
  `/jwks/AUDIT_ANCHOR_SIGN`, ticket 74; `/tokens` TOKENISE_MSISDN, ticket 130).
- `contracts.yml`: the machine-readable list of what this module produces and consumes (checked
  by `./gradlew :contractsLint`, tested by `contracts/ConsumedContractsTest` (every valid sample reads
  into `dto/AuditEvent`) and `contracts/ApiContractsTest` (search responses against
  `audit-query.v1`, test API responses against `audit-dw-test.v1`); no Docker). It produces no events, so there is no `ProducedContractsTest`
  (`ConsumedContractsTest.producesNoEvents` fails if the manifest gains one).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (written inline in
  `build.gradle.kts`; drop once the Boot BOM ships them); the shared dev provisioning image `deploy/provision/Dockerfile` runs `apk upgrade` and removes gosu. `scripts/security.sh <this module>` scans the SBOM and the
  `mfs-mw/*:local` images (HIGH/CRITICAL fail; accepted ones in `.trivyignore.yaml` with a reason and expiry).
- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, unsafe or holding a
  run of 6+ digits or one the scrub would change (WARN `correlation.id.replaced`), MDC
  `correlationId`; drops a caller `traceparent` whose trace or parent id is such a value.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field. Party ids are
  `PARTY_ID` (ticket 23): masked by the party's `type` (wallet/MSISDN as an MSISDN, a bank account or any
  other type last 4 only).
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
  Add context with `log.atInfo().addKeyValue("event", "...")` or the MDC.
- `config/MwServiceProperties`: config binding; secrets from Vault when `VAULT_ENABLED=true`.
- `config/ServiceCertificateManager` + `config/TlsProperties` (ticket 66, D24; template copy): this
  service's Vault certificate (`pki_int/issue/mw-audit-dw`, role and policy from `PKI_SERVICES` in
  `deploy/provision/services.sh`), in memory, renewed at two thirds of 72 h, gauge
  `tls.certificate.expiry.seconds`. On in the stack (`MW_TLS_ENABLED=true`, ticket 74; default off, so
  tests need no Vault): `client/CryptoClient` is built once on `clientSslContext()` and sends the
  client-credentials JWT too (template `CLAUDE.md`, "Service certificate and mTLS").
  `client/MtlsClientIT` proves the caller side (Vault container `PkiTestAuthority`, a server that
  needs a client certificate, a renewal, no certificate = failed handshake).
- Kafka through Spring Kafka (ADR D27; no Camel in this module, copied from the template, ticket 79):
  `messaging/EventPublisher` sends records with the 6.1 headers through `KafkaTemplate` (idempotent,
  `acks=all`, at most 5 in flight; `publish` waits for the ack, `publishStoredAsync` sends an outbox row
  with exactly its stored headers). `config/KafkaConfig` puts every `@KafkaListener` on non-blocking retry
  topics `<topic>.<group>.retry.1..3` (10 s / 1 min / 10 min, `mw.kafka.retry.delays`) then
  `<topic>.<group>.dlq` (`messaging/RetryTopicNaming`, group = `spring.kafka.consumer.group-id`, the
  service name; declared in `deploy/kafka/topics.txt`); validation and JSON failures and records that are
  not UTF-8 go to the DLQ at once; `messaging/RetryTopicRecoverer` adds `mw-retry-attempt`,
  `mw-original-topic`, `mw-error-class`, `mw-retry-not-before`; `messaging/RetryDueTimeCap` caps a forged
  due time; `messaging/ConsumerContextInterceptor` puts the record's ids in the MDC. Listeners take
  `ConsumerRecord<String, String>` and `Acknowledgment` and call `ack.acknowledge()` after handling
  (per-record commit, AckMode `MANUAL_IMMEDIATE`); a delay pauses the partition, never sleeps on the
  consumer thread. Settings: `spring.kafka.*`, `mw.kafka.send-timeout`, `mw.kafka.listener.shutdown-timeout`
  (100 ms in tests), `mw.kafka.retry.exclude-topics`. `messaging/RetryTopicIT` (conformance) checks it all.
- `scheduler/OutboxRelayScheduler`: relays the transactional outbox (`messaging/OutboxRelay`, 6.4) every
  `mw.outbox.relay.period` after a run ends, ShedLock-guarded; off in tests (`mw.outbox.relay.enabled`).
  Tests that consume create their topics with retry topics and DLQ first (`KafkaTestTopics.createWithRetries`).
- `application.yml`: Resilience4j defaults (19.2), own schema `audit` migrated by Flyway on
  start from `db/migration` (16.4).

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-audit-dw:test` | every test in one JVM: unit, slice and contract tests (`contracts/`), the Testcontainers `*IT` classes and the `@Conformance` suite; one PostgreSQL and one Kafka per test JVM, a fresh database per Spring context; the `*IT` and conformance classes are skipped without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-audit-dw:build` | `test`, then the boot jar `build/libs/mw-audit-dw.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-audit-dw:bootJar && docker build -t mw-audit-dw:dev services/mw-audit-dw` | image, as spec 4.3 rule 1 |
| `./gradlew clean build` | the full gate: every module, plus the repo checks (`:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`); unchanged modules come from the build cache |
| `./gradlew :mw-audit-dw:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-audit-dw` | rebuild this service's image (`mfs-mw/mw-audit-dw:local`) and restart it with what it depends on (dev timers from `deploy/.env`) |
