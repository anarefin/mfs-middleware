# mw-simulators

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.simulators`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Partner simulators for tests only (spec 22.2, WP12), one generic contract per partner type.
  OAuth2 client credentials (ticket 100b): `client/PartnerTokenClient` signs a `private_key_jwt`
  assertion (no client secret) and `GET /sim/partner-token` returns the access token;
  `GET /sim/oauth2-cc/ping` is the 204 the edge route `test-oauth2-cc` proxies to after the bearer
  check. The key is Vault `sim.partner-sim.private-key` (`sim.partner-token.private-key`).
  Today: OCS (prepaid top-up), the utility bill aggregator `billagg` (ticket 21) and the C2W bank
  `c2wbank` (ticket 23). State is in
  memory (a restart resets it); never deployed to production projects. Outcomes are
  `dto/SimMode` (SUCCESS, BUSINESS_FAILURE, TIMEOUT, SERVER_ERROR_AFTER_SEND, UNKNOWN_CODE,
  MALFORMED, CONNECTION_RESET, PARTIAL_SUCCESS), decided by mock control (below) with the amount magic
  values of `service/MagicAmounts` as the default.
- Mock control (tickets 102a/b, ADR D33, FRS MCK-03..07, MCT-01..04, MCT-06; `service/mockcontrol/`,
  `controller/MockControlController`, contract `api/mw-simulators/mock-control.v1.openapi.yaml`): one engine
  for every mock party, only in the Spring profile `mock-control` (`MockControl.PROFILE`; the compose block
  sets `SPRING_PROFILES_ACTIVE: dev,mock-control` so springdoc is on, the test config `mock-control` too). Without the profile `MagicValuesOnly`
  answers every call by its magic value and no control path, journal or alias exists. MCT-08 (ticket
  103): `config/MockControlProdGuard` refuses to start with the profile and `mw.service.env` (`MW_ENV`)
  `prod`; `scripts/check-mock-control-prod.sh` (`./gradlew :mockControlProdCheck`) fails when anything but
  the test doubles (this service, mw-core-mock) enables it.
  - Global reset (ticket 103, FRS MCT-07; `controller/MockResetController`, `service/mockcontrol/GlobalReset`,
    `client/CoreMockControlClient`, `config/CoreMockControlProperties` `sim.core-mock.{base-url
    http://mw-core-mock:8080, timeout 30s}`): `POST /mock-control/reset {seedVersion?}` resets every party
    of `MockParties.ALL` as the party reset does (`PartyReset`), then mw-core-mock's `POST
    /mock-control/reset` (contract `api/mw-core-mock/core-mock-control.v1`: its ledger back to the seed of
    `seedVersion`, its scenarios and journal cleared); answers `{parties:[{party, status RESET|FAILED,
    seedVersion?, detail?}]}`, the local parties first; a party that fails (the core unreachable or not
    200) is FAILED with the reason and the others still go. Test-only APISIX route `/test/mock-control/*`
    (`MW_E2E_ROUTES=1`). Test: `GlobalResetIT` (a stub core).
  - Parties (`MockParties`, `GET /mock-control/parties`): `ocs` (legacy single profile; topup, status,
    reverse; keys msisdn, txnRef), the operator profiles `ocs-gp`, `ocs-robi`, `ocs-teletalk`, `ocs-bl`
    (ticket 112a; topup, inquiry, postpaid, status, reverse; keys msisdn, txnRef), `billagg` (billers,
    fetch, pay, status, reverse; biller, billNumber, txnRef; REST and SOAP
    front ends), `c2wbank` (debit, status, reverse; account, txnRef), `merchant` (ipn; ipnId, path), `merchant-soap`
    (validate, notify, query, refund; merchantId, txnRef; ticket 115b), and the
    SMPP / TCP parties of ticket 102b (FRS MCT-04): `smsc` (bind, submit; systemId, msisdn), `ussdgw` (bind,
    submit, enquire_link, dial; systemId, msisdn, mno), the per-MNO USSD ports `ussd-gp`, `ussd-robi`,
    `ussd-bl`, `ussd-teletalk` (bind, submit, enquire_link; systemId, msisdn), `payshield` (command; command), and `push` (send; token,
    topic; ticket 110). Each party lists the
    behaviours it carries out (`MockParty.behaviours`; the SMPP / TCP ones a subset) and `MockParties.check`
    refuses a scenario with another behaviour, an operation it lacks or a `code` not in its form (SMPP
    command_status `0x58` / `88` for smsc, ussdgw and the per-MNO USSD parties, two characters for payshield): 400 MW-VAL-001. The
    file mocks `aml`, `trustbank`, `dw` of ticket 123 are parties too (below). An HTTP
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
    ipnId?}` (a next-N scenario on `merchant`/ipn) and `DELETE /sim/merchant/ipn/received`; ticket 102b:
    `POST|GET|DELETE /sim/payshield/scenario {latencyMs, errorCode, outage, commands[]}` (`errorCode` =
    BUSINESS_ERROR on `command` keyed per listed command or for all; `outage` = DOWN; `latencyMs` = SLOW fixed)
    and `POST /sim/ussdgw/smpp/scenario {throttleNext, queueFullNext, genericNackNext, silentEnquireLink,
    screenTimeoutMs}` (next-N scenarios on `submit`: MALFORMED_RESPONSE, BUSINESS_ERROR `0x58`, `0x14`,
    refused in that order; TIMEOUT on `enquire_link`; setting `timeoutDelayMs`; `{}` resets).
  - Magic values (OCS): MSISDN ending `999` -> business failure; amount fraction `.13` -> timeout (top-up
    applied, reply held `sim.timeout-delay`, default 30 s), `.77` -> 500 after send (applied), `.99` ->
    unknown code `9876`, `.55` -> malformed body (applied). `dto/SimMode` is the resolved outcome.
  - The SMPP / TCP parties (ticket 102b): their own wire carries a `Decision` out (below, per simulator);
    their journal entries (`method` `SMPP` / `TCP`, `path` = the PDU or command: `bind`, `submit_sm`, the
    payShield command code) carry addresses only, never SMS text, USSD input or payShield fields. The
    SMSC's DLR is an in-band callback (`MockParties.callsBackInBand`): always on without a `callbackUrl`,
    also without the profile (`MagicValuesOnly` answers ONCE). Not on the engine: the SOAP stub biller and
    the partner stubs.
- OCS wire format: `POST /ocs/v1/topup {transactionId, msisdn, amount:"100.00"}`; `GET
  /ocs/v1/topup/{transactionId}` returns the real outcome (200, or 404 NOT_FOUND). Replies:
  `{transactionId, status SUCCESS|FAILED|NOT_FOUND|UNAVAILABLE|ERROR, code, message, ocsRef}`;
  success 200 code `0000`, failure 422 (`2001` business, `9876` unknown), MAINTENANCE 503
  (not applied). A repeated `transactionId` returns the original outcome. Operator profiles (ticket
  112a, `service/OperatorOcsService`, `OperatorOcsProfile`, `controller/OperatorOcsController`):
  `/ocs-gp/v1`, `/ocs-robi/v1`, `/ocs-teletalk/v1`, `/ocs-bl/v1` (recharge or the operator's name for
  it, bill fetch, postpaid pay, status, reverse) plus `POST /ocs-teletalk/oauth/token`. An MSISDN
  ending `999` is that operator's barred code. Seeded postpaid accounts: GP `01711000021` outstanding
  `450.00` minimum `50.00` bill `GPB-01711000021`; Robi `01811000021` `320.50` / `20.00` /
  `RBB-01811000021`; Teletalk `01511000021` `180.00` / `10.00` / `TTB-01511000021`; Banglalink
  `01911000021` `275.75` / `25.00` / `BLB-01911000021`. Any other postpaid number is the unknown-bill
  code. A second reverse returns the first operator reference. `service/OperatorOcsServiceTest`.
- Bill aggregator wire format (`billagg`, `controller/BillAggregatorController`,
  `service/BillAggregatorService`, `BillPaymentLedger`): `GET /billagg/v1/billers` -> `[{code,
  name, category, protocol: REST|SOAP}]`, 10 billers (`BillAggregatorService.BILLERS`, read from the seed file
  `src/main/resources/seed/v1/billers.csv` by `service/SeedFiles`, ticket 103, FRS MCK-08; test
  `service/SeedFilesTest`; bills have no seed: magic values; the bank gateway's banks and accounts below): REST BREB, CWASA,
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
- Card acquirer `cybersource` (ticket 118, FRS UC-13, EXT-14, FL-07, MCK-06; `controller/CyberSourceController`,
  `service/CyberSourceService`, `dto/CyberSourceRequests`), the CyberSource-style wire mw-adapter-card's seed
  calls: `POST /cybersource/v1/sessions {clientReference, amount, currency}` -> 201 `{status CREATED, sessionId,
  hostedPageUrl}` (`sim.cybersource.hosted-page-base` + `/<sessionId>`, one session per reference); the hosted
  page `GET /cybersource/hp/{sessionId}` (an HTML card form, the only place a card number is typed) and its
  `POST` (form field `cardNumber`) -> `{clientReference, sessionId, transientToken, status AUTHENTICATED|FAILED,
  code}`: the number is dropped, only its test-card outcome is kept behind the token, and the same body goes as
  the 3-D Secure webhook to the party's `callbackUrl` (signed with `sim.callback-secrets.cybersource`, stack
  `CYBERSOURCE_HMAC_SECRET`; once, duplicate, late or none by mock control); `POST /cybersource/v1/payments
  {clientReference, sessionId, transientToken, amount, currency}` -> 201 `{status CAPTURED, paymentId, code
  100}` or 422 `{status DECLINED, code}` (once per reference, a repeat answers the first); `GET
  /cybersource/v1/payments/{clientReference}` the true outcome or 404 `NOT_FOUND` `4040`; `POST
  .../{clientReference}/refunds {refundId, amount}` -> 201 `{status REFUNDED, refundId}` once (not captured 422
  `4004`, unknown 404). Test cards (magic values): `4111111111111111` success, `4000000000000002` declined
  `4001`, `4000000000003063` 3-D Secure failed `4002` (webhook FAILED, capture refused), `4000000000000119`
  capture timeout (captured, answer held `sim.timeout-delay`); any other number declined. Mock party
  `cybersource` (operations session, authenticate, capture, status, refund; keys txnRef, session), the party
  reset forgets sessions, tokens and payments. Test: `CyberSourceSimulatorIT`.
- UCB IMT (`ucb-imt`, ticket 119, FRS UC-16, MCK-06; `controller/UcbImtController`, `service/UcbImtService`):
  `POST /mock-control/ucb-imt/trigger {amount, msisdn, sender, country}` posts one signed credit
  (`X-Partner-Id: ucb-imt`, hmac-sha256 with `sim.callback-secrets.ucb-imt`, stack `UCB_IMT_HMAC_SECRET`) to
  `sim.ucb-imt.credit-url` (default `http://mw-adapter-remit:8080/remit/v1/credits`). A DUPLICATE_CALLBACK
  scenario sends that body twice (a fresh nonce each time), LATE_CALLBACK waits `delayMs`, NO_CALLBACK
  sends nothing. The adapter's confirmation is `POST /ucb-imt/v1/remittances/{reference}/confirmation
  {partnerRef, upayRef, outcome}` (signed headers required, a repeat keeps the first outcome) and
  `GET /ucb-imt/v1/remittances/{reference}` is `{confirmed: true}` or 404 `4001`. The MSISDN and the sender
  name are the credit body only, never logged. The party reset forgets confirmations. Test:
  `service/UcbImtServiceTest`.
- NPSB switch (`npsb`, ticket 121a, FRS EXT-13, MCK-01; `controller/NpsbController`, `service/NpsbService`,
  `dto/NpsbParticipant`): `GET /npsb/v1/participants` -> 200 `{participants: [{id, name, kind BANK|MFS,
  active}]}` from the versioned seed `src/main/resources/seed/v1/npsb/participants.json` (12 fake participants,
  8-character BIC-shaped ids `FAKE...`; `FAKEBDX9` is suspended, `active` false). Mock party `npsb` (operations
  `participants`, `transfer`, `status`, `return`, `inbound`; keys participant, txnRef = EndToEndId):
  participants BUSINESS_ERROR 422 `{status FAILED, code}` (default `NARR`), MAINTENANCE 503 everywhere.
  ISO 20022 (ticket 121b part 2; `service/NpsbIsoXml`, JDK DOM in the XSD element order, safe parse; the wire of
  mw-adapter-interop's `npsb` seed): `POST /npsb/v1/transfers | status | returns {messageType, document}` (pacs.008 /
  pacs.028 / camt.056) -> 200 `{msgId, document}` (pacs.002.001.10 / camt.029.001.09), 400 `{code FF01}` for a
  document that is not the message. Stateful, in memory, one transfer per EndToEndId: a repeat answers the
  stored state (nothing applied twice); pacs.028 answers the stored state, unknown 404 `{code NOT_FOUND}`; a PDNG
  transfer settles `sim.npsb.settle-delay` (5 s) after it was booked; camt.056 answers CNCL once for an ACSC
  transfer, then RJCR (also unknown or not settled). A BUSINESS_ERROR scenario on `transfer` books it RJCT with
  its `code` (default `NARR`); the other behaviours as `MockCalls.answer`. Data triggers (creditor account's last
  three digits unless said; MCK-03):

  | Trigger | pacs.002 |
  | --- | --- |
  | creditor agent not in the participant seed, or suspended (`FAKEBDX9`) | RJCT `RC01` |
  | `991` | RJCT `AC01` |
  | `992` | RJCT `AM04` |
  | `993` | PDNG, then ACSC after the settle delay (async pacs.002 by inquiry) |
  | `995` | PDNG, then RJCT `AC01` after the settle delay |
  | `994` | ACSC booked, first answer held `sim.timeout-delay` (adapter UNKNOWN, the pacs.028 finds ACSC) |
  | anything else | ACSC, `ClrSysRef` `NPSB-CLR-n` |

  Inbound push `POST /mock-control/npsb/inbound {creditorAccount, amountMinor?, endToEndId?, creditorAgent?
  (BITSBDDH), debtorAgent? (FAKEBDD1), ageMinutes?}` (client `client/NpsbInboundClient`): a pacs.008.001.08 posted
  as application/xml to `sim.npsb.inbound-url` (default `http://mw-adapter-interop:8080/partner/v1/npsb/inbound`);
  operation `inbound` callback dimension: DUPLICATE_CALLBACK sends it twice (the adapter credits once),
  LATE_CALLBACK waits `delayMs`, NO_CALLBACK sends nothing; `ageMinutes` back-dates CreDtTm (a stale message is
  refused RJCT `DT01`). Answers `{endToEndId, answers:[{status, body}]}`. Every document the mock sends (pacs.002,
  camt.029, the pushed pacs.008) is signed by `service/NpsbSigner` in mw-crypto's one XML-DSig profile with the
  dev switch key `sim.npsb.signing-key` (Vault secret/mw-simulators, PKCS#8, created once by provision
  `npsb_switch_key`, which copies the certificate to mw-adapter-interop as `npsb-certificate-pem`); unset, unsigned. Out-of-order callbacks have no counterpart on this synchronous wire. The party reset forgets the
  transfers. Tests: `NpsbSimulatorIT`, `service/NpsbServiceTest` (XSD-valid output, triggers, settle, return
  once, scenario code, push, signed with the switch key), `service/NpsbSignerTest`, `service/SeedFilesTest`.
- Bank gateway (ticket 117d, FRS EXT-12, MCK-02/03/08; `controller/BankGatewayController`,
  `service/BankGatewayService`, `BankGatewaySoapService`, `BankGatewayLedger`, `BankTransfer`): the 20 banks of
  `src/main/resources/seed/v1/bank-profiles.csv`, a copy GENERATED by `node deploy/partner-routes/bank/generate.mjs`
  from `deploy/partner-routes/bank/bank-profiles.csv` (never edit it; the same CSV makes mw-adapter-bank's and
  mw-admin-api's `bank-<code>` profiles, which point at `http://mw-simulators:8080`). Wire format = the
  generator's header. REST banks: `POST /bank/<code>/v1/accounts/verify {accountNumber}` -> 200 `{status: ACTIVE,
  accountName, verifyRef}`; `POST .../transfers {transactionId, accountNumber, amount, direction DEBIT|CREDIT}`
  -> CREDIT (W2B) 200 `{transactionId, status: SUCCESS, code: "000", message, bankRef}`, DEBIT (C2W) always 202
  `{status: PENDING, code: "100", bankRef}` with the outcome by callback; `GET .../transfers/{id}` the true
  outcome or 404 `NOT_FOUND` `4040`; `POST .../transfers/{id}/reverse {reversalId?, amount?}` 200 `{status:
  REVERSED, reversalRef}` once (later reverses answer the first), unknown 404 `4040`, not successful 422 `4091`;
  refusals 422 `{transactionId, status: FAILED, code, message}`; invalid field 400 `1000`. SOAP banks: `POST
  /bank/<code>/v1/soap` (namespace `urn:bank:<code>:v1`) `ValidateAccount`, `Transfer`, `TransferStatus`,
  `ReverseTransfer` -> `<Op>Response` with the REST fields as elements (unknown transfer: `status NOT_FOUND`);
  refusals `soap:Client` faults `<detail><b:code/><b:message/></detail>`, MAINTENANCE / 500 mode `soap:Server`
  5030 / 5000, `1000` unreadable or unknown operation. A SOAP bank has no REST endpoints and the reverse (404:
  the protocol guard). Auth: the request must carry the profile's scheme (hmac-sha256 `X-Signature` +
  `X-Timestamp`, api-key `X-Api-Key`, basic `Authorization: Basic`, ws-security a UsernameToken with Username and
  Password), else 401 / fault `1002`; the values are not checked (the adapter's generated secrets live only in
  `secret/mw-adapter-bank/bank-<code>`). `GET /bank/<code>/v1/health` -> 200 `{status: UP}` (operation `health`;
  the adapter profiles probe it, `healthCheck: true`, with the profile's HTTP auth, none for ws-security).
  Accounts: `seed/v1/bank-accounts.csv` (bank or `*` = every bank; holder name, ACTIVE|DORMANT|CLOSED,
  balance), any other number opened on first use (ACTIVE, "Test Customer", 1,000,000.00); a CREDIT adds to the
  balance, a DEBIT takes from it (short -> 4003), a reversal undoes it. Test helper `GET
  /sim/bank/<code>/accounts/<account>` -> `{bank, accountNumber, holderName, status, balance}`. Trigger table
  (MCK-03; the defaults mock control overrides):

  | Trigger | AVL | Transfer |
  | --- | --- | --- |
  | account ending `00` (not seeded) | 422 `4001` ACCOUNT_NOT_FOUND | 422 `4001` |
  | account ending `98`, seeded DORMANT / CLOSED | 422 `4002` ACCOUNT_INACTIVE | 422 `4002` |
  | account ending `99` | ACTIVE | DEBIT 422 `4003` INSUFFICIENT_FUNDS (also a balance short of the amount) |
  | account ending `97` | ACTIVE | 422 `4004` LIMIT_EXCEEDED |
  | account ending `96` | ACTIVE | 422 `4005` TRANSFER_REFUSED |
  | account ending `95` | ACTIVE | CREDIT answered 202 PENDING, SUCCESS by callback and status |
  | amount `.13` / `.77` / `.99` / `.55` | | timeout (applied, reply held, no callback) / 500 applied / 422 `9876` (ambiguous) / cut-short body applied |

  Mock party `bank-<code>` per bank (`MockParties`, operations verify, transfer, status, reverse, health; keys
  account, txnRef; `RecordingFilter` journals `/bank/<code>/...` under it): scenarios (BUSINESS_ERROR `code`
  default the trigger's, else 4005), switch, settings (`callbackOnly` answers a CREDIT 202 PENDING too) and the
  party reset (that bank back to its seed). Callbacks after every applied transfer (not TIMEOUT) to the party's
  `callbackUrl`, body `{transactionId, status, code, message, bankRef}`, signed with `sim.callback-secrets.bank-<code>`
  when set; replay `POST /mock-control/bank-<code>/callbacks/{transactionId}/replay`. Test: `BankGatewaySimulatorIT`.
- Bill aggregator mocks (ticket 114, FRS UC-10, EXT-10, MCK-02/06/08; `controller/BillAggregatorMockController`,
  `service/BillAggregatorMockService`, `AggregatorBillLedger`, `dto/AggregatorProfile`): four profiles, each a mock
  party named after it (operations fetch, pay, status, reverse; keys biller, billNumber, txnRef), in the wire
  dialect of `deploy/partner-routes/aggregators/generate.mjs` (keep `AggregatorProfile` in step): REST
  `POST /<id>/v1/bills/inquiry`, `POST /<id>/v1/payments`, `GET /<id>/v1/payments/{reference}`, `POST
  /<id>/v1/payments/{reference}/reverse` for `sslcommerz` (snake_case), `ekpay` (camelCase, Bearer token from
  `POST /ekpay/v1/oauth/token` with any Basic client) and `agg4` (`{"req"}` / `{"res"}` envelope), SOAP 1.1
  `POST /agg3/v1/soap` (`urn:agg3:v1`, GetBill / PayBill / PaymentStatus / ReversePayment, `soap:Client` faults
  with `<detail><b:code/>`). The path is `/<id>/v1`, not the ticket's `/sim/agg/<id>`: the mock party is the
  first path segment (journal, ADR D33). Auth presence is checked (sslcommerz `X-Key-Id` + `X-Signature` +
  `X-Timestamp`, ekpay an issued token, agg3 a UsernameToken, agg4 Basic), else 401 / fault `1002`. Bills:
  `seed/v1/bills/<id>.csv` (MCK-08; billerCode, billNumber, amount, dueDate, billPeriod, UNPAID|PAID), stateful
  UNPAID -> PAID -> REVERSED (payable again), back to the seed only by the party reset (`DELETE
  /mock-control/<id>/journal`, `POST /mock-control/reset`), not by a scenario. Test helper `GET
  /sim/agg/<id>/bills/<biller>/<billNumber>`. Trigger table (codes per profile: sslcommerz `E10n`, ekpay `40n`,
  agg3 `A3-0n`, agg4 `X0n`):

  | Trigger | Fetch | Pay |
  | --- | --- | --- |
  | bill not seeded (biller known) | 422 n=1 NOT_FOUND | 422 n=1 |
  | biller not in the profile's seed | 422 n=4 UNKNOWN_BILLER | 422 n=4 |
  | bill PAID (seeded or paid earlier) | 200, status the profile's paid word | 422 n=2 BILL_ALREADY_PAID |
  | amount not the bill's | | 422 n=6 AMOUNT_MISMATCH |
  | bill number ending `999` | | 422 n=3 REFUSED |
  | reverse of a payment not paid | | 422 n=5 NOT_PAID (reverse) |
  | amount `.13` / `.77` / `.99` / `.55` | | timeout applied / 500 applied / 422 `9876` / cut-short body applied |

  A repeated reference answers the stored payment; a second reverse the first reversal. Setting `callbackOnly`
  answers a payment 202 (pending word) with the outcome by callback; callbacks (ONCE, DUPLICATE, LATE,
  OUT_OF_ORDER by scenario, MCK-06) carry the REST status body, signed with `sim.callback-secrets.<id>` (stack:
  `BILLAGG_HMAC_SECRET`). billagg also has seeded bills (`seed/v1/bills/billagg.csv`, bill numbers `1149...`):
  a seeded bill answers its own amount, a paid one is refused `3002` on fetch and pay, a reverse makes it
  payable again; any other bill number keeps the computed amount and magic endings. Test: `BillAggregatorMockIT`.
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
  refused 0x58 (throttled) then accepted, `996` refused 0x0B, `995` no DLR; else DELIVRD. Mock party
  `smsc` (ticket 102b; magic values the default): `bind` (key systemId) BUSINESS_ERROR rejects the bind
  (`code`, default 0x0D ESME_RBINDFAIL; MAINTENANCE 0x0D), DOWN / CONNECTION_RESET close the connection
  unanswered; `submit` (key msisdn = destination) BUSINESS_ERROR refuses (`code`, else the magic value's,
  else 0x58 ESME_RTHROTTLED), MAINTENANCE 0x08, DOWN closes the session (nothing stored in these);
  otherwise stored, then TIMEOUT holds the submit_sm_resp, HTTP_5XX answers 0x08 after storing,
  CONNECTION_RESET closes after storing; the DLR (callback dimension): NO_CALLBACK none, LATE_CALLBACK after
  `delayMs` more, DUPLICATE_CALLBACK twice, OUT_OF_ORDER_CALLBACK the final one then a stale ENROUTE
  (`dlrStatus` keeps the final state). The party reset clears the messages.
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
  CONTINUE per input (the e2e suite plays a session that is running when a menu version goes live). Each
  play (HTTP or SMPP) is operation `dial` of the mock party `ussdgw` (keys msisdn, mno): DELAY waits before
  the first step, CONNECTION_RESET hangs up after the first screen, DOWN sends nothing (one screen status
  -1), MAINTENANCE neither (status 503). No journal of plays (inputs carry PINs).
- USSD gateway simulator, SMPP mode (ticket 27, 109a, `client/UssdSmppGateway`): one SMPP 3.4
  server per MNO. Grameenphone listens on `sim.ussd-gateway.smpp.port` (2776, party `ussd-gp`,
  packing `EOS_BIT0`). When `sim.ussd-gateway.smpp.peers-enabled` (on in dev, off in tests) robi
  listens on 2777 (`ussd-robi`, `EOS_BIT7`, transmitter+receiver), bl on 2778 (`ussd-bl`,
  `EOS_BIT0`, transceiver) and teletalk on 2779 (`ussd-teletalk`, `EOS_BIT7`, transmitter+receiver).
  A `deliver_sm` goes out on a receiver or transceiver, never a transmitter. MNOs in
  `sim.ussd-gateway.smpp.mnos` ([gp]) are played over SMPP by the same `POST
  /sim/ussdgw/sessions`: `deliver_sm` with `ussd_service_op` 1 (dial, short message = service
  code) then 18 per input, `its_session_info` session number 1-128 packed for that MNO (sequence
  in bits 7..1 and the end bit in bit 0, or the end bit in bit 7); each `submit_sm` of that session
  is a screen `{status: 200, message, flag}` (END when op 17 or the packing's end bit, else CON);
  no screen within `screen-timeout` (15 s) or no bind -> `{status: -1}` and the play stops; the
  wait is that port's party setting `timeoutDelayMs`, and for `ussd-gp` the legacy `ussdgw`
  setting when its own is unset, else `screen-timeout`. Faults are the port's party (`bind`,
  `submit`, `enquire_link`); Grameenphone also honours a scenario on `ussdgw` when `ussd-gp` has
  none, so the old `/sim/ussdgw/smpp/scenario` body still drives it. `bind` (key systemId)
  BUSINESS_ERROR rejects (default 0x0D), DOWN / CONNECTION_RESET close; `submit` (key msisdn = the
  subscriber) BUSINESS_ERROR refuses the screen (default 0x58 ESME_RTHROTTLED), MALFORMED_RESPONSE
  answers `generic_nack`, MAINTENANCE 0x08, DOWN closes the bind (all counted in `refused`),
  TIMEOUT takes the screen and holds the submit_sm_resp, CONNECTION_RESET takes it and closes the
  bind; `enquire_link`: anything but SUCCESS leaves it unanswered (dropped enquire_link). Dial
  stays operation `dial` of `ussdgw`. `POST /sim/ussdgw/smpp/drop` closes every port's binds,
  `GET /sim/ussdgw/smpp/binds` reports the Grameenphone port `{bound, binds, enquireLinks, refused}`.
- Merchant IPN receiver (ticket 32, `controller/MerchantIpnController`, `service/MerchantIpnService`):
  plays a checkout merchant's server. `POST /merchant/ipn/**` (stack: mw-checkout sessions' `ipnUrl`
  `http://mw-simulators:8080/merchant/ipn/...`, allowed by mw-checkout's dev-hosts) answers 200 and
  records `{seq, receivedAt, path, ipnId (X-Bits-Ipn-Id), headers (lower-case, no Authorization/
  Cookie), body, answeredStatus (-1 = no answer)}`; no secret here (the e2e suite verifies the
  signature). Mock party `merchant` (operation `ipn`, keys ipnId, path): HTTP_5XX / BUSINESS_ERROR answer
  their `httpStatus`, CONNECTION_RESET and DOWN close the connection. `GET
  /sim/merchant/ipn/received[?ipnId=][&type=PAYMENT|REFUND]` (ticket 116: a refund IPN, body `type` REFUND, is recorded with type REFUND); the old `POST /sim/merchant/ipn/scenario` and `DELETE
  /sim/merchant/ipn/received` are aliases (above).
- Merchant system SOAP mock (ticket 115b, FRS UC-11, EXT-11, MCK-01; `controller/MerchantSoapController`,
  `service/MerchantSoapMock`; a separate class from the IPN receiver above): `POST /merchant-soap/v1/soap` (SOAP
  1.1, namespace `urn:merchant:v1`), the other side of mw-adapter-merchant's `merchant-soap` partner.
  `ValidateMerchant {merchantId, tillId}` -> `{status ACTIVE, merchantRef MREF-<id>}`; seeded merchants M-0001
  (active, TILL-01/02), M-0002 (inactive), M-0003 (active, no till); unknown fault `5001`, inactive `5002`, a till it
  lacks `5003`. `NotifyPayment {merchantId, tillId, amount, reference}` -> `{status RECEIVED, notifyRef NTF-n, code
  0000}` (once per reference). `QueryNotify {merchantId, reference}` -> `{status RECEIVED|NOT_FOUND, notifyRef}`.
  `Refund {merchantId, amount, reference, refundId}` -> `{status REFUNDED, refundRef RF-n, code 0000}`, stateful:
  once per refundId, never above the notified amount (else `5004`). WS-Security UsernameToken user `bits`, password
  `sim.merchant-soap.ws-password` (stack `MERCHANT_SOAP_WS_PASSWORD` in Vault `secret/mw-simulators`; unset = every
  call refused `1002`). Mock party `merchant-soap`: BUSINESS_ERROR a `soap:Client` fault with the code, HTTP_5XX a
  `soap:Server` fault `5000` after the effect (the adapter reads it UNKNOWN), TIMEOUT holds, MALFORMED_RESPONSE cuts
  short, MAINTENANCE `soap:Server` `5030`; the party reset forgets notifies and refunds. Test: `MerchantSoapMockIT`.
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
  - Mock party `payshield` (ticket 102b), operation `command` (key `command`, the code): DELAY and the SLOW
    range hold the answer, BUSINESS_ERROR answers its `code` (default `15`) without running the command,
    TIMEOUT runs it and holds the answer, MALFORMED_RESPONSE answers the response code alone,
    CONNECTION_RESET runs it and closes the connection unanswered; DOWN closes it unanswered, MAINTENANCE
    answers `68`. The journal notes the command code only. The old `/sim/payshield/scenario` is an alias
    (above). Test helper `POST /sim/payshield/pinblock {keyType TPK|ZPK, key (blob), format
    ISO0|ISO1|ISO3|ISO4, pin, pan}` -> `{block}`: what the ATM or switch sharing the key sends (never
    recorded).
  - Image: Alpine JRE (as mw-crypto) with `p11-kit=0.26.2-r0` (must match softhsm's), `socat`, `bash`;
    the only p11-kit module is `remote: |socat - TCP:softhsm:5658,...`. Compose waits for a healthy
    `softhsm`. Tests: `util/PayShieldCryptoTest` (DES worked example, KCV, format 0 example, PVV
    decimalisation, round trips of every format), `service/PayShieldServiceTest` (every command,
    error codes), `client/PayShieldServerTest` (framing, header, the mock party: latency, error code per
    command, cut short, reset, DOWN, MAINTENANCE),
    `client/Pkcs11LmkStoreTest` (the Feistel construction, fail closed), `controller/PayShieldControllerTest`,
    `PayShieldSimulatorIT` (this image built from the Dockerfile and the boot jar, the `test` task
    depends on `bootJar`, against a SoftHSM built from `src/test/resources/softhsm/`, a checked copy of
    `deploy/softhsm` (`scripts/test/test_softhsm.sh`): commands with the LMK in the token, and a second
    simulator on the same token keeps the LMK).
- Push server mock (ticket 110, FRS EXT-04; `controller/PushController`, `service/PushService`,
  `config/PushProperties` `sim.push.api-key`, stack: `PUSH_API_KEY` in Vault `secret/mw-simulators`, no value in
  the main config; unset = every push refused): the app-server push API mw-notify calls, FCM / APNs style.
  `POST /push/v1/messages {messageId?, token | topic, platform?, notification {title, body}, data?}` with
  `Authorization: key=<api-key>` (else 401 `UNAUTHENTICATED`; no target or notification 400
  `INVALID_ARGUMENT`) -> 200 `{messageId: push-<n>, status: DELIVERED}`, the push stored. `GET
  /push/v1/messages[?token=&topic=]` (and `GET /sim/push/messages`, the same list on the control path the
  test-only route `/test/sim/*` reaches) -> `[{seq, messageId, senderMessageId, token, topic, platform, title,
  body, data, status, receivedAt}]`. Mock party `push`, operation `send` (keys token, topic), behaviours
  SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, PARTIAL_SUCCESS:
  BUSINESS_ERROR (magic value: a token containing `invalid`) 404 `{error: <code, default UNREGISTERED>}`, not
  stored; HTTP_5XX its `httpStatus` (default 500), NOT stored (unlike the partners: a failed push server
  delivered nothing, so a retried push never shows twice); TIMEOUT stores then holds the answer;
  MALFORMED_RESPONSE stores and cuts the body short; CONNECTION_RESET stores and closes the connection;
  PARTIAL_SUCCESS stores with status `ACCEPTED` (mw-notify: SENT, not DELIVERED); DOWN closes, MAINTENANCE 503,
  nothing stored. The journal records each call (Authorization dropped); the party reset forgets the pushes.
  Test: `PushSimulatorIT`.
- File mocks (ticket 123, FRS EXT-05, EXT-06, EXT-17, MCT-05, MCK-01/03/07; `service/files/`,
  `controller/FileMockController`, `client/SftpClient` (sshj, implements `client/RemoteFiles`),
  `client/CoreLedgerClient`, `config/FileMockProperties` `sim.files.*`; mock-control profile only). On the
  partner SFTP host (stack `partner-sftp`, profile tester, login bits, password `sim.files.sftp.password` =
  `SFTP_DEV_PASSWORD` from Vault; host key checked only when `sim.files.sftp.host-key` is set). Folders
  `aml/in`, `aml/out`, `trustbank/out`, `dw/in` (created by partner-sftp's command). Parties of the engine
  (`MockParties`): `aml` (respond; keys file, date), `trustbank` (mt940, mt942; keys date, account), `dw`
  (validate; keys file, date). Jobs (ShedLock, only with `sim.files.enabled`, the stack sets
  `SIM_FILES_ENABLED` = `MW_SIM_FILES_ENABLED`, default true; a host that is down gives one warning per
  outage): `scheduler/AmlResponderJob` (`sim.files.aml.poll` 15 s), `TrustStatementJob` (the MT940 of
  yesterday at `cutoff-cron` 00:05 Asia/Dhaka, an MT942 of today every `intraday` 15 min),
  `DwDropValidatorJob` (`sim.files.dw.poll` 30 s). `POST /mock-control/{party}/run {operation?, date?,
  at?}` runs one now and answers `{party, operation, entries}` (the journal entries of the run); `POST
  /mock-control/{party}/files {name, content}` (aml, dw) drops a file into the inbox as the middleware
  would (test helper, 201 `{path, sha256, bytes}`). Not reachable: 503 `MW-SYS-FILE_MOCK_UNAVAILABLE`.
  - AML (`AmlMockService`, `AmlResponder`, draft format `contracts/files/aml/README.md`, which ticket 125
    finalises): each new file of `aml/in` (once per name and sha256; the party reset forgets) is READ and
    answered in `aml/out` as `AML_RESP_<x>` (header, `ACTION|txnId|walletId|rule|reference` lines, trailer
    `T|lines|alerts|holds|blocks`; an unreadable file gets `E|<code>|<detail>`). Triggers (seed
    `seed/v1/aml/rules.json`, MCK-03): amount above 5,000,000 poisha -> ALERT `AMOUNT_THRESHOLD` (payer);
    a WALLET / MSISDN party on the watch-list (01711000777, 01811000777, 01911000777) -> HOLD `WATCH_LIST`;
    one ending `6666` -> BLOCK `BLOCK_SUFFIX`; strongest wins. INJECTED_MISMATCH kind `UNKNOWN_TXN`.
  - Trust bank (`TrustBankService`, `TrustStatementGenerator`, Prowide Core `MT940` / `MT942` and field
    classes, never string concatenation): `trustbank/out/MT940_<account>_<yyyyMMdd>.fin`,
    `MT942_<account>_<yyyyMMdd>_<HHmm>.fin` of the mw-core-mock wallet `sim.files.trustbank.wallet-id`
    (SETTLEMENT), account `0011223344556`, BICs TRSTBDDHXXXX -> BITSBDDHXXXX. The ledger only through REST:
    `GET /core/v1/statements` with a realm services client-credentials token (client `mw-simulators`,
    secret `sim.files.trustbank.core.client-secret` = `KC_MW_SIMULATORS_SECRET`, token URI set by compose;
    mw-core-mock's compose block adds it to `MW_INTERNALAUTH_ALLOWEDCLIENTS`), and `GET
    /core-mock/v1/wallets/{id}` for the balance of a day without `balanceAfter`. One `:61:`/`:86:` pair per
    entry (`:86:` = txnId); closing = opening + the lines shown. Deterministic: same ledger, date and fault
    give the same bytes. INJECTED_MISMATCH kinds: MISSING_IN_PARTNER (first `count` lines dropped),
    MISSING_IN_UPAY (`count` extra credits `TB-EXTRA-<yyMMdd>-<n>` of n x 10 Tk), AMOUNT_MISMATCH (+1 Tk on
    the first `count`), DC_FLIP (their mark flipped).
  - DW (`DwDropService`, `DwDropValidator`, schemas `seed/v1/dw/schemas.json`, a draft for ticket 128): each
    new `control_<date>.json` `{date, files:[{name, rows, sha256}]}` of `dw/in` and the files it lists:
    present, sha256, data row count, header of the type (`transactions`, `wallet_snapshot`,
    `notifications`, `recon`); journal READ per file (`verdict` OK or the problem) and one for the control
    file (`verdict` ACCEPTED / REJECTED, `reasons`). A BUSINESS_ERROR scenario forces REJECTED (`FORCED
    <code>`); the file faults do not apply (it writes nothing).
  - File faults (`service/files/FileMocks`, engine `MockControl.fileFault`, dimension FILE of
    `dto/MockBehaviour`; only the file parties take them): MISSING_FILE (nothing written, journal SKIP),
    LATE_FILE (journal LATE, written after `delayMs`, default the timeout hold), DUPLICATE_FILE (also
    `<name>_DUP<ext>`), CORRUPT_FILE (first half + NUL `#CORRUPT#`), INJECTED_MISMATCH (`code` = kind,
    `count` default 1, refused for a kind the party lacks); scope `nextN` files or `key` (date or file
    name). Journal entries (method READ / WRITE / SKIP / LATE, path, headers `sha256`, `bytes`, `fault`,
    `verdict`, `reasons`; body the text up to 64 KiB). Tests: `TrustStatementGeneratorTest`,
    `AmlResponderTest`, `FileFaultScenarioTest`, `DwDropValidatorTest`, `SftpMocksIT` (the stack's
    atmoz/sftp image and a stub core), API folder 44, test console page `/files`.
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
  `secret/mw-simulators` (DB password, never overwritten; `sim.push.api-key` synced from `PUSH_API_KEY`) and the AppRole `mw-simulators`
  (configtree volume at `/run/mw/approle`).

## Contracts owned

- `api/mw-simulators/mock-control.v1.openapi.yaml` (ticket 102, test-only): the mock-control API;
  `contracts/MockControlContractTest` validates the served bodies and the behaviour list against it;
  `contracts/ContractDriftTest` (re-enabled in 102b, profiles `test` and `mock-control`) compares the
  generated document's `/mock-control/` paths with it. Tests of the SMPP parties: `SmppMockControlIT`
  (bind refusal, ESME_RTHROTTLED, missing / late / duplicate DLR, dropped enquire_link, DOWN, journal),
  `service/mockcontrol/LegacyScenariosTest` (the old payShield and SMPP bodies). The
  partner wire formats stay simulator-owned and not in `contracts/`.

## Contracts used

- `api/mw-ussd/ussd-http.v1.openapi.yaml` (client, USSD gateway simulator; own types `dto/UssdScreen`).
- `api/mw-core-mock/core-mock-control.v1.openapi.yaml` (ticket 103): `POST /mock-control/reset` of the
  global reset (`client/CoreMockControlClient`, own types `dto/ResetResult`; checked by
  `contracts/MockControlContractTest`).
- `api/mw-core-mock/core-wallet.v1.openapi.yaml` (`GET /core/v1/statements`) and
  `api/mw-core-mock/core-mock-seed.v1.openapi.yaml` (`GET /core-mock/v1/wallets/{walletId}`), ticket 123:
  the trust bank mock's ledger (`client/CoreLedgerClient`, own type `dto/LedgerEntry`; the bodies it reads
  checked by `contracts/MockControlContractTest`).
- Draft file format `contracts/files/aml/README.md` (ticket 123 wrote it, ticket 125 / mw-compliance owns
  and finalises it); the MT940 / MT942 are SWIFT formats.
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
