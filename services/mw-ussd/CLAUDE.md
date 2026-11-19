# mw-ussd

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.ussd`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- USSD channel (spec 9.2, FR-10/11, WP18 part, WP19 part; ticket 26): one menu engine for every
  MNO behind the transport SPI `transport/UssdTransport` (`onBegin`, `onContinue`, `onEnd`,
  `onAbort`, `send`): `transport/HttpUssdTransport` and `transport/SmppUssdTransport` (ticket 27; `transport/` sits beside `controller/` and calls `service/UssdEngine`).
  An MNO switches transport by configuration alone (`mw.ussd.mno.<mno>.transport` + its
  `mw.ussd.smpp.mno.<mno>` endpoint); `UssdMenuWalkSuite` runs the same walk over both.
- SMPP transport (spec 9.2, 9.2.1, WP19): `client/UssdSmppClient` (bean in `config/UssdSmppConfig`)
  holds `mw.ussd.smpp.mno.<mno>.binds` transceiver binds `<mno>-1..n` per SMPP MNO
  (`client/UssdSmppBind`, jSMPP direct as mw-notify): own `enquire_link` every 30 s (jSMPP's
  sender is off), 3 misses in a row drop the bind, reconnect 1-60 s exponential; `submit_sm`
  through a window (outstanding) and a TPS throttle, retried after `retry-delays` (500 ms, 1 s,
  2 s) on `generic_nack`, 0x58 and 0x14 (a timeout is not retried: the screen may have arrived).
  Events `smpp.bind.up` / `smpp.bind.down` (`reason` closed|enquire_link|bind_failed),
  `smpp.submit.retry`; gauge `mw.smpp.bind.up{mno,bind}` (`mw_smpp_bind_up`). `deliver_sm` TLVs:
  `ussd_service_op` (0x0501) 1 PSSR indication = BEGIN, 18 USSR confirm = CONTINUE;
  `its_session_info` (0x1383: session number; sequence bits 7..1; end-of-session bit 0) with the
  end bit = ABORT (subscriber release). Screens go out as op 2 USSR request, the last as 17 PSSR
  response with the end bit, on the bind that received the session (`SmppUssdTransport` keeps the
  route in memory). Session id `smpp-<number>-<sha256(msisdn) 16 hex>` (no MSISDN in Redis keys).
  Each step runs on a virtual thread, never on jSMPP's PDU threads. Text GSM-7 unpacked or UCS-2
  (`util/UssdText`). Dev: gp binds 2 to `mw-simulators:2776`; robi, bl, teletalk
  endpoints config-ready. `mw.ussd.smpp.enabled=false` binds nothing (tests). SMPP password key
  `mw.ussd.smpp.mno.<mno>.password` in Vault (empty in dev: the simulator accepts any bind).
- HTTP transport: `POST /ussd/{mno}` (`controller/UssdHttpController`, contract
  `api/mw-ussd/ussd-http.v1.openapi.yaml`), JSON `{sessionId, msisdn, serviceCode?, input?,
  type: BEGIN|CONTINUE|END|ABORT}` -> `{sessionId, message, flag}` with the MNO's flag values
  (`mw.ussd.mno.<mno>.continue-flag` / `end-flag`, CON / END). Only MNOs with `transport: HTTP`
  are served (others 404 `MW-VAL-003`); MSISDN `01X...`, `8801X...`, `+8801X...` (else 400
  `MW-VAL-001`), canonical `01XNNNNNNNN` from there on. The body is checked before binding (and
  before the MNO lookup) against `src/main/resources/derived-schemas/ussd-http-v1-step.json`, derived
  from the contract's `UssdStep` (`scripts/derived-schemas.sh --write` after changing it, `./gradlew
  :contractsLint` fails on drift), by `filter/BodySchemaAdvice` -> `service/BodySchema` (copied from
  mw-app-gateway; review A6, APISIX checks no body): duplicate keys, trailing content, non-objects and
  schema breaks `400 MW-VAL-001` (no request value in a message: `input` may be a PIN), over 64 KiB
  `413 MW-VAL-BODY_TOO_LARGE`. Behind APISIX routes `ussd-http-<mno>`
  (`/ussd/<mno>`, `deploy/apisix/routes/ussd-http-<mno>.json`: the MNO's ip allow-list
  `USSD_<MNO>_ALLOWED_CIDRS`, basic-auth as consumer `ussd_<mno>` only (another MNO's credential
  403), 4 KiB body cap, quota per MSISDN hash; this service sees no credential).
- Per MNO (`mw.ussd.mno.<gp|robi|bl|teletalk>`, spec 9.2; `config/UssdProperties`): transport,
  short codes, screen limits (182 GSM-7 / 70 UCS-2), session timeout, overflow TRUNCATE|PAGE.
  Dev: gp SMPP; robi, bl HTTP + PAGE; teletalk HTTP + TRUNCATE. Short codes, one per MNO: gp `*268#`, robi
  `*269#`, bl `*270#`, teletalk `*271#`. `UssdEngine` checks the dialled code on BEGIN (`dto/UssdRequest.serviceCode`:
  HTTP `serviceCode`, SMPP destination address; whitespace ignored): another code ends the session at once with
  the built-in `UssdEngine.WRONG_CODE_TEXT` ("Dial {code}", the MNO's first code; default language), no
  session stored, event `ussd.session.refused`; a step without a code, or an MNO without `short-codes`, is
  not checked. `operator` = the MNO's top-up partnerId
  at mw-adapter-telco, filled into `{mno}` of an own-number top-up (bl: `banglalink`; default the key).
- Menus are data (`dto/Menu`, `MenuNode`, node types MENU, INPUT, CONFIRM, ACTION, DYNAMIC, END,
  text `{bn, en}`): versioned JSON in `ussd.menu`, activated per MNO with a go-live time in
  `ussd.menu_activation` (V4). `service/MenuService` returns the live version for an MNO (latest
  activation whose go-live passed) or a session's own version (a running session finishes on the
  version it started on); versions are immutable and cached once parsed. `service/MenuValidator`
  refuses a tree with dangling references, missing fields, an unknown validator/flow, a regex that
  does not compile, or a PIN input that is stored or does not lead straight to an ACTION.
  `service/MenuSeeder` stores the dev seed `menus/main-v3.json` (`mw.ussd.seed-menu`, blank = off)
  and activates it for every configured MNO without a menu or still on an older version of the
  seed (v1/v2 -> v3; another menu is never replaced; `main-v2.json` stays as shipped). Seed (ticket 28): 1 Send Money (P2P), 2 Cash Out (customer to agent), 3 Mobile
  Recharge (1 own number: option `set` stores `{msisdn}` / `{mno}` as target and operator; 2 other
  number + DYNAMIC `operators`; product `=PREPAID`), 4 Pay Bill (DYNAMIC `bp.category` source
  `biller-categories` store `category` "Select category" -> DYNAMIC `bp.biller` source `billers` (that
  category's billers), bill number INPUT with `lookup: bill`, CONFIRM with the fetched amount; en screens
  "Select category\n1 Electricity\n2 Water\n3 Gas\n4 Telecom\n5 Internet", after 3 "Select biller\n1 JGTDSL\n2
  KGDCL\n3 TITAS"; the same nodes in `main-v2.json` and mw-seeder's `seed-content/menus/main.json`), 5 Balance (v3, ADR D26: `bal.pin` ->
  ACTION `bal.check` flow BALANCE, empty mapping -> `bal.done` "Tk {balance}" / `bal.failed`), 9 language
  (v3 labels option 3 "রিচার্জ" / "Recharge" so the main screen stays within 70 UCS-2 characters). Every Bangla screen of
  the seed fits 70 UCS-2 characters (`UssdMenuWalkTest`).
- Published menus (ticket 39, spec 9.2, 20.4): mw-admin-api's approved versions arrive on
  `mw.config.changed.v1` (compacted; `messaging/ConfigChangedListener`, a retried `@KafkaListener`, group
  mw-ussd, earliest offset, retry topics then DLQ, tombstones skipped) and
  `service/MenuPublisher` takes configType USSD_MENU UPSERTs (value `{menuId, version, menu,
  activations: [{mno, goLiveAt}]}`; other types / DELETE ignored): in one transaction the tree is
  stored as a new immutable version (numbered here: above every stored version and at least 100, so
  never a seed's number; the admin version kept in `ussd.menu.config_version`, V5, unique per menu:
  a redelivered or replayed record is stored once, an older one ignored) with one activation per
  configured MNO at its go-live (unknown MNO skipped, event `ussd.menu.activation_skipped`). The tree
  is checked again by `MenuValidator.publishProblems` (the base rules plus every text, option label,
  validator error and engine message in bn and en, as mw-admin-api checked at maker time); an
  unusable one is logged `ussd.menu.rejected` and dropped. Sessions starting after the go-live get
  it; a running session keeps its version (`MenuPublisherIT`, on a test-only MNO `itmenu`). Events
  `ussd.menu.published`, `ussd.menu.activated`, `ussd.menu.publish_skipped`.
- Engine (`service/UssdEngine`): one step per request, never throws (a failure ends with the
  menu's `unavailable` text). Language `mw.ussd.default-language` (bn) at dial-in, toggled by a
  MENU option with `switchLanguage` (no profile lookup yet). Validators (`service/InputValidator`):
  msisdn (canonical, `notSelf`), amount (taka, <= 2 decimals, min/max, stored as `500.00`), pin
  (5 digits unless `pattern`: mw-crypto's PIN rule since ticket 72), numeric (digit count min/max), regex. A bad input re-prompts with
  the validator's `error` (or the menu's `invalid`) above the screen; `max-invalid-attempts` (3)
  in a row end the session. Placeholders `{input}` / `{txnId}` / `{error}` / `{balance}` in texts. A screen over
  the MNO limit is cut (TRUNCATE) or paged with the menu's `more` option ("0 More", PAGE).
  DYNAMIC sources (`service/DynamicLists.items(source, inputs)`): `billers` = mw-adapter-billpay's live
  catalogue (`service/BillPayDirectory`, sorted by code, label = code, cached `catalogue-ttl` 60 s and kept
  through an outage), only the billers of the session input `category` when it is set;
  `biller-categories` = the categories of that catalogue with at least one biller, in the fixed order
  `DynamicLists.CATEGORY_ORDER` (ELECTRICITY, WATER, GAS, TELECOM, INTERNET; another category after them by
  code, labelled with its code), value = the category code, bn/en label from `mw.ussd.biller-categories.<CODE>`
  (`config/UssdProperties.billerCategories`); others static from `mw.ussd.lists.<source>` (`operators`).
  `DynamicListsTest` and `UssdMenuWalkTest` (the 10-biller catalogue: Category -> Gas -> KGDCL, every Bangla
  screen within 70 UCS-2 characters) cover them. INPUT `lookup`
  (`service/Lookups`, arguments from the node's `mapping`): `bill` fetches the bill and adds
  `amount` (taka) and `partnerId` (the biller's aggregator) to the inputs; a refusal asks again
  with the adapter's message (messageBn in bn), an outage ends with `unavailable`. Bill fetch and
  catalogue go to `client/BillPayClient` (`mw.ussd.billpay.*`, contract
  `api/mw-adapter-billpay/billpay.v1.openapi.yaml`, internal HTTP over `app`: the documented
  read-only exception; read timeout 5 s, Resilience4j circuit breaker `billpay`, no retry).
  ACTION `mapping` values are input names or literals `=VALUE`; `MenuValidator` checks each flow's
  required fields (`TxnRequestMapper.REQUIRED_FIELDS`) and each lookup's arguments.
- Sessions: Redis `ussd:{mno}:{sessionId}` (`repository/RedisSessionStore`, JSON
  `dto/UssdSession`: node, path, inputs, language, menu version, pending pages), TTL = MNO session
  timeout + `session-grace` (30 s); a CONTINUE from another MSISDN is treated as expired. The PIN
  never reaches the session: it is encrypted on receipt (`util/PinCipher`, RSA-OAEP SHA-256 /
  MGF1-SHA-256 under the mw-crypto `PIN_TRANSPORT` key held by `service/PinTransport`) and handed
  straight to the ACTION, which sends it once to mw-auth (never to Kafka).
- PIN transport key (ticket 72, spec 18.7, D20): `service/PinTransport` asks mw-auth `GET /internal/v1/pin-key`
  (the internal port over the same mTLS client and service token as the assertion, `{keyId, algorithm,
  publicKeyPem, purpose}`; security review of ticket 72: never from the plain public port, and a key whose
  `purpose` is not `PIN_TRANSPORT` is refused as an outage, `dto/ChannelPinKey`) once the service is
  up (a failure is only logged `ussd.pin_key.unavailable`: mw-auth may start later; the first PIN asks again),
  keeps it `pin-key-ttl` (10 m) and drops it when mw-auth answers `MW-SYS-051` (mw-crypto refused the PIN
  block, e.g. a rotated key; that step ends `unavailable`, the next PIN uses the new key). No key can be had:
  the step ends `unavailable`. The core's PIN key (M11: `mw.ussd.pin.*`, its Vault copy) is gone.
- Transaction authorisation (ticket 72, D22; `client/AuthClient`, `config/AuthClientProperties`
  `mw.ussd.auth.*`, contract `api/mw-auth/auth-internal.v1.openapi.yaml`): before publishing,
  `KafkaTransactionFlow` sends `POST /internal/v1/txn-authorisations {msisdn, channel USSD, encryptedPin, txn
  {txnType, amount (valueMinor), currency, payer = debitParty {type, id}, payee = creditParty {type, id},
  idempotencyKey}}` built by `TxnRequestMapper.authorisation` from the very request it then publishes (mw-auth
  hashes these values into the assertion's `txnHash`; the core recomputes it from the request, so they must
  be identical: `TxnRequestMapperTest`), and publishes the request with `assertion` only. Over mTLS with this
  service's Vault certificate (`config/ServiceCertificateManager` + `TlsProperties` from the template,
  `mw.tls.enabled`: off in the image default and tests, `MW_TLS_ENABLED=true` in compose; PKI role `mw-ussd`)
  and a Keycloak realm `services` client-credentials JWT of client `mw-ussd` (`client/ServiceTokenClient`,
  audience mapper `aud-mw-auth`; secret `mw.ussd.auth.client-secret` in `secret/mw-ussd`, synced from
  `KC_MW_USSD_SECRET`; on mw-auth's internal allow-list and `mw.auth.assertion.callers`). HTTP/1.1, timeout
  2 s, Resilience4j breaker `mw-auth` (count based, 4xx ignored). mw-auth's error envelope is an answer
  (`exception/AuthRefusedException`): `MW-AUTH-040` (wrong PIN, `triesLeft`) -> flow `WRONG_PIN`, the engine
  shows the PIN input again with the menu's `wrongPin` text (`{triesLeft}` filled in), not counted as an
  invalid input: it keeps asking as long as mw-auth says wrong, mw-auth's lockout decides when that ends;
  `MW-AUTH-041` -> `pinLocked`, `042` -> `pinBlocked`, `043` -> `walletLocked`, each ending the session
  (flow `LOCKED`); any other refusal (`045` no PIN, `002`, `047`, `MW-IDEM-001`) -> `onFailure` with mw-auth's
  message. Nothing is published on a refusal. A 401 `MW-AUTH-001` (service token refused) drops the cached
  token and retries once; no other 401 is retried, so a PIN is checked once. Unreachable, 5xx, timeout, open
  breaker or an answer without an assertion -> `MW-SYS-012`, the step ends `unavailable`. The four PIN texts
  are in the seed menu (bn/en) and built into the engine (`UssdEngine.PIN_TEXTS`) for menus published without
  them; `MenuValidator` does not require them. Event `txn.authorisation.refused` (errorCode only); nothing of
  the PIN or the assertion is logged.
- ACTION -> `service/KafkaTransactionFlow`: idempotency key `sha256(USSD|msisdn|sessionId@startedAtMillis|menuPath|
  amountMinor|target)` hex (BALANCE: `...|menuPath|BALANCE|txnId`, a fresh key per check: its mapping is
  empty, so the fields alone would replay the first check) (the session start: SMPP session numbers are reused) (spec 7.3 rule 1, `service/IdempotencyKeys`; menuPath = the node ids and
  choices taken, e.g. `main:1>p2p.recipient>p2p.amount>p2p.confirm:1>p2p.pin`), UUIDv7 txnId,
  canonical `mw.txn.request.v1` (`mapper/TxnRequestMapper`: channel USSD, initiator MSISDN, debit
  the CUSTOMER wallet; credit per flow: P2P CUSTOMER wallet, CASH_OUT AGENT wallet, TOPUP bare
  MSISDN + partner {operator, productCode}, BILL_PAY `BILLER_ACCOUNT` + partner {aggregator,
  `BILL`, billerCode}; `metadata {ussdSessionId, mno}`, locale = session language), authorised by mw-auth
  (above) and published with `assertion` and `mw-reply-to = instanceId`; the MNO request's (virtual) thread waits at most
  `sync-budget` (8 s) on `service/ReplyWaiters`, completed by `config/StatusReplyConsumerConfig` /
  `messaging/StatusReplyListener` (group `mw-ussd.reply.<instanceId>`, latest offset, never
  blocks). COMPLETED -> the node's `onSuccess`; FAILED/REJECTED -> `onFailure` with the status
  error's text (messageBn in bn); no final status in time -> the menu's `processing` text ("being
  processed", the result by SMS once mw-notify sends it); publish failure -> `unavailable`.
  Flows: P2P, CASH_OUT, TOPUP, BILL_PAY, BALANCE (`TxnRequestMapper.FLOWS`); agents cash in from the Agent
  app, not over USSD.
- Business duplicate (ticket 99, FRS PLT-013): a FAILED `MW-VAL-024` from the orchestrator is the confirm
  node: the engine goes back to the PIN input with the menu's `duplicateSuspected` text (optional, built-in
  `UssdEngine.DUPLICATE_TEXT`) and the session input `_confirmDuplicate` (`FlowRequest.CONFIRM_DUPLICATE`);
  the PIN entered there is the yes (the PIN is never stored, so it is asked again) and that submit, same
  menu path and key, carries `confirmDuplicate: true`. Hanging up is the no. Asked once per session step.
- Balance inquiry (ADR D26): flow BALANCE is authorised and published like a transfer (amount 0 BDT,
  debitParty = creditParty = the CUSTOMER wallet, mw-auth txn `{BALANCE, 0, BDT, payer = payee}`, assertion).
  COMPLETED carries `balance {available, asOf}` (`dto/Balance` on `dto/StatusMessage`, into
  `FlowOutcome.balance`); the engine fills `{balance}` with `util/UssdText.taka` ("12,000.00", ASCII digits in bn
  and en). The balance is a screen variable only: never in the session, never logged (`@Sensitive` SECRET).
  PROCESSING / UNAVAILABLE of a BALANCE end with the menu's `balanceUnavailable` text (optional, built-in
  `UssdEngine.BALANCE_UNAVAILABLE_TEXT`), never `processing` (no SMS follows a balance check); WRONG_PIN, LOCKED
  and FAILED as for the other flows.
- Dev provisioning (`provision_mw_ussd` in `deploy/provision/services.sh`, run by the one-shot `provision` on every `docker compose up`, idempotent, dev only): role `ussd_rw`,
  schema `ussd`, `secret/mw-ussd` (DB password; Redis password from `REDIS_PASSWORD`;
  `mw.ussd.auth.client-secret` from `KC_MW_USSD_SECRET`), AppRole `mw-ussd`, PKI role `mw-ussd`. Keycloak
  realm `services` client `mw-ussd` (audience `mw-auth`): a running stack needs `deploy/reset.sh` to import it.
  Compose waits for a healthy mw-auth.

## Contracts owned

- `api/mw-ussd/ussd-http.v1.openapi.yaml` (MNO HTTP USSD API).

## Contracts used

- `contracts.yml` (module root) is the machine-readable list of the events and APIs here, checked by
  `./gradlew :contractsLint` and tested without Docker by `contracts/ProducedContractsTest` (every produced
  event built by `TxnRequestMapper` validates), `contracts/ConsumedContractsTest` (every valid sample reads
  into the own DTO) and `contracts/ApiContractsTest` (USSD HTTP API responses and the billpay client's requests).
- `events/mw-orchestrator/mw.txn.request.v1` (produced; own type `dto/TxnRequest`, validated in
  `TxnRequestMapperTest`).
- `events/mw-admin-api/mw.config.changed.v1` (consumed, ticket 39; own type `dto/ConfigChangedEvent`;
  `ConsumedContractsTest` runs every valid sample through `ConfigChangedListener.onConfigChanged`).
- `events/mw-orchestrator/mw.txn.status.v1` (consumed; own type `dto/StatusMessage`) and the
  orchestrator's `mw-reply-to` / duplicate-answer conventions.
- `api/mw-adapter-billpay/billpay.v1.openapi.yaml` (called: biller list, bill fetch; own types
  `dto/Biller`, `dto/Bill`; `client/BillPayClient`, copied from mw-app-gateway).
- `api/mw-auth/auth-internal.v1.openapi.yaml` (called, ticket 72: `POST /internal/v1/txn-authorisations`; own
  types `dto/TxnAuthorisationRequest`, `dto/TxnAuthorisation`; and `GET /internal/v1/pin-key`, `dto/ChannelPinKey`
  -> `dto/PinKey`), checked in `ApiContractsTest` (the mapper's request bodies, the answers). The public
  `api/mw-auth/auth.v1.openapi.yaml` is not used any more (security review of ticket 72).

## Tests (ticket 72)

- `client/AuthClientIT` (WireMock mw-auth over mTLS with a Vault PKI certificate, `PkiTestAuthority`):
  body and token, refusals as answers (a wrong PIN sent once, never opening the breaker), 401 MW-AUTH-001
  refresh, transport key, fail closed; `client/MtlsClientIT` (template copy). `service/KafkaTransactionFlowTest`
  (authorisation = published values, assertion published, refusals publish nothing, MW-SYS-051 drops the key),
  `service/PinTransportTest`, `UssdEngineTest` (wrong PIN asks again, locks end the session, unavailable). The
  `*IT` classes that start the app use `AuthStub` (one plain-HTTP WireMock per JVM: token, transport key from
  `util/TestPinKeys`, a fixed assertion; `AuthStub.refuse` for refusals; mTLS off in tests);
  `UssdHttpFlowIT` decrypts the PIN mw-auth received and checks it saw exactly the published values.
- Balance (ADR D26): `TxnRequestMapperTest` (BALANCE request and authorisation), `KafkaTransactionFlowTest` /
  `KafkaTransactionFlowKeyTest` (balance read from the status, a fresh key per check), `UssdEngineTest` (5 -> PIN ->
  balance, wrong PIN, lock, failure, no answer in time), `util/UssdTextTest`, and `UssdHttpFlowIT` over Kafka
  (`messaging/OrchestratorStub` answers a BALANCE with COMPLETED and `balance` 12,000.00).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (written inline in
  `build.gradle.kts`; drop once the Boot BOM ships them); the shared dev provisioning image `deploy/provision/Dockerfile` runs `apk upgrade` and removes gosu. `scripts/security.sh <this module>` scans the SBOM and the
  `mfs-mw/*:local` images (HIGH/CRITICAL fail; accepted ones in `.trivyignore.yaml` with a reason and expiry).
- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field. Inputs,
  the PIN block and the assertion are `SECRET`.
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
  Add context with `log.atInfo().addKeyValue("event", "...")` or the MDC.
- `config/MwServiceProperties`: config binding; secrets from Vault when `VAULT_ENABLED=true`.
- `messaging/EventPublisher`: Kafka records with the 6.1 headers (+ extra headers such as `mw-reply-to`)
  through Spring Kafka's `KafkaTemplate` (ADR D27), idempotent `acks=all` producer; `publish` waits at most
  `mw.kafka.send-timeout`. The admin client (asked for the cluster id by the observed template's first
  send) is bounded like the producer (`spring.kafka.admin.operation-timeout`), so Kafka down still fails fast.
- Spring Kafka (ADR D27, copied from the template, ticket 80): `config/KafkaConfig` (non-blocking retry topics
  `<topic>.mw-ussd.retry.1..3` then `<topic>.mw-ussd.dlq` for every `@KafkaListener` on the default factory,
  `messaging/RetryTopicNaming`, `RetryTopicRecoverer` (`mw-retry-*` headers, class only), `RetryDueTimeCap`,
  `ConsumerContextInterceptor` (consumer MDC), `Utf8Deserializer`; validation/schema failures to the DLQ at
  once); retry topics 10 s / 1 min / 10 min (`mw.kafka.retry.delays`); per-record manual ack; no sleeps on
  Kafka threads. Consumers: `messaging/ConfigChangedListener` (retried, group `mw-ussd`) and
  `config/StatusReplyConsumerConfig`: the per-instance reply consumer of `mw.txn.status.v1`, a container built from its own
  `statusReplyContainerFactory` (not a `@KafkaListener`, so the retry configuration never applies to it), group
  `mw-ussd.reply.<instanceId>`, `auto-offset-reset=latest`, auto-commit, no retry topic or DLQ, an error handler
  that logs the exception class and skips; `messaging/StatusReplyListener.onReply` only completes futures.
  `messaging/StatusReplyConsumerIT` runs two instances: each gets its replies, none sent before it started.
- `scheduler/OutboxRelayScheduler`: the outbox relay schedule (`mw.outbox.relay.period`, ShedLock-guarded).
- `application.yml`: Resilience4j defaults (19.2), own schema `ussd` migrated by Flyway on start
  from `db/migration` (16.4).

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-ussd:test` | every test in one JVM: unit, slice and contract tests (`contracts/ProducedContractsTest`, `ConsumedContractsTest`, `ApiContractsTest`), the Testcontainers `*IT` classes (PostgreSQL, Kafka, Redis) and the `@Conformance` suite (outbox, inbox, HTTP, purge); one PostgreSQL and one Kafka per test JVM, a fresh database per Spring context; the `*IT` and conformance classes are skipped without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-ussd:build` | `test`, then the boot jar `build/libs/mw-ussd.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-ussd:bootJar && docker build -t mw-ussd:dev services/mw-ussd` | image, as spec 4.3 rule 1 |
| `./gradlew clean build` | the full gate: every module, plus the repo checks (`:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`); unchanged modules come from the build cache |
| `./gradlew :mw-ussd:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-ussd` | rebuild this service's image (`mfs-mw/mw-ussd:local`) and restart it with what it depends on (dev timers from `deploy/.env`) |
| `scripts/api-test.sh "18, 19, 20"` | P2P, cash-out, top-up and bill pay over the HTTP USSD gateway simulator (`tests/api/src/18-ussd`, `19-ussd-flows`) and GP over SMPP (`20-ussd-smpp`) |
