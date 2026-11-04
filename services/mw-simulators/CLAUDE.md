# mw-simulators

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.simulators`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Partner simulators for tests only (spec 22.2, WP12), one generic contract per partner type.
  Today: OCS (prepaid top-up), the utility bill aggregator `billagg` (ticket 21) and the C2W bank
  `c2wbank` (ticket 23). State is in
  memory (a restart resets it); never deployed to production projects. Outcomes are
  `dto/SimMode` (SUCCESS, BUSINESS_FAILURE, TIMEOUT, SERVER_ERROR_AFTER_SEND, UNKNOWN_CODE,
  MALFORMED; the control API's `forceMode`), amount magic values in `service/MagicAmounts`.
- Shared scenario mechanics (`service/ScenarioStore`, `RequestRecorder`, `CallbackService`):
  - Magic values (OCS): MSISDN ending `999` -> business failure; amount fraction `.13` ->
    timeout (top-up applied, reply held `sim.timeout-delay`, default 30 s), `.77` -> 500 after
    send (applied), `.99` -> unknown code `9876`, `.55` -> malformed body (applied).
  - Control API per partner (`sim.partners`): `POST|GET|DELETE /sim/{partner}/scenario`
    (`forceMode`, `latencyMs`, `errorRate`, `outage`, `timeoutDelayMs`, `callbackMode`
    NONE|ONCE|DUPLICATE|LATE|OUT_OF_ORDER (outcome, then a stale PENDING; billagg),
    `callbackUrl`, `callbackDelayMs`, `callbackOnly` (billagg: payments answered 202 PENDING,
    outcome by callback only)), `GET|DELETE /sim/{partner}/requests[?path=]`, `POST
    /sim/{partner}/callbacks/{reference}/replay` (optional body `{callbackUrl}`; OCS ledger or
    billagg payments; answers `{reference, targetStatus}`).
  - Callbacks (`service/CallbackService`, `client/CallbackClient`, `client/CallbackSigner`): the
    partner's own reply body for the reference, posted to `callbackUrl` off the request thread,
    signed hmac-sha256 at send time when `sim.callback-secrets.<partner>` is set (string to sign:
    method, path+query, epoch seconds, hex SHA-256 of the body; `X-Signature`, `X-Timestamp`; the
    middleware's `hmac-sha256` scheme). In the stack the billagg secret is the generated
    `BILLAGG_HMAC_SECRET`, written to OpenBao `secret/mw-simulators` by `init/init.sh`; the e2e
    suite points `callbackUrl` at `http://apisix:9080/partner/billagg/callback/payments`.
  - `filter/RecordingFilter` records every request to `/{partner}/**` (raw bodies, test data;
    Authorization and Cookie headers dropped), last `sim.record-limit` per partner.
- OCS wire format: `POST /ocs/v1/topup {transactionId, msisdn, amount:"100.00"}`; `GET
  /ocs/v1/topup/{transactionId}` returns the real outcome (200, or 404 NOT_FOUND). Replies:
  `{transactionId, status SUCCESS|FAILED|NOT_FOUND|UNAVAILABLE|ERROR, code, message, ocsRef}`;
  success 200 code `0000`, failure 422 (`2001` business, `9876` unknown), outage / error rate 503
  (not applied). A repeated `transactionId` returns the original outcome.
- Bill aggregator wire format (`billagg`, `controller/BillAggregatorController`,
  `service/BillAggregatorService`, `BillPaymentLedger`): `GET /billagg/v1/billers` -> `[{code,
  name, category}]` (DESCO, DPDC, WASA, TITAS); `POST /billagg/v1/bills/fetch {billerCode,
  billNumber}` -> 200 `{billerCode, billNumber, status: DUE, code: "000", amount: "667.00",
  dueDate, billPeriod}` with amount due = 100 taka + the bill number's last three digits, or 422
  `{status: FAILED, code}`: bill number ending `404` -> `3001` not found, ending `000` -> `3002`
  already paid, unknown biller -> `3004`; `POST /billagg/v1/payments {transactionId, billerCode,
  billNumber, amount}` -> `{transactionId, status, code, message, paymentRef}`: success 200 code
  `000` (`paymentRef` `AGG-n`), bill number ending `999` -> 422 `3003` refused by the biller,
  amount fractions as the OCS (`.13` timeout applied, `.77` 500 applied, `.99` 422 `9876`, `.55`
  malformed applied); `GET /billagg/v1/payments/{transactionId}` the real outcome or 404
  NOT_FOUND. Outage / error rate 503 on all three (not applied); `forceMode` applies to payments
  only. A repeated `transactionId` returns the original outcome. Callback-only mode (ticket 22,
  scenario `callbackOnly`): a payment is applied as usual but answered 202 `{status: PENDING,
  code: "001", paymentRef}` (every repeat too), the outcome goes by callback (`callbackMode`) as
  `{transactionId, status SUCCESS|FAILED, code, message, paymentRef}`, the status inquiry reports
  the real outcome; the TIMEOUT hold is skipped in this mode.
