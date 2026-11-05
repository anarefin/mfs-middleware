# tests/e2e: black-box Cucumber suite (spec WP26)

Its own Gradle build (wrapper, `gradle/libs.versions.toml`); never depends on a service module.
It runs on the host against a running MVP stack and talks to it over the network only.

```
make up                       # the stack (fresh: make down DOWN_ARGS=-v first)
make e2e TAGS=@p2p            # every @p2p scenario
make e2e TAGS=@cash           # agent01 cash-in, customer01 cash-out to agent01
make e2e M=mw-audit-dw TAGS=@p2p   # @mw-audit-dw and (@p2p)
```

Per-worktree stacks: export the same `COMPOSE_PROJECT_NAME` (and `MW_HTTPS_PORT=0`,
`MW_OPS_SUBNET`) for `make up` and `make e2e`; the suite finds the APISIX port with
`docker compose port apisix 9443`. Override with `-Dmw.e2e.base-url=` / `MW_E2E_BASE_URL`.

## How the suite reaches the stack

Only APISIX is published, so every call goes through it:

| What | Path | Notes |
| --- | --- | --- |
| App API | `/app/v1/*` | the real channel route (`deploy/apisix/routes/app-v1.json`) |
| Tokens (realm customers) | `POST /auth/realms/customers/protocol/openid-connect/token` | `auth-customers-token.json`; pins Host/X-Forwarded-* to `keycloak:8080` so `iss` = `http://keycloak:8080/realms/customers`, the issuer APISIX's openid-connect discovery expects. Password = `KC_CUSTOMERS_TEST_PASSWORD` from the generated `deploy/compose/platform/.env` |
| Tokens (realm portals) | `POST /auth/realms/portals/protocol/openid-connect/token` | `auth-portals-token.json` (ticket 33), same pinning; client `mw-portal`, password `KC_PORTALS_TEST_PASSWORD`; `support/Keycloak.portalToken(user)` |
| Org portal API | `/portal/org/v1/*` | the real route (`portal-org-v1.json`); `support/PortalApi` |
| core-mock test/admin API | `/test/core-mock/v1/*` -> `mw-core-mock:8080/core-mock/v1/*` | `deploy/apisix/routes-test/test-core-mock.json`, TEST ONLY: PIN public key, `wallets/{id}` balances, `faults`. Present only after `make e2e` re-ran `apisix-init` with `MW_E2E_ROUTES=1` (a plain `make up` removes it); key-auth header `apikey` = `MW_E2E_API_KEY` from the generated `.env`, private source addresses only |
| Simulator control API | `/test/sim/*` -> `mw-simulators:8080/sim/*` | `deploy/apisix/routes-test/test-simulators.json`, TEST ONLY, same key-auth as core-mock; `support/Simulators.setScenario` / `reset` |
| Dev mail server (GreenMail) | `GET /test/mail/*` -> `greenmail:8080/api/*` | `deploy/apisix/routes-test/test-mail.json`, TEST ONLY, same key-auth; `support/Mailbox.messagesTo(email)` |
| Audit rows | `docker compose exec -T postgres psql` (superuser, read-only queries) | no API exposes `audit.event` before mw-admin-api (WP23), and the service role cannot read it |
| Internal requests (REVERSAL) | `docker compose exec -T kafka kafka-console-producer.sh` on `mw.txn.request.v1` | `support/TxnRequests`: plays the operations producer (channel SYSTEM) in `reversal.feature`; the broker is not published. The maker-checker path through mw-admin-api is `admin-maker-checker.feature` (ticket 37) |
| Tokens (realm ops) | `POST /auth/realms/ops/protocol/openid-connect/token` | `routes-test/auth-ops-token.json` (ticket 37, test-only: `MW_E2E_ROUTES=1`, key-auth `apikey` = `MW_E2E_API_KEY`, 10/min per address), same pinning; dev-only public client `mw-e2e-ops` (the console client `mw-admin-ui` has no direct grant, security review), password `KC_OPS_TEST_PASSWORD` plus the TOTP code from `KC_OPS_TEST_TOTP_SECRET` (`support/Totp`, HmacSHA256); dev-only users `ops-e2e-maker01` (ops-maker), `ops-e2e-checker01` (ops-checker); `support/Keycloak.opsToken(user)`. A stack whose Keycloak predates ticket 37 (or the `mw-e2e-ops` client) needs `make down DOWN_ARGS=-v` |
| Admin API | `/admin/v1/*` | the real route (`admin-v1.json`); `support/AdminApi` |

