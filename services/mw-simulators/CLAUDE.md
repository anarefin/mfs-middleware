# mw-simulators

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.simulators`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Partner simulators for tests only (spec 22.2, WP12), one generic contract per partner type.
  Today: OCS (prepaid top-up), the utility bill aggregator `billagg` (ticket 21) and the C2W bank
  `c2wbank` (ticket 23). State is in
  memory (a restart resets it); never deployed to production projects. Outcomes are
  `dto/SimMode` (SUCCESS, BUSINESS_FAILURE, TIMEOUT, SERVER_ERROR_AFTER_SEND, UNKNOWN_CODE,
  MALFORMED, CONNECTION_RESET, PARTIAL_SUCCESS), decided by mock control (below) with the amount magic
  values of `service/MagicAmounts` as the default.
- Mock control (ticket 102, ADR D33, FRS MCK-03..07, MCT-01..03, MCT-06; `service/mockcontrol/`,
  `controller/MockControlController`, contract `api/mw-simulators/mock-control.v1.openapi.yaml`): one engine
  for every mock party, only in the Spring profile `mock-control` (`MockControl.PROFILE`; the compose block
  sets `SPRING_PROFILES_ACTIVE: mock-control`, the test config too). Without the profile `MagicValuesOnly`
  answers every call by its magic value and no control path, journal or alias exists (MCT-08 hardening:
  ticket 103).
  - Parties (`MockParties`, `GET /mock-control/parties`): `ocs` (topup, status, reverse; keys msisdn,
    txnRef), `billagg` (billers, fetch, pay, status, reverse; biller, billNumber, txnRef; REST and SOAP
    front ends), `c2wbank` (debit, status, reverse; account, txnRef), `merchant` (ipn; ipnId, path). A
    party's name is the first segment of its wire paths (the journal's key). A new mock adds a line there
    and calls `MockControl.decide(party, operation, keys, magic)` per operation, then carries the
    `Decision` out (`MockCalls.admit` / `MockCalls.answer`).
  - `ScenarioEngine`: `POST|GET|DELETE /mock-control/{party}/scenarios[/{id}]` `{operation?, behaviour,
    code?, delayMs?, httpStatus?, key?, nextN?}` (201 with `id`, `remaining`; an operation the party lacks
    400 MW-VAL-001, unknown party 404 MW-VAL-003). Behaviours (`dto/MockBehaviour`), three dimensions decided
    separately per call: outcome SUCCESS, BUSINESS_ERROR (`code` replaces the party's refusal code; merchant
    `httpStatus`, default 400), TIMEOUT (applied, reply held `delayMs`, default the settings'
    `timeoutDelayMs`, else `sim.timeout-delay`), HTTP_5XX (applied, `httpStatus` default 500),
    MALFORMED_RESPONSE (applied, body cut short), CONNECTION_RESET (applied, connection closed unanswered),
    PARTIAL_SUCCESS (the party's partial reply: status `PARTIAL`, ocs `0002`, billagg / c2wbank `010`);
    delay DELAY (`delayMs`, outcome as without it); callback DUPLICATE_CALLBACK, LATE_CALLBACK (`delayMs`),
    OUT_OF_ORDER_CALLBACK (outcome then a stale PENDING), NO_CALLBACK. Lookup per dimension: next-N scoped,
    per key (any key value of the call), per operation, party-wide (latest wins ties); none -> the magic
    value (MCK-03); none -> SUCCESS. A next-N scenario counts down only on calls it decided. On read
    operations (status, billers, fetch, reverse) BUSINESS_ERROR refuses without applying, the others as above.
  - `PartySwitch`: `PUT|GET /mock-control/{party}/switch {state UP|DOWN|MAINTENANCE|SLOW, latencyMinMs,
    latencyMaxMs, ratio}`: DOWN closes the connection unanswered (`exception/MockAbortException` ->
    `GlobalExceptionHandler` marks the request -> `filter/MockAbortValve`, a Tomcat valve registered in
    `config/SimulatorConfig`, drops the buffered answer and closes the socket), MAINTENANCE 503
    `UNAVAILABLE` `5030`, nothing applied in either, checked before any scenario (none is used); the
    latency range applies in every state; `ratio` = share of calls DOWN/MAINTENANCE hits. `PUT|GET
    /mock-control/{party}/settings {callbackUrl, callbackOnly, timeoutDelayMs}`.
  - `Journal` (MCK-07): `filter/RecordingFilter` (registered by `config/MockControlConfig`) records every
    request to `/<party>/**` (raw bodies, test data; Authorization and Cookie headers dropped; the text of a
    WS-Security `Password` element recorded as `***`, `util/SoapXml.redactPasswords`), last
    `sim.record-limit` per party. `GET /mock-control/{party}/journal[?path=&contains=&afterSeq=]`; `DELETE`
    clears it and resets the party (`PartyReset`: scenarios, switch, settings and every `PartyState`: the
    ledgers, reversals, received IPNs; reference counters keep counting).
  - Callbacks (`service/CallbackService`, `client/CallbackClient`, `client/CallbackSigner`): after an
    applied call, the partner's own reply body for the reference, posted to the settings' `callbackUrl`
    (none = no callbacks) off the request thread as the decision says (once, twice, late, out of order,
    none), signed hmac-sha256 at send time when `sim.callback-secrets.<partner>` is set (string to sign:
    method, path+query, epoch seconds, hex SHA-256 of the body; `X-Signature`, `X-Timestamp`; the
    middleware's `hmac-sha256` scheme). In the stack the billagg secret is the generated
    `BILLAGG_HMAC_SECRET`, written to Vault `secret/mw-simulators` by `deploy/provision/services.sh`; the e2e
    suite points `callbackUrl` at `http://apisix:9080/partner/billagg/callback/payments`. `POST
    /mock-control/{party}/callbacks/{reference}/replay` (optional body `{callbackUrl}`; answers
    `{reference, targetStatus}`).
  - Deprecated aliases for one release (`controller/ControlController`, `LegacyScenarios`; one
    `sim.control.deprecated` log line): `POST|GET|DELETE /sim/{partner}/scenario` (ocs, billagg, c2wbank;
    `dto/Scenario`: `forceMode` = a party-wide scenario on topup / pay / debit, UNKNOWN_CODE = BUSINESS_ERROR
    `9876`, SERVER_ERROR_AFTER_SEND = HTTP_5XX; `outage` = MAINTENANCE, `errorRate` = MAINTENANCE with that
    ratio, `latencyMs` = SLOW fixed; `callbackMode` DUPLICATE / LATE / OUT_OF_ORDER / NONE = the callback
    scenarios; `callbackUrl`, `callbackOnly`, `timeoutDelayMs` = settings; each POST replaces the party's
    control state), `GET|DELETE /sim/{partner}/requests[?path=]` (the journal), `POST
    /sim/{partner}/callbacks/{reference}/replay`, `POST /sim/merchant/ipn/scenario {failNext, failStatus?,
    ipnId?}` (a next-N scenario on `merchant`/ipn) and `DELETE /sim/merchant/ipn/received`.
  - Magic values (OCS): MSISDN ending `999` -> business failure; amount fraction `.13` -> timeout (top-up
    applied, reply held `sim.timeout-delay`, default 30 s), `.77` -> 500 after send (applied), `.99` ->
    unknown code `9876`, `.55` -> malformed body (applied). `dto/SimMode` is the resolved outcome.
  - Not on the engine yet (follow-up of ticket 102): the SMSC, the USSD gateway (HTTP and SMPP) and the
    payShield simulator keep their own switches below; the SOAP stub biller and the partner stubs too.
- OCS wire format: `POST /ocs/v1/topup {transactionId, msisdn, amount:"100.00"}`; `GET
  /ocs/v1/topup/{transactionId}` returns the real outcome (200, or 404 NOT_FOUND). Replies:
  `{transactionId, status SUCCESS|FAILED|NOT_FOUND|UNAVAILABLE|ERROR, code, message, ocsRef}`;
  success 200 code `0000`, failure 422 (`2001` business, `9876` unknown), MAINTENANCE 503
  (not applied). A repeated `transactionId` returns the original outcome.
- Bill aggregator wire format (`billagg`, `controller/BillAggregatorController`,
  `service/BillAggregatorService`, `BillPaymentLedger`): `GET /billagg/v1/billers` -> `[{code,
  name, category, protocol: REST|SOAP}]`, 10 billers (`BillAggregatorService.BILLERS`): REST BREB, CWASA,
  DESCO, DPDC, TITAS, WASA; SOAP BTCL, JGTDSL, KGDCL, LINK3 (categories ELECTRICITY, WATER, GAS, TELECOM,
  INTERNET); `POST /billagg/v1/bills/fetch {billerCode,
  billNumber}` -> 200 `{billerCode, billNumber, status: DUE, code: "000", amount: "667.00",
  dueDate, billPeriod}` with amount due = 100 taka + the bill number's last three digits, or 422
  `{status: FAILED, code}`: bill number ending `404` -> `3001` not found, ending `000` -> `3002`
  already paid, unknown biller -> `3004`; `POST /billagg/v1/payments {transactionId, billerCode,
  billNumber, amount}` -> `{transactionId, status, code, message, paymentRef}`: success 200 code
  `000` (`paymentRef` `AGG-n`), bill number ending `999` -> 422 `3003` refused by the biller,
  amount fractions as the OCS (`.13` timeout applied, `.77` 500 applied, `.99` 422 `9876`, `.55`
  malformed applied); `GET /billagg/v1/payments/{transactionId}` the real outcome or 404
  NOT_FOUND. MAINTENANCE 503 on all three (not applied). A repeated `transactionId` returns the original outcome. Callback-only mode (ticket 22,
  setting `callbackOnly`): a payment is applied as usual but answered 202 `{status: PENDING,
  code: "001", paymentRef}` (every repeat too), the outcome goes by callback as
  `{transactionId, status SUCCESS|FAILED, code, message, paymentRef}`, the status inquiry reports
  the real outcome; the TIMEOUT hold is skipped in this mode. Protocol guard (proves the adapter's
  route picked the right protocol): a SOAP biller on the REST fetch or payment endpoint is refused
  422 `3004` unknown biller (a payment is not applied); a code not in the list at all is still paid
  over REST, as before.
- billagg SOAP front end (`controller/BillAggregatorSoapController`, `service/BillAggregatorSoapService`):
  `POST /billagg/v1/soap` (SOAP 1.1, `text/xml` or `application/soap+xml`, namespace `urn:billagg:v1`,
  SOAPAction `urn:billagg:v1:FetchBill|PayBill`, the body element decides) for the SOAP billers only.
  Every request carries a WS-Security 1.0 `wsse:Security` header with a UsernameToken (`PasswordText`,
  as the adapters' `mw-soap-call` builds it), user `bits`, password the property `sim.billagg.ws-password`
  (read from the `Environment` like `sim.callback-secrets.*`; stack: `BILLAGG_WS_PASSWORD` in Vault
  `secret/mw-simulators`; no value in the main config; unset = every call refused); missing or wrong ->
  fault `1002`. `FetchBill {billerCode, billNumber}` -> `FetchBillResponse {billerCode, billNumber,
  amountDue, dueDate, billPeriod}`; `PayBill {billerCode, billNumber, amount, reference}` ->
  `PayBillResponse {status SUCCESS|PENDING, paymentRef, code (000, or 001 when PENDING)}`. Both delegate to
  `BillAggregatorService` (protocol SOAP): the same amounts, magic bill numbers and amounts, scenarios
  (partner `billagg`), timeout hold, callback-only PENDING and callbacks, and the same payment store
  (`reference` = `transactionId`: one payment per reference, a repeat returns the same reply, `GET
  /billagg/v1/payments/{reference}` finds it). Faults (HTTP 500, `<detail><b:error><b:code/><b:message/>`):
  `soap:Client` `3001` / `3002` / `3003` / `9876` as the REST codes, `3004` for a REST or unknown biller,
  `1000` unreadable request, DOCTYPE, unknown operation or invalid field, `1002` authentication; `soap:Server`
  `5030` MAINTENANCE, `5000` the 500-after-send mode; the malformed mode answers a cut-short
  envelope (200). Recorded under `billagg` like the REST calls. Tests: `BillAggregatorSoapIT`.
- SOAP stub biller (`soapbill`, ticket 85, `controller/SoapBillerController`, `service/SoapBillerService`;
  parser, envelope and fault helpers shared with the billagg SOAP front end in `util/SoapXml`):
  the SOAP case of the adapters' `mw-soap-call` (`deploy/partner-routes/examples/soap-biller.yaml`). `POST
  /soapbill/v1` (SOAP 1.1, `text/xml` or `application/soap+xml`, namespace `urn:soapbill:v1`): `FetchBill
  {billNumber}` -> `FetchBillResponse {billNumber, amountDue (100 taka + last three digits), dueDate}`;
  `PayBill {billNumber, amount, reference}` -> `PayBillResponse {receipt: SB-n}` (one receipt per
  reference); `GetPayment {reference}` -> `GetPaymentResponse {status: PAID, receipt}`. Faults (HTTP 500,
  `soap:Client`, the code in `<detail><s:error><s:code>`): bill number ending `404` -> `3001`, ending `999`
  on a payment -> `3003`, unknown reference `3404`, unknown operation or a request with a DOCTYPE `1000`.
  Configurable fault: `POST /sim/soapbill/fault {faultCode: Client|Server, code, reason}` answers every call
  with it until `DELETE /sim/soapbill/fault`. Not a mock-control party (no scenario or journal).
- C2W bank wire format (`c2wbank`, ticket 23, `controller/BankC2wController`,
  `service/BankC2wService`, `BankDebitLedger`, `mapper/BankDebitMapper`): `POST /c2wbank/v1/debits
  {transactionId, accountNumber ([0-9]{6,20}, else 400), amount}` -> always 202 `{transactionId,
  status: PENDING, code: "100", message, debitRef: "C2W-n"}` (every repeat too, one debit per
  `transactionId`); the outcome goes by callback (mock control; once, duplicate, late, out of order) as `{transactionId, status SUCCESS|FAILED, code, message, debitRef}`: success
  `000`; account ending `999` -> FAILED `4002` debit refused; amount fractions `.13` timeout (debit
  applied, 202 held `sim.timeout-delay`, NO callback: only the status inquiry learns it), `.77`
  500 (applied, callback sent), `.99` FAILED `9876` (ambiguous), `.55` malformed 202 body
  (applied, callback sent). `GET /c2wbank/v1/debits/{transactionId}` the real outcome or 404
  NOT_FOUND. MAINTENANCE 503 (not applied). Callbacks signed with
  `sim.callback-secrets.c2wbank` (stack: `C2WBANK_HMAC_SECRET`, written by `deploy/provision/services.sh`); the
  e2e suite points `callbackUrl` at `http://apisix:9080/partner/c2wbank/callback/debits`; replay
  through `POST /mock-control/c2wbank/callbacks/{reference}/replay`.
- Partner reverse (ticket 97, FRS MCK-02; `controller/ReversalController`, `service/ReversalService`):
  `POST /ocs/v1/topup/{transactionId}/reverse`, `/billagg/v1/payments/{transactionId}/reverse`,
  `/c2wbank/v1/debits/{transactionId}/reverse`, optional body `{reversalId}` (the adapters send
  `reverse:<txnId>`): a transaction its ledger holds as `SUCCESS` is reversed once, 200 `{transactionId,
  status: REVERSED, code: "0000", message, reversalRef: REV-n, reversalId}`, and every later reverse of
  it answers that first result again; unknown 404 `NOT_FOUND` `4040`; not successful 422 `FAILED` `4091`;
  operation `reverse` of each party's mock control (MAINTENANCE 503, BUSINESS_ERROR 422 `FAILED` with its
  code, nothing applied). In memory; the party reset forgets its reversals.
- SMSC (ticket 24, `client/SmscServer` on jSMPP, `service/SmscService`,
  `controller/SmscController`): SMPP 3.4 server on `sim.smsc.port` (2775, internal network only;
  `sim.smsc.enabled`, off in tests except `SmscSimulatorIT`). Accepts any bind (no credential
  check); every `submit_sm` gets `SMSC-<start>-<n>` (start = the instance's start time, so a restart never repeats an id mw-notify already stored); GSM-7 (data_coding 0, unpacked) and UCS-2 (8)
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
  APISIX route `ussd-http-<mno>` -> mw-ussd; wire format `contracts/api/mw-ussd/ussd-http.v1.openapi.yaml`),
  each step with HTTP basic auth as `ussd-<mno>` and the password `sim.ussd-gateway.credentials.<mno>`
  (stack: the generated `USSD_<MNO>_GATEWAY_SECRET`, written to `secret/mw-simulators` by
  `deploy/provision/services.sh`; an MNO without one posts without credentials and APISIX answers 401)
  and stops when mw-ussd answers the end flag (`sim.ussd-gateway.end-flag`, END); answers
  `{sessionId, screens: [{status, message, flag}], ended}`. `resume: true` with a `sessionId` and
  inputs (HTTP mode only, ticket 39) continues a session an earlier call left open: no BEGIN, one
  CONTINUE per input (the e2e suite plays a session that is running when a menu version goes live). Not a mock-control party (no
  journal: inputs carry PINs).
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
- Merchant IPN receiver (ticket 32, `controller/MerchantIpnController`, `service/MerchantIpnService`):
  plays a checkout merchant's server. `POST /merchant/ipn/**` (stack: mw-checkout sessions' `ipnUrl`
  `http://mw-simulators:8080/merchant/ipn/...`, allowed by mw-checkout's dev-hosts) answers 200 and
  records `{seq, receivedAt, path, ipnId (X-Bits-Ipn-Id), headers (lower-case, no Authorization/
  Cookie), body, answeredStatus (-1 = no answer)}`; no secret here (the e2e suite verifies the
  signature). Mock party `merchant` (operation `ipn`, keys ipnId, path): HTTP_5XX / BUSINESS_ERROR answer
  their `httpStatus`, CONNECTION_RESET and DOWN close the connection. `GET
  /sim/merchant/ipn/received[?ipnId=]`; the old `POST /sim/merchant/ipn/scenario` and `DELETE
  /sim/merchant/ipn/received` are aliases (above).
- payShield 10K simulator (ticket 64, spec 18.8, D23; `client/PayShieldServer`, `service/PayShieldService`,
  `service/PayShieldKeys`, `util/PinBlocks`, `util/PayShieldCrypto`, `controller/PayShieldController`;
  `sim.payshield.*`): TCP on `sim.payshield.port` (1500, internal network only; off in host tests).
  Frame: 2-byte big-endian length, header (`header-length`, 4 chars, echoed: the host correlates by
  it), then command code and fields in ASCII; many commands per connection. Answer: response code
  (second letter + 1: `CA -> CB`), 2-digit error code, fields. Errors `00`, `01` PIN verification
  failure, `10` / `11` parity error of the first / second key field, `15` invalid input, `68` command
  disabled (also any unknown command), `69` PIN block format not permitted: `CA` / `CC` from ISO 0, 3
  or 4 into ISO 1 while the payShield security setting "allow translation to non-PAN formats" is off
  (`sim.payshield.allow-non-pan-translation`, default false; ISO 1 binds the PIN to no account). Fields (simulator layout; keys are 33-char blobs, see below;
  a PIN block is 16 hex under a `U` key, 32 under an `S` key; `acct` = 12 digits (rightmost without
  the check digit), or 2-digit length + whole PAN when a format 4 block is involved):
  - `NC` -> `ND00` + LMK check value (16H) + firmware `1500-0000`.
  - `A0` mode (`0`, or `1` = also under a ZMK) + key type (`000` ZMK, `001` ZPK, `002` TPK/PVK, `00A`
    ZEK) + scheme (`U` double length TDES, `S` AES-128) [+ ZMK blob + `U`] -> `A100` + blob [+ `U` + 32H
    under the ZMK] + KCV (6H).
  - `CA` TPK + ZPK, `CC` ZPK + ZPK: + max PIN length (2N) + block + source and destination format
    codes (`01` ISO 0, `05` ISO 1, `47` ISO 3, `48` ISO 4) + acct -> `CB00`/`CD00` + PIN length + block
    + destination format.
  - `JC` TPK / `JE` ZPK + block + format + acct -> `JD00`/`JF00` + the PIN under the LMK (32H).
  - `DG` PVK + PIN under the LMK + acct (12N) + PVKI (1N) -> `DH00` + PVV (4N, Visa).
  - `DC` TPK / `EC` ZPK + PVK + block + format + acct + PVKI + PVV -> `DD00`/`ED00`, or `01`.
  - `M0` / `M2` mode (`00` ECB, `01` CBC) + `1` + `1` (hex in and out) + `00A` + ZEK [+ IV] + length
    (4H, bytes) + data (hex) -> `M100`/`M300` + length + data.
  - Keys under the LMK (`service/PayShieldKeys`): scheme + 32H = the key xor a per-type variant
    (SHA-256 of a fixed label), encrypted under the LMK; a TDES blob of another type unwraps without odd
    parity (`10`/`11`). PINs under the LMK the same way. ISO 9564-1 formats 0, 1, 3 (TDES) and 4 (AES,
    `util/PinBlocks`), PINs of 4 to 12 digits.
  - LMK (`client/LmkStore`, `sim.payshield.lmk-store`): `pkcs11` (`client/Pkcs11LmkStore`, the stack)
    = an AES-256 token object `lmk` in the SoftHSM `paysim` token (softhsm:5658 through the image's
    p11-kit proxy, user PIN `sim.payshield.pkcs11.user-pin` from Vault `secret/mw-simulators`,
    `SOFTHSM_PAYSIM_USER_PIN` by `provision_mw_simulators`), generated on first use and kept across
    restarts. Over the p11-kit RPC the token only encrypts (an ECB decrypt fails with
    `CKR_FUNCTION_NOT_SUPPORTED`), so a block under the LMK is a 4-round Feistel network on the token's
    AES encryption. `memory` (`client/InMemoryLmkStore`): a fresh LMK per start, host tests only. The
    working keys are clear in the simulator's memory while it runs a command (a test-only simulator).
    An LMK failure closes the connection unanswered. Message content is never logged.
  - Control: `POST|GET|DELETE /sim/payshield/scenario {latencyMs, errorCode, outage, commands[]}`
    (`errorCode` for the listed commands, or all; `outage` closes every connection unanswered). Test
    helper `POST /sim/payshield/pinblock {keyType TPK|ZPK, key (blob), format ISO0|ISO1|ISO3|ISO4, pin,
    pan}` -> `{block}`: what the ATM or switch sharing the key sends. Not a mock-control party (no
    recording).
  - Image: Alpine JRE (as mw-crypto) with `p11-kit=0.26.2-r0` (must match softhsm's), `socat`, `bash`;
    the only p11-kit module is `remote: |socat - TCP:softhsm:5658,...`. Compose waits for a healthy
    `softhsm`. Tests: `util/PayShieldCryptoTest` (DES worked example, KCV, format 0 example, PVV
    decimalisation, round trips of every format), `service/PayShieldServiceTest` (every command,
    error codes, scenario), `client/PayShieldServerTest` (framing, header, latency, outage),
    `client/Pkcs11LmkStoreTest` (the Feistel construction, fail closed), `controller/PayShieldControllerTest`,
    `PayShieldSimulatorIT` (this image built from the Dockerfile and the boot jar, the `test` task
    depends on `bootJar`, against a SoftHSM built from `src/test/resources/softhsm/`, a checked copy of
    `deploy/softhsm` (`scripts/test/test_softhsm.sh`): commands with the LMK in the token, and a second
    simulator on the same token keeps the LMK).
- Generic programmable partner stub (ticket 83, `controller/PartnerStubController`,
  `service/PartnerStubStore`, `dto/PartnerStub`): for partner route dry-runs (the adapters'
  `mw.partners.dry-run.base-url` in dev, `http://mw-simulators:8080/sim/partners/{partnerId}`) and manual
  tests. Control: `POST /sim/stubs/{partnerId} {operation?, method, path ("*" = one segment), status,
  body, headers?, delayMs?}` (same method + path replaces), `GET` the stubs, `DELETE` stubs and requests,
  `GET /sim/stubs/{partnerId}/requests` (last 200; Authorization / Cookie dropped). Traffic: any method on
  `/sim/partners/{partnerId}/**` gets the matching stub's answer, else 404 `{error: NO_STUB, method, path}`.
  Any partner id works (not a mock-control party); in memory.
- Adding a simulator: add a `MockParty` to `service/mockcontrol/MockParties`, a controller under
  `/<party>/...`, call `MockControl.decide` per operation with its magic value (`MagicAmounts`), carry the
  `Decision` out with `MockCalls`, implement `PartyState` for what it keeps, reuse the callback service.
- Dev provisioning (`provision_mw_simulators` in `deploy/provision/services.sh`, run by the one-shot `provision` on every `docker compose up`, idempotent, dev only): role
  `simulators_rw` and schema `simulators` (the chassis still migrates one on start-up),
  `secret/mw-simulators` (DB password, never overwritten) and the AppRole `mw-simulators`
  (configtree volume at `/run/mw/approle`).

## Contracts owned

- `api/mw-simulators/mock-control.v1.openapi.yaml` (ticket 102, test-only): the mock-control API;
  `contracts/MockControlContractTest` validates the served bodies and the behaviour list against it. The
  partner wire formats stay simulator-owned and not in `contracts/`.

## Contracts used

- `api/mw-ussd/ussd-http.v1.openapi.yaml` (client, USSD gateway simulator; own types `dto/UssdScreen`).
- Otherwise none. (Later tickets add the core stub: `core.*.command` in, `core.*.event` out, spec 22.2.)
- `contracts.yml`: the machine-readable list (checked by `./gradlew :contractsLint`): no events produced
  or consumed (the Kafka chassis is unused), so there is no `ProducedContractsTest` /
  `ConsumedContractsTest`; `contracts/ApiContractsTest` checks the steps `client/UssdGatewayClient`
  sends against `UssdStep` and that it reads a `UssdScreen` (local stand-in server, no Docker). The
  merchant IPN receiver plays the merchant side of mw-checkout's `ipn` webhook; it records the body
  raw and parses nothing, so `checkout.v1` is not listed.

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
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field. Party ids are
  `PARTY_ID` (ticket 23): masked by the party's `type` (wallet/MSISDN as an MSISDN, a bank account or any
  other type last 4 only).
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
  Add context with `log.atInfo().addKeyValue("event", "...")` or the MDC.
- `config/MwServiceProperties`: config binding; secrets from Vault when `VAULT_ENABLED=true`.
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
- `application.yml`: Resilience4j defaults (19.2), own schema `simulators` migrated by Flyway on
  start from `db/migration` (16.4); rename the schema to the spec 16.2 name if it has one.

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-simulators:test` | every test in one JVM: unit, slice and contract tests (`contracts/`), the Testcontainers `*IT` classes and the `@Conformance` suite; one PostgreSQL and one Kafka per test JVM, a fresh database per Spring context; the `*IT` and conformance classes are skipped without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-simulators:build` | `test`, then the boot jar `build/libs/mw-simulators.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-simulators:bootJar && docker build -t mw-simulators:dev services/mw-simulators` | image, as spec 4.3 rule 1 |
| `./gradlew clean build` | the full gate: every module, plus the repo checks (`:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`); unchanged modules come from the build cache |
| `./gradlew :mw-simulators:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-simulators` | rebuild this service's image (`mfs-mw/mw-simulators:local`) and restart it with what it depends on (dev timers from `deploy/.env`) |
