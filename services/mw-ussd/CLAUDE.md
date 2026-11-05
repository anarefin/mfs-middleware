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
  route in memory). Session id `smpp-<number>-<sha256(msisdn) 16 hex>` (no MSISDN in Valkey keys).
  Each step runs on a virtual thread, never on jSMPP's PDU threads. Text GSM-7 unpacked or UCS-2
  (`util/UssdText`). Dev: gp binds 2 to `mw-simulators:2776`; robi, bl, teletalk
  endpoints config-ready. `mw.ussd.smpp.enabled=false` binds nothing (tests). SMPP password key
  `mw.ussd.smpp.mno.<mno>.password` in OpenBao (empty in dev: the simulator accepts any bind).
- HTTP transport: `POST /ussd/{mno}` (`controller/UssdHttpController`, contract
  `api/mw-ussd/ussd-http.v1.openapi.yaml`), JSON `{sessionId, msisdn, serviceCode?, input?,
  type: BEGIN|CONTINUE|END|ABORT}` -> `{sessionId, message, flag}` with the MNO's flag values
  (`mw.ussd.mno.<mno>.continue-flag` / `end-flag`, CON / END). Only MNOs with `transport: HTTP`
  are served (others 404 `MW-VAL-003`); MSISDN `01X...`, `8801X...`, `+8801X...` (else 400
  `MW-VAL-001`), canonical `01XNNNNNNNN` from there on. Behind APISIX routes `ussd-http-<mno>`
  (`/ussd/<mno>`, `deploy/apisix/routes/ussd-http-<mno>.json`: the MNO's ip allow-list
  `USSD_<MNO>_ALLOWED_CIDRS`, basic-auth as consumer `ussd_<mno>` only (another MNO's credential
  403), 4 KiB body cap, quota per MSISDN hash; this service sees no credential).
- Per MNO (`mw.ussd.mno.<gp|robi|bl|teletalk>`, spec 9.2; `config/UssdProperties`): transport,
  short codes, screen limits (182 GSM-7 / 70 UCS-2), session timeout, overflow TRUNCATE|PAGE.
  Dev: gp SMPP; robi, bl HTTP + PAGE; teletalk HTTP + TRUNCATE. `operator` = the MNO's top-up partnerId
  at mw-adapter-telco, filled into `{mno}` of an own-number top-up (bl: `banglalink`; default the key).
- Menus are data (`dto/Menu`, `MenuNode`, node types MENU, INPUT, CONFIRM, ACTION, DYNAMIC, END,
  text `{bn, en}`): versioned JSON in `ussd.menu`, activated per MNO with a go-live time in
  `ussd.menu_activation` (V4). `service/MenuService` returns the live version for an MNO (latest
  activation whose go-live passed) or a session's own version (a running session finishes on the
  version it started on); versions are immutable and cached once parsed. `service/MenuValidator`
  refuses a tree with dangling references, missing fields, an unknown validator/flow, a regex that
  does not compile, or a PIN input that is stored or does not lead straight to an ACTION.
  `service/MenuSeeder` stores the dev seed `menus/main-v2.json` (`mw.ussd.seed-menu`, blank = off)
  and activates it for every configured MNO without a menu or still on an older version of the
  seed (v1 -> v2; another menu is never replaced), until the admin console edits menus (WP23,
  maker-checker). Seed (ticket 28): 1 Send Money (P2P), 2 Cash Out (customer to agent), 3 Mobile
  Recharge (1 own number: option `set` stores `{msisdn}` / `{mno}` as target and operator; 2 other
  number + DYNAMIC `operators`; product `=PREPAID`), 4 Pay Bill (DYNAMIC `billers`, bill number
  INPUT with `lookup: bill`, CONFIRM with the fetched amount), 9 language. Every Bangla screen of
  the seed fits 70 UCS-2 characters (`UssdMenuWalkTest`).