## Reuse in later flows (TOPUP, cash-in/out, bill pay, ...)

- `support/AppApi`: `request(txnType, creditParty, amountMinor, pin)` (add flow fields to the
  returned node), `submit`, `status`, `pollStatus`, `newIdempotencyKey()`, `FINAL`.
- TOPUP (`features/topup.feature`, `@topup`): step "tops up N poisha <operator> <product> airtime
  on <msisdn>"; money lands on the core's `SETTLEMENT` wallet (fee 200 on `FEE-POOL`); the OCS
  simulator refuses MSISDNs ending in `999` (other magic values: amount fraction .13 timeout,
  .77 5xx, .99 ambiguous, .55 malformed, all UNKNOWN, resolved by status inquiry).
- Unknown outcomes (`@tsq`, ticket 18): the response is 202 `PROCESSING`; assert the end with "the
  app shows the transaction as X [with error "C"] within N seconds" (first inquiry 30 s after
  UNKNOWN; a NOT_FOUND counts only ~60 s after the PAY, so a release takes ~2 min). "the OCS
  answers too late and never applies the top-up" forces a 25 s, not-applied OCS (reset with "the
  OCS behaves normally again" and by an `@After` hook); "the core drops its reply to the next
  PostTransfer" loses a P2P reply that QueryTransaction then finds. "no funds are held on <wallet>"
  compares the core's held amount with the noted one.
- BILL_PAY (`features/billpay.feature`, `@billpay`, ticket 21): "<user> lists the billers" /
  "the billers include DESCO"; "<user> fetches bill <n> of biller <code>" / "the amount due is N
  poisha" (sync fetch through mw-app-gateway -> mw-adapter-billpay internal API -> aggregator
  simulator `billagg`); "<user> pays N poisha on bill <n> of biller <code> with PIN ..." (credit
  party BILLER_ACCOUNT, `partner.billerCode`). Money lands on `SETTLEMENT` (fee 300 on
  `FEE-POOL`). Simulator magic values: amount due = 100 taka + last three digits of the bill
  number (1234567 -> 66700 poisha); bill numbers ending 404 unknown (`MW-PTR-BILLAGG-BILL_NOT_FOUND`),
  ending 999 refused on payment (`MW-PTR-BILLAGG-PAYMENT_REFUSED`); amount fraction .13 times out
  past the adapter's 25 s limit (202, then COMPLETED by status inquiry, `@tsq`).
- Partner callbacks (`features/billpay-callback.feature`, `@billpay @callback`, ticket 22): "the
  aggregator confirms payments only by callback[, sending each callback twice | , out of order |
  , N seconds late]" puts billagg in callback-only mode (202 PENDING, signed callback to
  `http://apisix:9080/partner/billagg/callback/payments`; reset by an `@After` hook); "the
  aggregator replays the transaction's callback" (simulator replay); "the aggregator calls back
  <STATUS> with code <C> for the transaction" signs with `BILLAGG_HMAC_SECRET` from the generated
  `.env` (`support/PartnerCallbacks`), "... with a bad signature" signs with a wrong key; audit
  steps "records a late <OUTCOME> callback", "records a conflicting <OUTCOME> callback", "records
  no late callback", "records the states \"...\" once each" (state rows only). The late
  scenario (`@tsq`) takes about 100 s.
- ADD_MONEY_BANK / C2W (`features/add-money-bank.feature`, `@addmoney @c2w`, ticket 23): "the bank
  confirms debits by callback[, sending each callback twice]" points the c2wbank simulator's
  signed callbacks at `http://apisix:9080/partner/c2wbank/callback/debits` (reset by an `@After`
  hook); "<user> adds N poisha from bank account <acct> at c2wbank with PIN ..." (body
  `debitParty` BANK_ACCOUNT, `partner.partnerId`, no `creditParty`); "the bank replays the
  transaction's callback". Money comes from `SETTLEMENT` (no fee). Magic values: account ending
  999 refused (`MW-PTR-C2WBANK-DEBIT_REFUSED`), amount fraction .13 = no answer within 25 s and no
  callback (`@tsq`, COMPLETED by status inquiry after about 60 s).
