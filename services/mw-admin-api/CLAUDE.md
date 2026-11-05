# mw-admin-api

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.admin.api`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Operations console API (spec 15, WP23 part, ticket 36) for `mw-admin-ui` (server-side calls only)
  behind APISIX route `/admin/v1/*` (`deploy/apisix/routes/admin-v1.json`, `openid-connect`
  bearer-only on realm `ops`, 1 MiB body cap, 300 req/min per token subject). Later tickets add the
  change-request engine (37, below), partner config (38), USSD menus / SMS templates (39); 38/39
  reuse `ChangeRequestService`'s rules (a new `resourceType`, its own maker/checker role sets).
- Auth (`config/SecurityConfig`, `security/`): bearer JWT of realm `ops`, verified again here (JWKS
  `mw.admin-api.jwt.jwk-set-uri` / `MW_ADMIN_JWK_SET_URI`, issuer must end with `/realms/ops`,
  `security/RealmIssuerValidator`). **MFA is the realm's**: the `ops` browser flow requires password +
  TOTP and every user has `CONFIGURE_TOTP` (no token before enrolment); the service does not inspect
  `acr`/`amr` (Keycloak sets no OTP marker without extra LoA config). Decision: conservative and
  enough while realm `ops` is the only issuer accepted. Tokens from the console's login (ticket 40,
  `ui/mw-admin-ui`) carry the public issuer `https://localhost/realms/ops`, others
  `http://keycloak:8080/realms/ops`; both end with `/realms/ops`.
- Roles (`security/AdminRole`, the spec 15.4 names as realm roles in `realm_access.roles`; any other
  role name grants nothing): `READ_TRANSACTIONS` = viewer, ops-maker, ops-checker, ops-emergency,
  recon-maker, recon-checker, compliance-officer, auditor (not integration-*, security-admin: least
  privilege); `REVEAL_MSISDN` = compliance-officer, auditor; `READ_ONLY` = viewer, auditor. Rules in
  `SecurityConfig`, in order: any method other than GET/HEAD/OPTIONS under `/admin/` needs a role
  outside `READ_ONLY` (`AdminRole.mayWrite`; so viewer/auditor get 403 on every write path, present or
  future); the transaction GETs need a `READ_TRANSACTIONS` role; any other `/admin/**` needs some 15.4
  role. No / bad token `401 MW-AUTH-001`, role `403 MW-AUTH-002`. The service checks the read role
  again (`TransactionQueryService.requireReader`). Later tickets add their permission sets to
  `AdminRole` and their matchers to `SecurityConfig`.
- Transactions (`controller/TransactionController` -> `service/TransactionQueryService` ->
  `client/OrchestratorQueryClient`, `client/AuditQueryClient`; contract `api/mw-admin-api/admin.v1`):
  `GET /admin/v1/transactions` (txnId, msisdn in any form, partnerRef, status, txnType, `[from, to)`,
  limit 1..200, offset; passed to `txn-query.v1` without `orgId`) and `GET
  /admin/v1/transactions/{txnId}[?reveal=true]` -> `{transaction, timeline, partnerCalls, auditTrail,
  revealed}`: timeline (STATE and CORE entries) and partner calls from mw-orchestrator, audit trail
  (`?txnId=`, first `audit-trail-limit` 500) from mw-audit-dw. Unknown txn `404 MW-VAL-003`; either
  API down / non-200 / unreadable `503 MW-SYS-040` (retryable). Values are masked as the
  orchestrator masks them (MSISDN `0171****789`, accounts last 4, names initial); audit entries as
  stored (masked).
- Reveal (spec 15.4): `reveal=true` from a `REVEAL_MSISDN` role makes the client ask the orchestrator
  for `?reveal=true` (its `reveal-clients` = mw-admin-api; MSISDN party ids whole, nothing else) and,
  before the view is returned, `service/RevealAudit` commits one `mw.audit.event.v1` record to the
  outbox (`mapper/RevealAuditMapper`: action `admin.txn.msisdn.revealed`, resource
  `transaction/<txnId>`, actor `user`/`preferred_username`, `after {role, fields}`, never the number;
  key the txnId). If the audit cannot be written the request fails: no unaudited reveal. Any other
  role asking gets the masked view, `revealed: false`, no audit, and the orchestrator is never asked
  for unmasked values.
- Maker-checker (ticket 37, spec 15.3; `controller/ChangeRequestController` ->
  `service/ChangeRequestService` -> `repository/ChangeRequestRepository`, table `admin.change_request`
  V4, `entity/ChangeRequest`, `mapper/ChangeRequestMapper`): operator actions on a transaction
  (`dto/ChangeRequestBody.TxnActionType`: INQUIRE in UNKNOWN/MANUAL_REVIEW, FORCE_COMMIT /
  FORCE_RELEASE in MANUAL_REVIEW, REVERSE of a COMPLETED, unreversed, non-REVERSAL txn; status read
  from `txn-query.v1`). `POST /admin/v1/transactions/{txnId}/change-requests {action, reason}` (role
  `AdminRole.TXN_MAKER` = ops-maker) -> 201 PENDING, `expiresAt` = created + `mw.change-requests.ttl`
  (72h); one PENDING request per transaction (partial unique index, `409 MW-VAL-031`); action not
  fitting the status `409 MW-VAL-030`. `POST /admin/v1/change-requests/{id}/approve` and `/reject
  {reason}` (role `TXN_CHECKER` = ops-checker; the maker never, compared on token subject AND
  username: `403 MW-AUTH-030`, even when the maker also holds ops-checker); the txn is read again on
  approval (moved on: `409 MW-VAL-030`, request stays PENDING); expired: marked EXPIRED + audited,
  `409 MW-VAL-032`; decided already `409 MW-VAL-033` (the `status = 'PENDING'` update guard).
  `GET /admin/v1/change-requests[?status&resourceId&limit&offset]`, `GET .../{id}` (READ_TRANSACTIONS).
  Approval = ONE database transaction: row APPROVED + the message to mw-orchestrator + the audit,
  through the outbox: INQUIRE/FORCE_* -> `mw.txn.action.v1` (own contract, `dto/TxnAction`, key
  txnId, idempotency header = actionId, stored in `payload.actionId`); REVERSE -> REVERSAL on
  `mw.txn.request.v1` (`dto/ReversalRequest`: channel SYSTEM, fresh UUIDv7 `util/UuidV7` stored in
  `payload.reversalTxnId`, idempotencyKey = change request id, the original's full amount; initiator,
  debit and credit party `ORG`/`upay-ops`, since the orchestrator reverses the original's own parties
  and no customer data has to leave this service). Audit (`mw.audit.event.v1`, resource
  `transaction/<txnId>`, so it shows in the txn's trail): `admin.change_request.created` (maker;
  before `{txnStatus}`), `.approved` / `.rejected` (checker; before `{status: PENDING, txnStatus}`,
  after `{status, txnStatus, action, changeRequestId, makerId, ...}`), `.expired` (actor system);
  reasons only scrubbed (`MaskingPolicy.scrub`), never logged. `scheduler/ChangeRequestExpiryJob`
  (ShedLock `change-request-expiry`, `mw.change-requests.expiry-cron` every 5 min, batch
  `expiry-batch`) expires due requests. Bodies are checked against `derived-schemas/` by
  `filter/BodySchemaAdvice` + `service/BodySchema` (copied from mw-portal-api; json-schema-validator
  is now an implementation dependency, Jackson excluded) before binding: `400 MW-VAL-001`.
  Decision (no product owner available): only ops-maker / ops-checker act on transactions (not
  recon-*, not ops-emergency: its post-approval flow is for partner changes, ticket 38); a reject by
  the maker (withdrawal) is not offered. The orchestrator's resulting state is not tracked here: the
  detail view and audit trail show it.
- Internal API identity (M3 gate decision; `client/ServiceTokenClient`, `client/InternalApiCaller`):
  Keycloak realm `services` client `mw-admin-api` (client credentials, HTTP Basic, token carries
  audiences mw-orchestrator and mw-audit-dw), cached until 30 s before `expires_in`, dropped on a
  401; secret `mw.admin-api.internal-auth.client-secret` in `secret/mw-admin-api`, synced by the init
  from `KC_MW_ADMIN_API_SECRET`. Resilience4j `orchestrator` and `audit-dw` (retry + breaker on
  `QueryUnavailableException` only).
- Init (`mw-admin-api-init`, label `mw.oneshot`, `init/init.sh`, idempotent, dev only): role
  `admin_api_rw`, schema `admin` (spec 16.2), `secret/mw-admin-api` (DB password; client secret),
  AppRole. Stack: included in `deploy/compose/mvp/compose.yml`, low-mem limit 384 MiB, Hikari pool 2.
- Keycloak realm `ops` (`deploy/compose/platform/keycloak/realms/ops-realm.json`) carries the 15.4
  roles; dev users `ops-viewer01` (viewer), `ops-maker01`, `ops-checker01`, `ops-admin01`
  (integration-maker, security-admin), `ops-compliance01`, `ops-auditor01`, all TOTP-required; the
  dev-only e2e operators `ops-e2e-maker01` (ops-maker) and `ops-e2e-checker01` (ops-checker) have a
  seeded TOTP credential instead (secret `KC_OPS_TEST_TOTP_SECRET`, token route
  `deploy/apisix/routes/auth-ops-token.json`; ticket 37). A
  running stack keeps its old realm (import skips existing realms): `make down DOWN_ARGS=-v` first.

## Contracts owned

- `api/mw-admin-api/admin.v1.openapi.yaml` (admin API v1; `contracts/ApiContractsTest` validates the
  served bodies). JSON request bodies (change request, reject) each have a derived schema in
  `src/main/resources/derived-schemas/` (`scripts/derived-schemas.sh --write` after a contract
  change) registered in `filter/BodySchemaAdvice`.
- `events/mw-admin-api/mw.txn.action.v1` (ticket 37): approved INQUIRE / FORCE_COMMIT /
  FORCE_RELEASE for mw-orchestrator (Kafka topic 6 partitions, 7 d, retry group mw-orchestrator).
- `events/mw-admin-api/mw.config.changed.v1` (owned, not produced yet: ticket 38).

## Contracts used

- `contracts.yml`: produces `events/mw-audit-dw/mw.audit.event.v1` (own type `dto/AuditEvent`;
  `contracts/ProducedContractsTest`, and `AdminTransactionApiIT` validates the outbox row),
  `events/mw-admin-api/mw.txn.action.v1` (`dto/TxnAction`) and a REVERSAL on
  `events/mw-orchestrator/mw.txn.request.v1` (`dto/ReversalRequest`; `ChangeRequestApiIT`
  validates both outbox rows), serves
  `admin.v1`; consumes `api/mw-orchestrator/txn-query.v1` (own type `dto/AdminTxn`) and
  `api/mw-audit-dw/audit-query.v1` (own type `dto/AuditTrail`), unknown fields ignored
  (`contracts/ApiContractsTest` reads contract-shaped answers). No consumed events.

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
- `messaging/OutboxWriter` + `OutboxRelay`: transactional outbox (6.4), used for the reveal audit.
- `route/RetryTopicRouteBuilder` + `messaging/RetryDelayListener`: subclass per consumed topic; retry
  topics 10 s / 1 min / 10 min then DLQ (19.2); per-record commit; no sleeps on Kafka threads.
- `application.yml`: Resilience4j defaults (19.2), own schema `admin` (spec 16.2) migrated by Flyway
  on start from `db/migration` (16.4).

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew test` | unit, slice and contract tests, no Docker |
| `./gradlew integrationTest` | Testcontainers `*IT` classes (Docker); `AdminTransactionApiIT` (stub orchestrator + audit-dw), `ChangeRequestApiIT` (maker-checker, stub orchestrator) |
| `./gradlew conformance` | the `@Conformance` suite |
| `./gradlew check` | all three plus the JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-admin-api:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-admin-api` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make verify-changed [BASE=main]` | the same gate for the modules changed since BASE only |
| `make up M=mw-admin-api` | run this service's `compose.yml` (platform project must be up) |
