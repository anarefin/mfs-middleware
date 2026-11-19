# mw-admin-api

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.admin.api`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Operations console API (spec 15, WP23 part, ticket 36) for `mw-admin-ui` (server-side calls only)
  behind APISIX route `/admin/v1/*` (`deploy/apisix/routes/admin-v1.json`, `openid-connect`
  bearer-only on realm `ops`, 1 MiB body cap, 300 req/min per token subject). Later tickets add the
  change-request engine (37, below), partner config (38), USSD menus / SMS templates (39, below); 38/39
  reuse `ChangeRequestService`'s rules (a new `resourceType`, its own maker/checker role sets).
- Auth (`config/SecurityConfig`, `security/`): bearer JWT of realm `ops`, verified again here (JWKS
  `mw.admin-api.jwt.jwk-set-uri` / `MW_ADMIN_JWK_SET_URI`, issuer must end with `/realms/ops`,
  `security/RealmIssuerValidator`). **MFA is the realm's**: the `ops` browser flow requires password +
  TOTP and every user has `CONFIGURE_TOTP` (no token before enrolment); the service does not inspect
  `acr`/`amr` (Keycloak sets no OTP marker without extra LoA config). Decision: conservative and
  enough while realm `ops` is the only issuer accepted. Tokens from the console's login (ticket 40,
  `ui/mw-admin-ui`) carry the public issuer `https://localhost/realms/ops`, others
  `http://keycloak:8080/realms/ops`; both end with `/realms/ops`.
- Roles (`dto/AdminRole`, the spec 15.4 names as realm roles in `realm_access.roles`; any other
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
  /admin/v1/transactions/{txnId}[?reveal=true]` -> `{transaction, timeline, partnerCalls, auditTrail, auditAnchor,
  revealed}`: timeline (STATE and CORE entries) and partner calls from mw-orchestrator, audit trail
  (`?txnId=`, first `audit-trail-limit` 500) and the signed daily anchors' status (`auditAnchor` = its page's `anchor`, ticket 74; absent if mw-audit-dw sends none) from mw-audit-dw. Unknown txn `404 MW-VAL-003`; either
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
  `GET /admin/v1/change-requests[?status&resourceType&resourceId&limit&offset]` (`resourceType`
  transaction|wallet|pin_reset|partner|ussd_menu|notify_template; `status` is `dto/ChangeRequestView.Status`), `GET .../{id}`
  (`READ_CHANGE_REQUESTS`: the transaction readers and integration-maker/checker, ticket 38; an
  operator without `READ_TRANSACTIONS` sees partner requests only: transaction and wallet ones are
  left out of the list, `?resourceType=transaction|wallet` and their detail are 403).
  Approval = ONE database transaction: row APPROVED + the message to mw-orchestrator + the audit,
  through the outbox: INQUIRE/FORCE_* -> `mw.txn.action.v1` (own contract, `dto/TxnAction`, key
  txnId, idempotency header = actionId, stored in `payload.actionId`); REVERSE -> REVERSAL on
  `mw.txn.request.v1` (`dto/ReversalRequest`: channel SYSTEM, fresh UUIDv7 `util/UuidV7` stored in
  `payload.reversalTxnId`, idempotencyKey = change request id, the original's full amount; initiator,
  debit and credit party `ORG`/`bits-ops`, since the orchestrator reverses the original's own parties
  and no customer data has to leave this service). Audit (`mw.audit.event.v1`, resource
  `transaction/<txnId>`, so it shows in the txn's trail): `admin.change_request.created` (maker;
  before `{txnStatus}`), `.approved` / `.rejected` (checker; before `{status: PENDING, txnStatus}`,
  after `{status, txnStatus, action, changeRequestId, makerId, ...}`), `.expired` (actor system);
  reasons only scrubbed (`MaskingPolicy.scrub`), never logged. The `admin.v1` view
  (`ChangeRequestMapper.view`) scrubs them too, and every string in `payload` / `diff` at any depth
  except a whole UUID (the system ids this service writes: actionId, reversalTxnId, unlockTxnId)
  (security review; `mapper/ChangeRequestMapperTest`): change requests have no reveal path. `scheduler/ChangeRequestExpiryJob`
  (ShedLock `change-request-expiry`, `mw.change-requests.expiry-cron` every 5 min, batch
  `expiry-batch`) expires due requests. Bodies are checked against `derived-schemas/` by
  `controller/BodySchemaAdvice` + `service/BodySchema` (copied from mw-portal-api; json-schema-validator
  is now an implementation dependency, Jackson excluded) before binding: `400 MW-VAL-001`.
  Decision (no product owner available): only ops-maker / ops-checker act on transactions (not
  recon-*, not ops-emergency: its post-approval flow is for partner changes, ticket 38); a reject by
  the maker (withdrawal) is not offered. The orchestrator's resulting state is not tracked here: the
  detail view and audit trail show it. Decision (no product owner): an approved INQUIRE on a
  MANUAL_REVIEW transaction only records the core's answer on the timeline and keeps MANUAL_REVIEW
  (mw-orchestrator's `ManualInquiry` is a self-transition there), so even a resolved answer still needs a
  FORCE_COMMIT / FORCE_RELEASE change request to settle the transaction.
- Wallet unlock (ticket 52, M2 gate decision; same engine): `POST /admin/v1/wallets/{walletId}/change-requests
  {action: UNLOCK, reason}` (`dto/WalletChangeRequestBody`, derived schema
  `admin-v1-wallet-change-request.json`; role `TXN_MAKER` = ops-maker) -> 201 PENDING,
  `resourceType` `wallet`, `resourceId` the wallet id (11-digit MSISDN `^01[3-9]\d{8}$`, else `400
  MW-VAL-001`) stored whole in `admin.change_request` but shown masked (`0171****789`,
  `ChangeRequestMapper.shownId`) in every view, audit and log; `diff` LOCKED -> ACTIVE;
  `payload.unlockTxnId` a UUIDv7 minted at creation (txnId of the core command and of every audit
  of the request). One open request per wallet (`409 MW-VAL-031`). Approve / reject / expiry and the
  maker != checker rule (`403 MW-AUTH-030`) are the transaction ones; there is no status read (the
  core owns the wallet state, so a request is taken for any well-formed id and the core refuses an
  unlock of a wallet that is not LOCKED). Approval = one database transaction: row APPROVED
  (`payload.commandId`) + `UnlockWallet` (`dto/UnlockWalletCommand`, `core.command.v1`, owned by
  mw-orchestrator) on `core.wallet.command.v1` (key unlockTxnId, commandId `<changeRequestId>:unlock`,
  `expiresAt` = issued + `mw.change-requests.unlock-command-ttl` 1h, payload subject WALLET + the
  whole id, changeRequestId, makerId, checkerId) + audit. Audit resource `wallet/<masked id>`,
  `walletStatus` where a transaction request has `txnStatus`, `after.sentAs` the topic.
  Decisions (no product owner): ops-maker / ops-checker act on wallets as on transactions; the
  core's answer (`WalletUnlocked` / `UnlockRejected`) is not consumed here (as for ticket 37, the
  result is not tracked; mw-core-mock's wallet view shows it); the reason text is not sent to the core.
  **Ticket 70:** the same approval transaction also writes UNLOCK on `auth.wallet-lock.v1` (owned by
  mw-auth; `dto/WalletLockCommand`, `ChangeRequestMapper.authUnlock`: commandId
  `<changeRequestId>:auth-unlock`, key the wallet id, actor the checker, `payload.authCommandId`), so
  mw-auth lifts its PIN lock (every level) and any ADMIN lock; audit `after.sentAs`
  `auth.wallet-lock.v1,core.wallet.command.v1`. **Decision:** `UnlockWallet` to the core stays, because
  the core may lock an account for its own reasons. Ticket 72 removed the core PIN check and its lockout
  and kept the core's LOCKED status and `UnlockWallet` for such core-side locks (mw-core-mock CLAUDE.md), so
  both commands stay: UNLOCK lifts the mw-auth PIN / ADMIN lock, `UnlockWallet` a core lock (`UnlockRejected
  WALLET_NOT_LOCKED` when there is none, harmless).
- Care PIN reset (ticket 70, spec 18.7): `POST /admin/v1/wallets/{walletId}/change-requests {action:
  PIN_RESET, reason}` (same maker role and id rules) opens a change request of resourceType
  `pin_reset` (`ChangeRequest.PIN_RESET`, handled like `wallet`: `ChangeRequest.walletLike`, masked id,
  `payload.unlockTxnId` as txnId of its audits, `diff` LOCKED -> RESET, its own one-open-per-wallet, so
  it never collides with an unlock), approved by an ops-checker (`TXN_CHECKER`). Approval = ONE
  transaction like every approval (spec 6.4): the conditional PENDING -> APPROVED update claims the row,
  PIN_RESET on `auth.wallet-lock.v1` through the outbox (`ChangeRequestMapper.pinReset`: commandId
  `<changeRequestId>:pin-reset`, changeRequestId, makerId, actor the checker, reason scrubbed;
  `payload.authCommandId`) and the audit (`sentAs` `auth.wallet-lock.v1`); mw-auth opens the reset from
  the committed command, idempotent on the change request id. **Security review of ticket 70
  (maker-checker TOCTOU):** the first version called mw-auth's `POST /internal/v1/pin-resets` before
  the conditional update committed, so a reject or expiry winning the race left an open reset; now no
  remote call happens before the decision (no mTLS client in this service). Test
  `controller/PinResetApiIT` (the command validated against its contract, approve vs reject raced
  10 times: a rejected request never has a command, the maker / integration roles refused).
- Partner config (ticket 38, spec 15.2 Partners, 15.3, 16.2, 20.4; `controller/PartnerController` ->
  `service/PartnerConfigService` (reads, maker) and `service/PartnerChangeHandler` (approval) ->
  `repository/PartnerRepository`, schema `partner` (own, V5: `partner`, `endpoint`, `error_map`, V10
  `partner_route`; V11 (ticket 84) dropped `mapping`, `partner.template` and `endpoint.base_url`; Flyway
  `schemas: admin,partner`, history table in `admin`), `entity/Partner`,
  `dto/PartnerConfigValue` = the adapters' `PartnerConfig` field names (camelCase), `mapper/PartnerMapper`):
  `GET /admin/v1/partners[/{partnerId}]` (`AdminRole.READ_PARTNERS` = transaction readers +
  integration-maker/checker); `POST /admin/v1/partners/{partnerId}/change-requests {action, reason,
  family?, timeouts?, config?}` (`PARTNER_MAKER` = ops-maker, integration-maker): ENABLE / DISABLE /
  SET_TIMEOUTS (existing partner, else 404; `timeouts` only with SET_TIMEOUTS) or UPSERT_ROUTE (full
  config, below; `family` telco|billpay|bank needed for a new partner, never changes). The old action
  UPSERT and the template shape (`template`, `baseUrl`, `operations`) are gone (ticket 84; the contract
  refuses them, 400). Fields not belonging to the action, an invalid config (`PartnerConfigService.common`:
  credentialsRef a bare secret name, error-map suffix, scheme needs a ref, positive timeouts,
  readTimeoutMs >= timeoutMs; messages never repeat a value) -> 400 MW-VAL-001; a change that changes
  nothing -> 409 MW-VAL-030. The change request
  (resourceType `partner`, resourceId the partner id) stores `payload {family, baseVersion?, config}`
  (full config) and `diff {before, after}` (changed top-level fields). Approve/reject on the shared
  `/admin/v1/change-requests/{id}/...` (`PARTNER_CHECKER` = ops-checker, integration-checker; never the
  maker). `ChangeRequestService` keeps the shared rules and calls the resource's
  `service/ResourceChangeHandler` (ticket 39 adds its own): approval = ONE database transaction: the
  partner row locked and still at `baseVersion` (absent: still not existing; else 409 MW-VAL-030, stays
  PENDING), tables replaced, version + 1 (seed 0 -> 1), outbox `mw.config.changed.v1` PARTNER UPSERT
  (key `partner/<id>`, value the full config, changedBy maker, approvedBy checker, idempotency header
  `partner/<id>@<version>`, 6.1 txn header the change request id), `payload.version`, the request
  APPROVED and its audit. Audit for partners: resource `partner/<id>`, no txnId (key the resource),
  before/after carry `config` (the diff sides; never secrets). The compacted topic replays the latest
  record per partner to every adapter start (the adapters consume from the beginning in a group of
  their own). Seeds: `mw.partners.seeds` (resource list, `config/PartnerSeedProperties`,
  `scheduler/PartnerSeedJob` at start-up, insert-if-absent at version 0, never published, checked with
  the route rules, its route row `route_status` SEED, no `route` state in the view); only the dev
  profile (`application-dev.yml`, `SPRING_PROFILES_ACTIVE=dev` in `deploy/docker-compose.yml`) seeds
  `partner-seeds/dev/ocs.json`, `billagg.json`, `c2wbank.json` (ticket 84), copies of the adapters' dev
  seeds (mw-adapter-telco `ocs`, -billpay `billagg`, -bank `c2wbank`) with the simulator URL written
  into the route (keep them identical; the test config seeds them too). V11 drops a version-0 seed
  without a route row so it is stored again in the route shape. Decisions (no product owner available): ops-maker/ops-checker may change
  partners as well as integration-* (an incident disable is an operations task); the ops-emergency
  direct change with 24 h post-approval (15.3) is not built; DELETE / tombstones are not offered (disable
  instead); the admin does not compile JSONata or know the adapters' host allowlists, so an adapter may
  still refuse an approved config (`security.partner_config_refused` / `config.rejected`, previous
  config kept) and the admin's tables then differ from what runs; records go to every adapter (configKey
  has no family), and an adapter without an allowlist entry for the id refuses them; nothing republishes
  the snapshot after a Kafka volume loss (approve a change to republish).
- Partner routes (ADR D28, ticket 83; same engine): action `UPSERT_ROUTE` with `config` (`route` Camel
  YAML DSL, `mappings`, `allowedHosts`, `dryRun {operation, sample, baseUrl?}`, timeouts, `errorMap`,
  `ambiguousCodes`, `sensitiveFields`; `family` for a new partner). Only UPSERT_ROUTE carries a
  `dryRun`: ENABLE / DISABLE / SET_TIMEOUTS on a proven route (a seed or a version reported LIVE)
  republish it without one (ticket 84), so the adapter swaps the version in at once; on a route still
  PENDING_DRY_RUN or REJECTED they keep its `dryRun`, so the gate still applies (review of 84). Field problems `400 MW-VAL-001`; the route itself is checked by
  `service/PartnerRouteValidator`, a copy of mw-adapter-billpay's `PartnerRouteLoader` rules kept identical
  under D14 (no Camel here: the loader's walk over Camel's model runs over the SnakeYAML node tree with the
  same allowlist; Camel's YAML DSL JSON Schema `schema/camelYamlDsl.json`, copied from camel-yaml-dsl
  4.22.2, runs first; strict Kamelet parameter keys, the `mw-http-call` host in `allowedHosts`, the
  ending rule, named mappings present; Kamelet library `mw.partner-routes.kamelets`; ticket 85: the
  `mw-soap-call` rules (address host, `*.xsl` or `classpath:partner/xslt/` stylesheets, SOAP version,
  WS-Security secret field; review of 85: `request=json`/`response=json` with a `namespace`, `auth`
  none|basic with `userField` and `secretField`, `sslContextRef` over https; the batch Kamelets' rules
  in `service/PartnerBatchRouteRules`, the copy of the adapters' `PartnerBatchRoutes`: values read
  URL-decoded, `delay` (100 ms or more) or `cron`, a sink `mapping` in request routes only) and every
  `*.xsl` mapping through `service/StylesheetPolicy`, the copy of the
  adapters' `partner/mapping/StylesheetPolicy`: no DOCTYPE/entity, `document()`, `doc()`, includes and the
  like, `SoapRouteValidatorTest`): `400 MW-VAL-041`,
  one detail per problem `{field: config.route, issue: "line N: construct: problem"}`, before any change
  request exists. `dryRun.baseUrl` must be a plain http(s) URL in `allowedHosts` (the adapters' rule).
  Approval: as above, plus `family` in the published value (every approval: each adapter applies its own
  family only) and a `partner.partner_route` row (V10: route_yaml, mappings, allowed_hosts, family, dry_run,
  route_status PENDING_DRY_RUN | LIVE | REJECTED (SEED for a seed, V11), problems, kamelets; V11
  `published_value` = the full value published) for the new version; any later ENABLE / DISABLE /
  SET_TIMEOUTS republishes the route as a new version.
  `messaging/PartnerRouteStatusListener` (group mw-admin-api, retry topics + DLQ;
  `mw.partner-routes.status-consumer.enabled`, off in tests) consumes `mw.partner.route.status.v1`
  (mw-adapter-billpay's contract, own type `dto/PartnerRouteStatusMessage`) into
  `service/PartnerRouteStatusService` (update by key, latest report wins, a version not approved here is
  skipped; ticket 97: the adapters' health reports, LIVE with `health`, are applied the same way, a seed's version 0 is skipped). Rollback (ticket 84, ADR D28 amendment; the restart gap of ticket 83: an adapter replays
  only the latest record per partner): a REJECTED report for the partner's current version republishes
  the newest version reported LIVE before it as the next version, without `dryRun`, in the same
  transaction (review of 84, `PartnerRouteStatusService.rolledBack`): the set its dry-run proved
  (route, mappings, allowedHosts, auth, sensitiveFields, headers, error map) as it was, enabled only
  when both versions are (never re-enables a partner the operator disabled), the current timeouts; the
  merged value must pass `validateRoute` again (`partner.route.rollback_refused` otherwise), and a
  rejected version that kept that proven set is not rolled back (it would be rejected again); partner rows restored, route row PENDING_DRY_RUN, outbox record
  changedBy / approvedBy `system/rollback`, event `partner.route.rolled_back`; a repeated or stale REJECTED
  (another instance, a replay) does nothing, and without a LIVE predecessor nothing is published (the
  adapter keeps its seed). Not audited on `mw.audit.event.v1` (the record and the log carry it). Views:
  `Partner.route {version, status, problems, kamelets, statusAt, liveVersion}`; `GET
  /admin/v1/partners/kamelet-usage` (newest LIVE version per partner, per Kamelet). Tests:
  `PartnerRouteValidatorTest`, `PartnerRouteApiIT`, `service/PartnerRouteRollbackIT`,
  `contracts/ConsumedContractsTest`.
- USSD menus and notify templates (ticket 39, spec 9.2, 10.3, 15.2; `controller/ContentController` ->
  `service/ContentChangeService` -> `repository/ContentVersionRepository`, table
  `admin.content_version` V6, `entity/ContentVersion`, `mapper/ContentChangeMapper`; roles
  `AdminRole.CONTENT_MAKER` = ops-maker, `CONTENT_CHECKER` = ops-checker). `POST
  /admin/v1/ussd-menus/{menuId}/change-requests {menu: {start, messages, nodes}, activations: [{mno,
  goLiveAt?}], reason}` and `POST /admin/v1/notify-templates/{code}/{SMS|EMAIL}/{bn|en}/change-requests
  {body, subject?, htmlBody?, senderId?, validFrom?, reason}` -> 201 PENDING change request
  (resourceType `ussd_menu` / `notify_template`, resourceId menuId / `<code>/<channel>/<locale>`,
  action `PUBLISH`, payload = the version, diff `{before: {configKey, version?}, after: {configKey,
  version}}`) plus a PENDING `content_version` row (version = max + 1 per configKey), audited, in one
  transaction; one open request per resource (`409 MW-VAL-031`). Checked at maker time, `400
  MW-VAL-040` with one `details` entry per problem: menus by `service/MenuValidator` (a copy of
  mw-ussd's rules over copied `dto/Menu`, `MenuNode`, `MenuOption`, `Text`, `ValidatorSpec`,
  `NodeType` and `mapper/MenuParser`; keep aligned: dangling references, missing fields, unknown
  validator/flow/lookup, bad regex, PIN rules, plus every text, option label, validator error and the
  six engine messages in both bn and en), each MNO once and in `mw.content.mnos` (gp, robi, bl,
  teletalk); templates by `service/TemplateValidator` (only plain `{{name}}`, EMAIL needs a subject,
  SMS no subject/HTML, senderId 1-11 alphanumerics/spaces). Approve / reject / expire are the
  generic change-request endpoints and job: `ChangeRequestService` hands them to the
  `service/ResourceChangeHandler`s `UssdMenuChangeHandler` / `TemplateChangeHandler` (base
  `ContentChangeHandler`; checker role `CONTENT_CHECKER`, maker != checker unchanged; the handler's
  `closed` hook, added here, marks a rejected / expired version); approval = ONE transaction: request APPROVED, version
  PUBLISHED, `mw.config.changed.v1` (key configKey `ussd-menu/<menuId>` or
  `template/<code>/<channel>/<locale>`, configType USSD_MENU / TEMPLATE, version = the admin version,
  value = the full version, changedBy maker, approvedBy checker; header mw-txn-id and idempotency key
  from the change request id) and the audit (resource `ussd_menu/<menuId>` /
  `notify_template/<...>`, no txnId, before / after `config` = the diff side {configKey, version}); go-live times (`goLiveAt`,
  `validFrom`) before the approval become the approval time. Rejected / expired versions are marked
  so and never published. Reads (any 15.4 role): `GET .../versions[?limit&offset]` (no value) and
  `.../versions/{version}` (with value). Body cap raised to 256 KiB (`controller/BodySchemaAdvice`; a
  menu tree is some 15 KiB). Decisions (no product owner): ops-maker / ops-checker own customer-facing
  text; one version carries its own per-MNO schedule (no activation-only request); version numbers
  here are the admin's, mw-ussd / mw-notify number their own rows; the seeded first versions live
  only in those services (the admin lists only versions made here); the compacted topic keeps only
  the latest record per key, so a consumer starting on an empty database gets the latest version
  only (fine for the single-instance MVP with persistent databases).
- Keys and the key ceremony (ticket 75, spec 18.8, D23, OWN-08; `controller/KeyController` ->
  `service/KeyAdminService`, approval `service/KeyChangeHandler` (resourceType `key`,
  `ChangeRequest.KEY`, resourceId the keyId), `client/CryptoKeyClient`): `GET /admin/v1/keys` (mw-crypto's
  key list, `AdminRole.READ_KEYS` = security-admin, auditor); `POST /admin/v1/keys/change-requests
  {action KEY_GENERATE|KEY_ROTATE|KEY_RETIRE, keyId, purpose?, version?, reason}` (checked against the
  key as mw-crypto has it: generate a keyId that does not exist, rotate an existing key, retire a
  VERIFY_ONLY version, else `409 MW-VAL-030`; diff `{before|after: {key: "<kid> <state>"}}`); `POST
  /admin/v1/key-ceremonies {keyId, purpose (PIN_HMAC|TOKENISE_*|HMAC_*), reason}` -> a KEY_CEREMONY
  request (payload `keyId, purpose, kid, shares[]`); `POST /admin/v1/key-ceremonies/{id}/components
  {component, checkValue}` (a custodian in their own session: the component goes to mw-crypto in the
  request only, never stored, logged or audited; payload gets `shares[{share, custodian, enteredAt}]`
  and, once both are in, `checkValue` (the KCV), through `ChangeRequestRepository.updatePendingPayload`;
  audit `admin.key_ceremony.component_entered`, actor the custodian, after `{share, custodian,
  checkValue?}`); `GET /admin/v1/key-ceremonies/{id}`. Roles: `AdminRole.KEY_ADMIN` = security-admin
  makes, enters components and decides (added to `ANY_CHECKER`; the handler's `checkers()`), maker !=
  checker is the shared rule (`403 MW-AUTH-030`). **Fresh MFA:** `dto/Operator.authTime` from the
  token's `auth_time` (`security/OperatorResolver`); every key action (file, enter a component, approve,
  reject: the new `ResourceChangeHandler.checkDecider` hook) needs it at most
  `mw.admin-api.key-mfa-max-age` (5 min) old, else `403 MW-AUTH-031` (sign in again); a token without
  `auth_time` is stale. **Approval is claim-first** (review of the dual-control race, as ticket 70's
  TOCTOU finding; `ResourceChangeHandler.claimFirst`, `ChangeRequestService.approveClaimFirst`, V7):
  (1) `ResourceChangeHandler.precheck` (a ceremony needs both components, `409 MW-VAL-050`), then one
  conditional update PENDING -> APPROVING (checker != maker and fresh MFA checked first) with a claim
  (V8/V9: `checker_sub`, a random `claim_token`, `claimed_at`, `attempt_started_at`); (2) only that
  winner calls mw-crypto: a key change is `PUT /internal/v1/key-changes/{claimToken}` (the approval
  attempt's id, the same on a retry of this claim, a new one after a release, so a cancelled attempt
  never blocks the request), a ceremony `POST /internal/v1/ceremonies/{changeRequestId}/commit`; (3)
  APPROVING -> APPROVED with the payload (`appliedKid`) and the audit (`sentAs` `mw-crypto`) in one
  transaction. Only the claim holder (sub AND token in the conditional UPDATE) finishes, retries
  (refreshing `attempt_started_at`) or releases. **"Not applied" is binding** (security reviews): a
  refusal (4xx) is never trusted alone; the claim is settled first (`ResourceChangeHandler.settle`):
  `POST /internal/v1/key-changes/{claimToken}/cancel` writes a terminal CANCELLED tombstone at
  mw-crypto unless the change was applied (atomic with the PUT; a later PUT of that attempt is
  refused); a ceremony is aborted unless committed (abort is terminal there). Applied -> APPROVED;
  cancelled -> PENDING (a ceremony: EXPIRED, `reusableAfterCancel`, its ceremony is dead); cannot tell
  -> stays APPROVING. No answer (timeout, 5xx, `503 MW-SYS-042`) leaves it APPROVING; only the
  claiming checker retries, within `mw.change-requests.approving-lease` (5 min) of the claim.
  `scheduler/ApprovingReconcileJob` (ShedLock `change-request-approving-reconcile`, every minute,
  `ChangeRequestService.reconcileApproving`) settles claims whose last attempt is older than the lease
  plus `mw.admin-api.read-timeout` (so no PUT can still be in flight; the cancel refuses it anyway):
  applied -> APPROVED, cancelled -> EXPIRED once `expires_at` passed, else PENDING; cannot tell -> next
  run. It also aborts ceremonies mw-crypto lists live (`GET /internal/v1/ceremonies?state=LIVE`) whose
  request is REJECTED, EXPIRED or missing and that are older than that grace (a failed filing; an
  abort that failed while mw-crypto was down). Reject and expiry act on PENDING only: an APPROVING
  request is refused (`409 MW-VAL-033`) and still blocks a second open request of the key. So an
  applied key change never belongs to a REJECTED request. Ceremonies: opened at mw-crypto at filing
  time (`PUT /internal/v1/ceremonies/{id}` inside the filing transaction; a failure after the open is
  compensated by an abort); a component never opens one; a component whose payload update finds the
  request no longer PENDING re-reads it and aborts only when it is REJECTED or EXPIRED (APPROVING or
  PENDING: `409`, no abort). Reject / expiry of a ceremony calls `.../abort` (best effort, WARN
  `admin.key_ceremony.abort_failed`; the reconciler retries). Tests `controller/KeyApprovalRaceIT`
  (stateful stand-in mw-crypto: approve vs reject, ceremony commit vs reject and component vs reject
  each raced 10 times; refusal settled applied / cancelled / unknown; a new attempt after a release;
  timeout, retry of the same attempt by the claimer only; the reconciler waits for the last attempt,
  cancels, and the PUT still in flight is refused; expiry; a refused ceremony commit; a component on
  an APPROVING vs a REJECTED request; a failed filing; orphan ceremonies). Decision: synchronous mTLS call, not an outbox command (the
  ceremony is interactive and returns the KCV). mw-crypto's own `MW-VAL-*` refusals pass through with their code and status
  (`MW-VAL-017` bad component, `MW-VAL-018` same custodian / conflict); unreachable, 5xx or another
  refusal `503 MW-SYS-042`. Visibility: an operator without `READ_TRANSACTIONS` sees partner requests
  (`READ_PARTNERS`) and key requests (`READ_KEYS`) only (`ChangeRequestService.visibleTypes`;
  security-admin is now in `READ_CHANGE_REQUESTS`). mTLS client: `config/ServiceCertificateManager` +
  `config/TlsProperties` copied from mw-audit-dw (`mw.tls.enabled`, default false, compose
  `MW_TLS_ENABLED=true`; PKI role from `PKI_SERVICES`), mw-crypto at `mw.admin-api.crypto.base-url`
  (`MW_CRYPTO_URL`, `https://mw-crypto:8080`); the services-realm token already carries audience
  `mw-crypto`. Test `controller/KeyCeremonyApiIT` (stand-in mw-crypto: same-user approval, stale MFA
  for maker, checker and a token without auth_time, ops-checker refused, ceremony before both
  components, same custodian, components never in the row / outbox, commit and abort calls).
- Internal API identity (M3 gate decision; `client/ServiceTokenClient`, `client/InternalApiCaller`):
  Keycloak realm `services` client `mw-admin-api` (client credentials, HTTP Basic, token carries
  audiences mw-orchestrator and mw-audit-dw), cached until 30 s before `expires_in`, dropped on a
  401; secret `mw.admin-api.internal-auth.client-secret` in `secret/mw-admin-api`, synced by the init
  from `KC_MW_ADMIN_API_SECRET`. Resilience4j `orchestrator` and `audit-dw` (retry + breaker on
  `QueryUnavailableException` only).
- Dev provisioning (`provision_mw_admin_api` in `deploy/provision/services.sh`, run by the one-shot `provision` on every `docker compose up`, idempotent, dev only): role
  `admin_api_rw`, schemas `admin` and `partner` (spec 16.2), `secret/mw-admin-api` (DB password; client secret),
  AppRole. Stack: a service of `deploy/docker-compose.yml` (profiles `minimal`, `core`), limit 512 MiB, Hikari pool 2.
- Keycloak realm `ops` (`deploy/keycloak/realms/ops-realm.json`) carries the 15.4
  roles; dev users `ops-viewer01` (viewer), `ops-maker01`, `ops-checker01`, `ops-admin01`
  (integration-maker, security-admin), `ops-compliance01`, `ops-auditor01`, all TOTP-required; the
  dev-only e2e operators `ops-e2e-maker01` (ops-maker) and `ops-e2e-checker01` (ops-checker) have a
  seeded TOTP credential instead (secret `KC_OPS_TEST_TOTP_SECRET`, dev-only direct-grant client
  `mw-e2e-ops`; the console client `mw-admin-ui` is code + PKCE only; token route
  `deploy/apisix/routes-test/auth-ops-token.json`, test-only; ticket 37). A
  running stack keeps its old realm (import skips existing realms): `deploy/reset.sh` first.

## Contracts owned

- `api/mw-admin-api/admin.v1.openapi.yaml` (admin API v1; `contracts/ApiContractsTest` validates the
  served bodies). JSON request bodies (change request, reject) each have a derived schema in
  `src/main/resources/derived-schemas/` (`scripts/derived-schemas.sh --write` after a contract
  change) registered in `controller/BodySchemaAdvice`.
- `events/mw-admin-api/mw.txn.action.v1` (ticket 37): approved INQUIRE / FORCE_COMMIT /
  FORCE_RELEASE for mw-orchestrator (Kafka topic 6 partitions, 7 d, retry group mw-orchestrator).
- `events/mw-admin-api/mw.config.changed.v1` (ticket 38): approved partner configs, PARTNER UPSERT,
  key `partner/<id>`, value the adapters' `PartnerConfig` shape (compacted topic, 6 partitions); since
  ticket 84 a value with `template` or `operations` is invalid, and a `system/rollback` record
  republishes the last LIVE version.
- `events/mw-admin-api/mw.config.changed.v1` (ticket 39): approved USSD menu and template versions,
  USSD_MENU (key `ussd-menu/<menuId>`) and TEMPLATE (key `template/<code>/<channel>/<locale>`) UPSERTs;
  value shapes in `mapper/ContentChangeMapper` and the samples `valid-ussd-menu-upsert.json`,
  `valid-template-upsert.json`; retry groups mw-ussd and mw-notify.

## Contracts used

- `contracts.yml`: produces `events/mw-orchestrator/core.command.v1#UnlockWallet` (ticket 52,
  `dto/UnlockWalletCommand`; `WalletUnlockApiIT` validates the outbox row),
  `events/mw-audit-dw/mw.audit.event.v1` (own type `dto/AuditEvent`;
  `contracts/ProducedContractsTest`, and `AdminTransactionApiIT` validates the outbox row),
  `events/mw-admin-api/mw.txn.action.v1` (`dto/TxnAction`) and a REVERSAL on
  `events/mw-auth/auth.wallet-lock.v1` (ticket 70, `dto/WalletLockCommand` UNLOCK and PIN_RESET;
  `WalletUnlockApiIT`, `PinResetApiIT`),
  `events/mw-orchestrator/mw.txn.request.v1` (`dto/ReversalRequest`; `ChangeRequestApiIT`
  validates both outbox rows), `events/mw-admin-api/mw.config.changed.v1` (`dto/ConfigChangedEvent`;
  `PartnerConfigApiIT` and `ContentChangeApiIT` validate the outbox rows), serves
  `admin.v1`; consumes `api/mw-orchestrator/txn-query.v1` (own type `dto/AdminTxn`) and
  `api/mw-audit-dw/audit-query.v1` (own type `dto/AuditTrail`) and `api/mw-crypto/crypto-internal.v1`
  (ticket 75, own type `dto/CryptoKeys`), unknown fields ignored
  (`contracts/ApiContractsTest` reads contract-shaped answers). Consumes
  `events/mw-adapter-billpay/mw.partner.route.status.v1` (ticket 83, `contracts/ConsumedContractsTest`).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (written inline in
  `build.gradle.kts`; drop once the Boot BOM ships them); the shared dev provisioning image `deploy/provision/Dockerfile` runs `apk upgrade` and removes gosu. `scripts/security.sh <this module>` scans the SBOM and the
  `mfs-mw/*:local` images (HIGH/CRITICAL fail; accepted ones in `.trivyignore.yaml` with a reason and expiry).
- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
  Events written without one (scheduled jobs) get a plain UUID (`util/CorrelationIds`).
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ForbiddenException` (403 `MW-AUTH-002`, thrown by the services and
  `security/OperatorResolver`; the services read roles from `dto/Operator` / `dto/AdminRole`, never
  `security/`), `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field.
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
  Add context with `log.atInfo().addKeyValue("event", "...")` or the MDC.
- `config/MwServiceProperties`: config binding; secrets from OpenBao when `VAULT_ENABLED=true`.
- `messaging/OutboxWriter` + `OutboxRelay`: transactional outbox (6.4), used for the reveal audit.
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
- `application.yml`: Resilience4j defaults (19.2), own schema `admin` (spec 16.2) migrated by Flyway
  on start from `db/migration` (16.4).

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-admin-api:test` | every test in one JVM: unit, slice and contract tests, the Testcontainers `*IT` classes (`AdminTransactionApiIT` (stub orchestrator + audit-dw), `ChangeRequestApiIT` (maker-checker, stub orchestrator), `PartnerConfigApiIT` (partner change requests, outbox config records), `WalletUnlockApiIT` (ticket 52), `ContentChangeApiIT` (menus and templates, ticket 39)) and the `@Conformance` suite; one PostgreSQL and one Kafka per test JVM, a fresh database per Spring context; the `*IT` and conformance classes are skipped without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-admin-api:build` | `test`, then the boot jar `build/libs/mw-admin-api.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-admin-api:bootJar && docker build -t mw-admin-api:dev services/mw-admin-api` | image, as spec 4.3 rule 1 |
| `./gradlew clean build` | the full gate: every module, plus the repo checks (`:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`); unchanged modules come from the build cache |
| `./gradlew :mw-admin-api:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-admin-api` | rebuild this service's image (`mfs-mw/mw-admin-api:local`) and restart it with what it depends on (dev timers from `deploy/.env`) |