- Engine (`service/UssdEngine`): one step per request, never throws (a failure ends with the
  menu's `unavailable` text). Language `mw.ussd.default-language` (bn) at dial-in, toggled by a
  MENU option with `switchLanguage` (no profile lookup yet). Validators (`service/InputValidator`):
  msisdn (canonical, `notSelf`), amount (taka, <= 2 decimals, min/max, stored as `500.00`), pin
  (4 digits unless `pattern`), numeric (digit count min/max), regex. A bad input re-prompts with
  the validator's `error` (or the menu's `invalid`) above the screen; `max-invalid-attempts` (3)
  in a row end the session. Placeholders `{input}` / `{txnId}` / `{error}` in texts. A screen over
  the MNO limit is cut (TRUNCATE) or paged with the menu's `more` option ("0 More", PAGE).
  DYNAMIC sources (`service/DynamicLists`): `billers` = mw-adapter-billpay's live catalogue
  (`service/BillPayDirectory`, sorted by code, label = code, cached `catalogue-ttl` 60 s and kept
  through an outage); others static from `mw.ussd.lists.<source>` (`operators`). INPUT `lookup`
  (`service/Lookups`, arguments from the node's `mapping`): `bill` fetches the bill and adds
  `amount` (taka) and `partnerId` (the biller's aggregator) to the inputs; a refusal asks again
  with the adapter's message (messageBn in bn), an outage ends with `unavailable`. Bill fetch and
  catalogue go to `client/BillPayClient` (`mw.ussd.billpay.*`, contract
  `api/mw-adapter-billpay/billpay.v1.openapi.yaml`, internal HTTP over `app`: the documented
  read-only exception; read timeout 5 s, Resilience4j circuit breaker `billpay`, no retry).
  ACTION `mapping` values are input names or literals `=VALUE`; `MenuValidator` checks each flow's
  required fields (`TxnRequestMapper.REQUIRED_FIELDS`) and each lookup's arguments.
- Sessions: Valkey `ussd:{mno}:{sessionId}` (`repository/ValkeySessionStore`, JSON
  `dto/UssdSession`: node, path, inputs, language, menu version, pending pages), TTL = MNO session
  timeout + `session-grace` (30 s); a CONTINUE from another MSISDN is treated as expired. The PIN
  never reaches the session: it is encrypted on receipt (`util/PinCipher`, RSA-OAEP SHA-256 with
  the core's public key, `mw.ussd.pin.key-id` / `public-key` from OpenBao) and handed straight to
  the ACTION, forwarded once in `encryptedPin`.
- ACTION -> `service/KafkaTransactionFlow`: idempotency key `sha256(USSD|msisdn|sessionId|menuPath|
  amountMinor|target)` hex (spec 7.3 rule 1, `service/IdempotencyKeys`; menuPath = the node ids and
  choices taken, e.g. `main:1>p2p.recipient>p2p.amount>p2p.confirm:1>p2p.pin`), UUIDv7 txnId,
  canonical `mw.txn.request.v1` (`mapper/TxnRequestMapper`: channel USSD, initiator MSISDN, debit
  the CUSTOMER wallet; credit per flow: P2P CUSTOMER wallet, CASH_OUT AGENT wallet, TOPUP bare
  MSISDN + partner {operator, productCode}, BILL_PAY `BILLER_ACCOUNT` + partner {aggregator,
  `BILL`, billerCode}; `metadata {ussdSessionId, mno}`, locale = session language)
  published with `mw-reply-to = instanceId`; the MNO request's (virtual) thread waits at most
  `sync-budget` (8 s) on `service/ReplyWaiters`, completed by `route/StatusReplyRoute` /
  `messaging/StatusReplyListener` (group `mw-ussd.reply.<instanceId>`, latest offset, never
  blocks). COMPLETED -> the node's `onSuccess`; FAILED/REJECTED -> `onFailure` with the status
  error's text (messageBn in bn); no final status in time -> the menu's `processing` text ("being
  processed", the result by SMS once mw-notify sends it); publish failure -> `unavailable`.
  Flows: P2P, CASH_OUT, TOPUP, BILL_PAY (`TxnRequestMapper.FLOWS`); agents cash in from the Agent
  app, not over USSD.
- Init (`mw-ussd-init`, label `mw.oneshot`, `init/init.sh`, idempotent, dev only): role `ussd_rw`,
  schema `ussd`, `secret/mw-ussd` (DB password; Valkey password from `VALKEY_PASSWORD`; the core's
  PIN public key and key id copied from `secret/mw-core-mock`, playing the core team's hand-over),
  AppRole `mw-ussd`.

## Contracts owned

- `api/mw-ussd/ussd-http.v1.openapi.yaml` (MNO HTTP USSD API).

## Contracts used

- `events/mw-orchestrator/mw.txn.request.v1` (produced; own type `dto/TxnRequest`, validated in
  `TxnRequestMapperTest`).
- `events/mw-orchestrator/mw.txn.status.v1` (consumed; own type `dto/StatusMessage`) and the
  orchestrator's `mw-reply-to` / duplicate-answer conventions.
- `api/mw-adapter-billpay/billpay.v1.openapi.yaml` (called: biller list, bill fetch; own types
  `dto/Biller`, `dto/Bill`; `client/BillPayClient`, copied from mw-app-gateway).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field. Inputs
  and the PIN ciphertext are `SECRET`.
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
  Add context with `log.atInfo().addKeyValue("event", "...")` or the MDC.
- `config/MwServiceProperties`: config binding; secrets from OpenBao when `VAULT_ENABLED=true`.
- `messaging/EventPublisher`: Kafka records with the 6.1 headers, idempotent `acks=all` producer
  (max-block 3 s, delivery timeout 5 s: inside the 8 s budget).
- `route/RetryTopicRouteBuilder` + `messaging/RetryDelayListener`: subclass per consumed topic; retry
  topics 10 s / 1 min / 10 min then DLQ (19.2); per-record commit; no sleeps on Kafka threads.
- `application.yml`: Resilience4j defaults (19.2), own schema `ussd` migrated by Flyway on start
  from `db/migration` (16.4).

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | unit, slice and Testcontainers tests (Docker needed for `*IT`: PostgreSQL, Kafka, Valkey), JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-ussd:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-ussd` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make up M=mw-ussd` | run this service's `compose.yml` (platform project must be up) |
| `make e2e TAGS=@ussd` | P2P, cash-out, top-up and bill pay over the HTTP USSD gateway simulator (`tests/e2e/src/test/resources/features/ussd.feature`, `ussd-flows.feature`) and GP over SMPP (`ussd-smpp.feature`, `@smpp`) |