- USSD (`features/ussd.feature`, `@ussd`, ticket 26): the USSD gateway simulator in HTTP mode plays
  the MNO (`support/UssdGateway`, `POST /test/sim/ussdgw/sessions`; it posts every step to
  `http://apisix:9080/ussd/<mno>`, route `ussd-http-<mno>` -> mw-ussd, with that MNO's basic-auth
  credential: the simulator reads `sim.ussd-gateway.credentials.<mno>` from OpenBao, synced from the
  generated `USSD_<MNO>_GATEWAY_SECRET`). Steps: "<msisdn> dials <code> on
  <mno> and enters "9, 1, 01711000002, 10, 1, 1234"" (comma-separated inputs; `9` switches to
  English, amounts in taka), "the first USSD screens were:" (one column, `\n` = line break), "the
  USSD session ends with "..."" (prefix of the last screen), "the USSD session is still open on
  "..."". MNO `robi` is on HTTP; `gp` is SMPP (not served over HTTP).
  Other USSD flows (`features/ussd-flows.feature`, ticket 28): main menu 1 Send Money, 2 Cash Out
  (agent number), 3 Mobile Recharge (1 own number = the dialling MNO, 2 other number + operator
  list 1 gp 2 robi 3 banglalink 4 teletalk), 4 Pay Bill (billers sorted by code, 1 DESCO; the
  amount due is fetched). "the USSD session is still open on a screen ending with "..."".
- Device binding (`features/device-binding.feature`, `@device`, ticket 29): `support/Devices` gives
  each user one EC P-256 device for the run, bound on first use (`POST /app/v1/devices`, signed, with the binding OTP: `POST /app/v1/devices/otp`, then the
  code read from the English `DEVICE_BIND_OTP` SMS at the SMSC simulator for the token's `msisdn`),
  its session reopened after 4 idle minutes; `AppApi` sends `X-Device-Id` on every call and signs
  every submit (`AppApi.prepare` / `send`). Steps "<user> replays the last signed request" (same
  bytes, nonce, signature: 401 `MW-AUTH-006`) and "... with the body changed after signing" (401
  `MW-AUTH-004`). A run binds new devices, so the previous run's devices are replaced.
- REVERSAL (`features/reversal.feature`, `@reversal`): "operations reverses the transaction"
  publishes a REVERSAL of the scenario's (COMPLETED) transaction; the original then shows
  REVERSED (or MANUAL_REVIEW) in the app view. "the orchestrator has handled the reversal
  request" waits for the request's idempotency row (a repeated reversal leaves no other trace).
  "the core rejects the next <Command> with reason <R>" injects a REJECT fault.
- Maker-checker (`features/admin-maker-checker.feature`, `@admin @mw-admin-api`, ticket 37):
  "<maker> asks to <INQUIRE|FORCE_COMMIT|FORCE_RELEASE|REVERSE> the transaction because "..."",
  "<user> approves the change request", "<user> rejects the change request because "..."", "the
  change request is <PENDING|APPROVED|REJECTED|EXPIRED>", "the admin API answers <status> with code
  "..."" (last approve/reject), "the admin view shows the transaction as X within N seconds", "the
  admin audit trail of the transaction records the <APPROVED|REJECTED> change request" (via
  mw-audit-dw). A TOPUP lands in MANUAL_REVIEW with "the core rejects the next CommitHold with
  reason INJECTED_REJECT"; the forced commit is a fresh core command, so it is not refused again.