- C2W bank wire format (`c2wbank`, ticket 23, `controller/BankC2wController`,
  `service/BankC2wService`, `BankDebitLedger`, `mapper/BankDebitMapper`): `POST /c2wbank/v1/debits
  {transactionId, accountNumber ([0-9]{6,20}, else 400), amount}` -> always 202 `{transactionId,
  status: PENDING, code: "100", message, debitRef: "C2W-n"}` (every repeat too, one debit per
  `transactionId`); the outcome goes by callback (`callbackMode`; ONCE, DUPLICATE, LATE,
  OUT_OF_ORDER) as `{transactionId, status SUCCESS|FAILED, code, message, debitRef}`: success
  `000`; account ending `999` -> FAILED `4002` debit refused; amount fractions `.13` timeout (debit
  applied, 202 held `sim.timeout-delay`, NO callback: only the status inquiry learns it), `.77`
  500 (applied, callback sent), `.99` FAILED `9876` (ambiguous), `.55` malformed 202 body
  (applied, callback sent). `GET /c2wbank/v1/debits/{transactionId}` the real outcome or 404
  NOT_FOUND. Outage / error rate 503 (not applied). Callbacks signed with
  `sim.callback-secrets.c2wbank` (stack: `C2WBANK_HMAC_SECRET`, written by `init/init.sh`); the
  e2e suite points `callbackUrl` at `http://apisix:9080/partner/c2wbank/callback/debits`; replay
  through the shared `POST /sim/c2wbank/callbacks/{reference}/replay`.
- SMSC (ticket 24, `messaging/SmscServer` on jSMPP, `service/SmscService`,
  `controller/SmscController`): SMPP 3.4 server on `sim.smsc.port` (2775, internal network only;
  `sim.smsc.enabled`, off in tests except `SmscSimulatorIT`). Accepts any bind (no credential
  check); every `submit_sm` gets `SMSC-n`; GSM-7 (data_coding 0, unpacked) and UCS-2 (8)
  decoded, concatenated parts (UDH IE 0x00/0x08) reassembled. A DLR (`deliver_sm`, SMSC delivery
  receipt, `id:... stat:...`) goes back on the same session after `sim.smsc.dlr-delay` (300 ms).
  Destination magic suffixes: `999` DLR UNDELIV, `998` DLR EXPIRED, `997` first two submits
  refused 0x58 (throttled) then accepted, `996` refused 0x0B, `995` no DLR; else DELIVRD.
  Control API: `GET /sim/smsc/messages[?msisdn=]` -> `[{destination, source, text, encoding
  GSM7|UCS2, segments, messageIds, dlrStatus, receivedAt}]`, `DELETE /sim/smsc/messages`.
- USSD gateway simulator, HTTP mode (ticket 26, `controller/UssdGatewayController`,
  `service/UssdGatewayService`, `client/UssdGatewayClient`): plays an MNO whose USSD transport is
  HTTP. `POST /sim/ussdgw/sessions {mno, msisdn, serviceCode?, sessionId?, inputs[]}` sends BEGIN,
  then one CONTINUE per input to `sim.ussd-gateway.target-url` + mno (stack: `http://apisix:9080/ussd/`,
  APISIX route `ussd-http` -> mw-ussd; wire format `contracts/api/mw-ussd/ussd-http.v1.openapi.yaml`)
  and stops when mw-ussd answers the end flag (`sim.ussd-gateway.end-flag`, END); answers
  `{sessionId, screens: [{status, message, flag}], ended}`. Not a partner in `sim.partners` (no
  recording: inputs carry PINs).
- USSD gateway simulator, SMPP mode (ticket 27, `client/UssdSmppGateway`): SMPP 3.4 server on
  `sim.ussd-gateway.smpp.port` (2776, internal network; `sim.ussd-gateway.smpp.enabled`, off in
  tests except `UssdSmppGatewayIT`) that mw-ussd binds to as transceiver (any bind accepted).
  MNOs in `sim.ussd-gateway.smpp.mnos` ([gp]) are played over SMPP by the same `POST
  /sim/ussdgw/sessions`: `deliver_sm` with `ussd_service_op` 1 (dial, short message = service
  code) then 18 per input, `its_session_info` session number 1-128 (round robin over the bound
  sessions); each `submit_sm` of that session is a screen `{status: 200, message, flag}` (END when
  op 17 or the end-of-session bit, else CON); no screen within `screen-timeout` (15 s) or no bind
  -> `{status: -1}` and the play stops. Control: `POST /sim/ussdgw/smpp/scenario {throttleNext,
  queueFullNext, genericNackNext, silentEnquireLink, screenTimeoutMs}` (replaces; `{}` resets;
  refused submits answer 0x58 / 0x14 / generic_nack), `POST /sim/ussdgw/smpp/drop` (closes every
  bind), `GET /sim/ussdgw/smpp/binds` -> `{bound, binds, enquireLinks, refused}`.
- Adding a simulator: add its name to `sim.partners`, a controller under `/<partner>/...`, reuse
  the store, recorder, `SimMode`/`MagicAmounts` and callback service.
- Init (`mw-simulators-init`, label `mw.oneshot`, `init/init.sh`, idempotent, dev only): role
  `simulators_rw` and schema `simulators` (the chassis still migrates one on start-up),
  `secret/mw-simulators` (DB password, never overwritten) and the AppRole `mw-simulators`
  (configtree volume at `/run/mw/approle`); waits for OpenBao's `dev-init.json` and retries the
  database step when another init races it.

## Contracts owned

- None. The control API and partner wire formats are simulator-owned and not in `contracts/`.

## Contracts used

- `api/mw-ussd/ussd-http.v1.openapi.yaml` (client, USSD gateway simulator; own types `dto/UssdScreen`).
- Otherwise none. (Later tickets add the core stub: `core.*.command` in, `core.*.event` out, spec 22.2.)

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

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
- `application.yml`: Resilience4j defaults (19.2), own schema `simulators` migrated by Flyway on
  start from `db/migration` (16.4); rename the schema to the spec 16.2 name if it has one.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | unit, slice and Testcontainers tests (Docker needed for `*IT`), JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-simulators:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-simulators` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make up M=mw-simulators` | run this service's `compose.yml` (platform project must be up) |