- Partner config (`features/partner-config.feature`, `@partner-config @mw-admin-api
  @mw-adapter-telco`, ticket 38; `steps/PartnerConfigSteps`): "the operators <disable|enable> partner
  <id>", "the operators set the time limit of partner <id> to <n> ms" (ops-e2e-maker01 asks, the
  maker's own approval is checked to be 403, ops-e2e-checker01 approves, the published version
  goes up), "the partner change has reached the adapters" (5 s for outbox relay + config consumer),
  "the OCS answers every top-up after <n> seconds" (applies it; reset by "the OCS behaves normally
  again" or the `@After`). An `@After` hook puts every changed partner back to its seed (enabled,
  20 s); the seed `ocs` comes from mw-admin-api's dev profile.
- Wallet unlock (`features/wallet-unlock.feature`, `@wallet-unlock @mw-admin-api`, ticket 52):
  "<maker> asks to unlock wallet <msisdn> because "..."" (then the change request steps above),
  "the core shows wallet <id> as <ACTIVE|LOCKED>" and "... within N seconds" (mw-core-mock's
  wallet view). It locks customer02 (01711000002), never customer01; an aborted run leaves it
  LOCKED until the next fresh stack (`make down DOWN_ARGS=-v`) or an unlock.
- Menus and templates (`features/admin-content.feature`, `@admin @mw-admin-api @mw-ussd @mw-notify`,
  ticket 39): `{token}` is a random 4-digit marker per scenario (`ScenarioState.token`).
  "<maker> proposes the main USSD menu for <mno> with the title "..."" (fixture
  `menus/main-e2e.json`, a copy of mw-ussd's seed, main screen title replaced in bn and en),
  "<maker> proposes the <SMS|EMAIL> template <CODE> in <bn|en> as "..."", then the approve steps
  above. "<msisdn> has a USSD session open on <mno> after dialling <code>" leaves a session open;
  "the open USSD session continues with "..."" resumes it (simulator `resume: true`, no new BEGIN);
  "the open USSD session shows the English main menu without "..."", "a new USSD session of <msisdn>
  on <mno> starts with "..." within N seconds" (re-dials until it does), "the services have had N
  seconds to take the published version", "<msisdn> gets an SMS starting with "..." within N
  seconds". The menu scenario uses bl (no other feature walks it); the template scenario restores
  the seeded English TXN_P2P_RECEIVED text at the end.
- Notifications (`features/notify.feature`, `@p2p @notify`, ticket 24): "<msisdn> gets the SMS
  \"...\"" waits (30 s) for that exact text at the SMSC simulator with a DELIVRD receipt
  (`{txnId}` stands for the scenario's transaction); "the SMS to <msisdn> about the transaction
  was sent as UCS2|GSM7 in N parts". `support/Smsc.messagesTo` reads `GET /sim/smsc/messages`
  through `/test/sim/*`. Texts are the seeded `notify.template` rows (bn when the request has no
  locale), the other party's number masked.
- Notifications per flow (`features/notify-flows.feature`, `@notify` + flow tags, ticket 25):
  success / failure / processing SMS of P2P, CASH_IN, CASH_OUT, TOPUP, BILL_PAY, ADD_MONEY_BANK;
  "<msisdn> gets the SMS \"...\" within N seconds" for late states (UNKNOWN, COMPLETED by status
  inquiry); "<address> gets the email \"<subject>\"" (multipart/alternative, text + HTML) and
  "<address> gets no email about the transaction" read GreenMail's REST API through the test-only
  route `/test/mail/*` -> `greenmail:8080/api/*` (`deploy/apisix/routes-test/test-mail.json`, same
  key-auth; `support/Mailbox`). Receipts go to registered addresses only: the dev stack seeds
  `notify.contact` (mw-notify `db/dev`) with the Keycloak users' emails (customer01 ->
  `customer01@example.test`).
- MERCHANT_PAY (`features/merchant.feature`, `@merchant`, ticket 30): customer01 pays merchant01
  (wallet `01911000001`, no fee) with the generic "submits MERCHANT_PAY of N poisha with
  counterparty ..." step; "<merchant> lists its collections", "<merchant> sees the transaction in its
  collections with the payer masked as 0171****001" (polls the projection), "<merchant> does not see
  the transaction in its collections" (merchant02, wallet `01911000002`, is the other merchant).
- Web checkout (`features/checkout.feature`, `@checkout`, ticket 31): `support/CheckoutApi` signs
  merchant calls for APISIX `hmac-auth` as the test-only consumer `merchant_demo` (key id
  `merchant-demo`, secret `CHECKOUT_DEMO_MERCHANT_SECRET` from the generated `.env`; Date, Digest,
  `@request-target`), calls the hosted page API `/checkout/pay/v1/{token}/*` and verifies the
  signed redirect from the contract (`verifyResult`). Steps: "the merchant creates a checkout
  session for N poisha", "... signed with a wrong secret" / "... without a signature" (401 at the
  edge), "the customer asks for an OTP for wallet <msisdn>" (reads the new code from the SMSC
  simulator, English template CHECKOUT_OTP), "the OTP was sent with OTP priority" (notify.message,
  read-only psql), "the customer confirms with [a wrong OTP | the OTP] and PIN \"...\"", "the
  checkout result is X", "the browser is redirected to the merchant's ok|fail URL with a X result
  the merchant verifies", "the merchant sees the session as X [paid by <masked>]". Merchant wallet
  01911000001 (merchant01); MERCHANT_PAY has no fee.
  Signed IPN (`@checkout @ipn`, ticket 32): every suite session's `ipnUrl` is the merchant IPN
  receiver simulator (`support/MerchantIpn.URL`, `http://mw-simulators:8080/merchant/ipn/e2e`; the
  dev stack allows that host and compresses the retry schedule to 10s,20s,30s,1m,2m). Steps: "the
  merchant's IPN endpoint fails the next N IPNs of the session with status S" (reset by an `@After`
  hook), "within N seconds the merchant has received the session's IPN K times, answering \"...\"",
  "the merchant accepted exactly one IPN of the session, a PAID result with a valid signature"
  (`MerchantIpn.verify`, written from the contract), "the merchant receives no further IPN of the
  session within N seconds".
- Acceptance traceability (ticket 43): `TRACEABILITY.md` maps every scenario to MVP acceptance
  criteria 1-5 (criterion 3 is ticket 44's chaos script) with a "Covers" item per flow, channel and
  outcome; `TraceabilityTest` (JUnit Jupiter, offline, runs with every `./gradlew test`) fails when
  a scenario is missing from the table, a row names no scenario, or a required item (every MVP flow
  x success / failure / tsq-commit / tsq-release / idempotent, USSD flows, operator search /
  timeline / audit / approved reversal, both USSD transports) is claimed by no scenario. A new
  scenario needs its row. Offline checks: `./gradlew test --tests mfs.mw.e2e.TraceabilityTest`;
  steps resolve: add `cucumber.execution.dry-run=true` to `junit-platform.properties` for one run.
- Timeout -> TSQ -> release without a hold (P2P, CASH_IN, CASH_OUT, MERCHANT_PAY app and checkout):
  "the core refuses the next <Command> and its reply is lost" (core-mock REJECT + DROP_REPLY, once
  each): POSTING times out, QueryTransaction finds REJECTED, the txn ends FAILED `MW-TMO-003` with
  no balance changed (about 40 s). Partner releases: "the aggregator answers too late and never
  applies the payment" / "the bank answers too late and never applies the debit" (billagg /
  c2wbank held 28 s, past the adapters' 27 s read limit, then 503 unapplied); "the aggregator |
  bank behaves normally again" waits until the simulator answered that request (`GET
  /sim/<partner>/requests`, seq noted when forced), then resets it (an `@After` hook resets too).
  Checkout: "the merchant sees the session as X within N seconds" (the confirm answers 202 PAYING).
- Operator console (`features/operator-console.feature`, `@admin @operator`, criterion 4;
  `steps/OperatorSteps`, `AdminApi.search`): "<user> searches the transactions by txnId", "<user>
  searches the <TYPE> transactions by MSISDN <any form>", "the search lists only the transaction",
  "the search finds the transaction as X with the initiator masked as 0171****001", "<user> opens
  the transaction in the admin view", "the admin timeline shows the states "..."" (STATE entries'
  `to`, in order, ending with the last), "the admin timeline shows the core command <Command>",
  "the admin audit trail shows the states "..."" (`txn.state.changed` entries of the detail view).
- `support/CoreMock`: `encryptPin` (RSA-OAEP SHA-256 with the core key), `balance`,
  `injectFault` / `resetFaults` (reset in an `@After` hook of the scenarios that inject).
- Org portal bulk (`features/bulk-upload.feature`, `@bulk @mw-portal-api`, ticket 33): "<user>
  uploads a bulk file of N rows" (`PortalApi.file`, 1.00 taka per row, fresh references), "...
  with these lines:", "<user> approves the batch" / "rejects the batch because "..."" / "looks
  at the batch", "the batch status is X", "the batch has N rows totalling M poisha", "the error
  details name these fields:". Users of realm portals: org-maker01, org-checker01,
  org-makerchecker01 (both roles) of ORG001, org-checker02 of ORG002. A stack whose Keycloak
  database predates ticket 33 needs `make down DOWN_ARGS=-v` (realm import does not update).
- Org bulk payout (`features/bulk-execution.feature`, `@bulk @mw-portal-api`, ticket 34): "no bulk
  batch of <user>'s org is still running" (Background: waits for APPROVED/EXECUTING batches of the
  org), "<user> has uploaded a payout file of N rows of X taka" (`PortalApi.payoutFile`, seeded
  customers `01711000001..01711001000` in turn) / "... a bulk file with these lines:", "<user>
  pauses / resumes the batch", "within N minutes <user> sees the batch COMPLETED", "the batch
  progress shows C completed, F failed and P poisha paid", "<user> sees the batch send no more
  rows", "<user>'s result file lists all N rows as COMPLETED, each with its own txnId" / "...
  shows:" (reference, status, errorCode), "<user>'s result file names the batch status X" (the
  `batchStatus` column, M3 gate). ORG001 pays from org wallet `01311000001`; at the
  default 50 rows/s the 10,000-row scenario takes about 4 minutes.
- Batch states of the M3 gate: "<user> lists the org's batches with status X" + "every listed
  batch has status X" (EXPIRED, COMPLETED_WITH_EXCEPTIONS are listable; reaching them needs 72 h or
  MANUAL_REVIEW / UNRESOLVED rows, so mw-portal-api's `BulkLifecycleIT` drives them on a test clock),
  "within N seconds the audit trail of the batch shows:" (action, actor; `AuditTrail.rowsOfBatch`
  reads resource `bulk-batch/<batchId>`, operator usernames unmasked). No step calls an internal
  `/internal/v1/**` API: those need a realm `services` client-credentials token (clients
  mw-portal-api, mw-admin-api); the suite checks them through the portal and the audit table.
- SP transaction query (`features/sp-query.feature`, `@sp-query @mw-portal-api`, ticket 35, review
  fix A3): "<user> lists the org's transactions [of the batch]", "the transaction list has N S T
  transaction paying <masked>", "every listed transaction is tagged INITIATOR|PAYEE", "the org's
  transaction list has the transaction tagged PAYEE" / "does not have the transaction" (the
  scenario's own txnId), "<user> opens the transaction" / "opens the first listed transaction",
  "the opened transaction is tagged PAYEE", "the timeline ends "A -> B"" (state changes only;
  the portal drops the orchestrator's core-command entries). Payee scope in the dev stack:
  ORG002 collects for biller DESCO and merchant wallet 01911000001 (mw-orchestrator `compose.yml`,
  `mw.orchestrator.search.org-payees`).
- `support/Keycloak.customerToken(user)`; `support/AuditTrail.rowsOfTxn` / `verifyChain`;
  `support/Waits.until` for anything asynchronous.
- Steps (`steps/`, one `ScenarioState` per scenario via PicoContainer): sign in, send P2P,
  `<user> submits <TYPE> of <n> poisha with counterparty <msisdn> and PIN "<pin>"` (any type
  whose body is the plain request), status/error/txnId assertions, "the app shows the transaction as X", balances noted / changed
  by / unchanged, audit states and chain. Add flow steps next to them; keep step text generic.
- Stability rule: never rely on leftover state. Fresh Idempotency-Key per request, balances
  compared before/after (never absolute), amounts small (seeded customers hold 10,000 BDT with a
  15,000 BDT daily limit). Tag every scenario with the flow tag and the `@mw-<module>` tags it
  exercises.
