# MVP acceptance traceability (ticket 43, spec WP26)

Maps every scenario of `src/test/resources/features` to the MVP acceptance criteria (spec
"MVP acceptance criteria"). `TraceabilityTest` (offline, part of `./gradlew test`) fails when a
scenario is missing here, a row names no scenario, or a coverage item the criteria demand is
claimed by no scenario.

| # | Criterion | Where it is proved |
| --- | --- | --- |
| 1 | Every MVP flow end to end from its channel: success, business failure, timeout -> TSQ -> commit, timeout -> TSQ -> release | rows with criterion 1; the "Covers" column names flow, channel and outcome |
| 2 | The same idempotency key never moves money twice (core-mock balances) | rows with criterion 2 (`idempotent` items: same key resent, balances compared) |
| 3 | Killing a container mid-flow leaves no non-terminal txn | not this suite: ticket 44 (WP27 chaos script, `make chaos`) |
| 4 | Operator finds a txn by txnId or MSISDN, sees timeline and audit, reversal approved by a second user | rows with criterion 4 (`ops` items), mainly `operator-console.feature` |
| 5 | USSD for the live MNO over its transport; switching SMPP <-> HTTP needs no code change | rows with criterion 5: robi over HTTP, gp over SMPP, same menu engine and menus. The switch itself is configuration only (`mw.ussd.mno.<mno>.transport`), proved by mw-ussd's `UssdMenuWalkSuite` (the same walk over both transports); the shared stack is not restarted with another transport by this suite |

Timeout -> TSQ -> release for the flows without a hold (P2P, CASH_IN, CASH_OUT, MERCHANT_PAY app and
checkout) means: the core's answer is lost, the status inquiry (QueryTransaction) finds nothing
applied and the transaction ends FAILED (`MW-TMO-003`) with no balance changed. The suite gets
there with two core-mock faults on the same PostTransfer: REJECT (no effect, logged REJECTED) and
DROP_REPLY (no answer). For ADD_MONEY_BANK (inbound, no hold) the bank never debits and the
transaction ends FAILED.

Covers vocabulary: `<TXN_TYPE> <app|checkout|ussd> <success|failure|tsq-commit|tsq-release|idempotent>`,
`ops <search-txnId|search-msisdn|timeline|audit|reversal-approved>`, `ussd <http|smpp>`.
Criteria `-` = supporting scenario (edge security, bulk, portal, config), no MVP criterion.

## Scenarios

| Feature | Scenario | Criteria | Covers |
| --- | --- | --- | --- |
| add-money-bank.feature | a confirmed debit credits the wallet once and holds nothing | 1 | ADD_MONEY_BANK app success |
| add-money-bank.feature | a replayed bank callback has no second effect | 1, 2 | |
| add-money-bank.feature | a callback the bank sends twice credits the wallet once | 1, 2 | |
| add-money-bank.feature | a debit the bank refuses fails and moves nothing | 1 | ADD_MONEY_BANK app failure |
| add-money-bank.feature | a wrong PIN is refused by the core and the bank is never asked | 1 | ADD_MONEY_BANK app failure |
| add-money-bank.feature | a bank timeout is resolved by status inquiry and the wallet is credited | 1 | ADD_MONEY_BANK app tsq-commit |
| add-money-bank.feature | an add-money request sent twice with the same idempotency key credits the wallet once | 2 | ADD_MONEY_BANK app idempotent |
| add-money-bank.feature | a bank timeout the debit did not survive is resolved by status inquiry and fails | 1 | ADD_MONEY_BANK app tsq-release |
| admin-content.feature | an approved menu text change shows in the next USSD session but not in a running one | - | |
| admin-content.feature | an approved SMS template change is used for the next notification | - | |
| admin-maker-checker.feature | a top-up in MANUAL_REVIEW is force-committed after a second operator approves | 4 | |
| admin-maker-checker.feature | a completed P2P is reversed after a second operator approves | 4 | ops reversal-approved; ops audit |
| admin-maker-checker.feature | a rejected reversal changes nothing | 4 | |
| billpay-callback.feature | a callback-only payment is completed by the aggregator's callback | 1 | BILL_PAY app success |
| billpay-callback.feature | a refusal reported by callback releases the hold | 1 | BILL_PAY app failure |
| billpay-callback.feature | a duplicate callback has no second effect | 1, 2 | |
| billpay-callback.feature | a stale PENDING callback after the outcome changes nothing | 1 | |
| billpay-callback.feature | a late callback after the status inquiry resolved the payment is recorded and changes nothing | 1 | BILL_PAY app tsq-commit |
| billpay-callback.feature | a late callback contradicting the resolution is recorded as a conflict and changes nothing | 1 | |
| billpay-callback.feature | a callback with a bad signature is rejected and changes nothing | 1 | |
| billpay.feature | the customer sees the billers and fetches the amount due on a bill | 1 | |
| billpay.feature | an unknown bill number is a mapped aggregator error | 1 | BILL_PAY app failure |
| billpay.feature | an unknown biller is not found | 1 | |
| billpay.feature | a paid bill debits the payer once and leaves nothing held | 1 | BILL_PAY app success |
| billpay.feature | a biller refusal releases the hold and moves no money | 1 | BILL_PAY app failure |
| billpay.feature | a bill payment without a biller is refused by the app API | 1 | |
| billpay.feature | an aggregator timeout is resolved by status inquiry and the hold is committed | 1 | BILL_PAY app tsq-commit |
| billpay.feature | a paid bill leaves an audit trail of the hold path | 1 | |
| billpay.feature | a bill payment sent twice with the same idempotency key pays the bill once | 2 | BILL_PAY app idempotent |
| billpay.feature | an aggregator timeout the payment did not survive is resolved by status inquiry and released | 1 | BILL_PAY app tsq-release |
| bulk-execution.feature | a 10,000-row batch completes, the result file matches and the org balance is correct | - | |
| bulk-execution.feature | a row the core refuses is reported in the result file and costs the org nothing | - | |
| bulk-execution.feature | a paused batch sends nothing until it is resumed, then completes | - | |
| bulk-upload.feature | a valid file is stored awaiting approval and a different checker approves it | - | |
| bulk-upload.feature | a checker rejects a batch | - | |
| bulk-upload.feature | the maker cannot approve their own batch, even with the checker role | - | |
| bulk-upload.feature | another org cannot see or approve the batch | - | |
| bulk-upload.feature | the org lists its batches by any batch status, the M3 ones included | - | |
| bulk-upload.feature | 10,000 rows are accepted | - | |
| bulk-upload.feature | 10,001 rows are rejected | - | |
| bulk-upload.feature | malformed rows are reported per line and nothing is stored | - | |
| cash.feature | an agent cash-in moves the amount from the agent's float to the customer | 1 | CASH_IN app success |
| cash.feature | a customer cash-out pays the agent, the fee goes to the pool | 1 | CASH_OUT app success |
| cash.feature | a cash-in the agent's float cannot cover is rejected and moves no money | 1 | CASH_IN app failure |
| cash.feature | a cash-out to a wallet that is not an agent is refused by the core | 1 | CASH_OUT app failure |
| cash.feature | the agent's own wallet is never a counterparty | 1 | |
| cash.feature | <user> may not initiate <type> | 1 | |
| cash.feature | an agent cash-in sent twice with the same idempotency key moves the money once | 2 | CASH_IN app idempotent |
| cash.feature | a customer cash-out sent twice with the same idempotency key moves the money once | 2 | CASH_OUT app idempotent |
| cash.feature | a cash-in whose core reply is lost is found by QueryTransaction and completes | 1 | CASH_IN app tsq-commit |
| cash.feature | a cash-in whose core refusal is lost is found by QueryTransaction and fails | 1 | CASH_IN app tsq-release |
| cash.feature | a cash-out whose core reply is lost is found by QueryTransaction and completes | 1 | CASH_OUT app tsq-commit |
| cash.feature | a cash-out whose core refusal is lost is found by QueryTransaction and fails | 1 | CASH_OUT app tsq-release |
| checkout.feature | create session, pay with OTP and PIN, signed redirect the merchant verifies | 1 | MERCHANT_PAY checkout success |
| checkout.feature | a payment the core rejects fails the session and sends the browser to the fail URL | 1 | MERCHANT_PAY checkout failure |
| checkout.feature | the merchant's IPN endpoint fails twice, then the signed IPN is delivered once | 1 | |
| checkout.feature | a second confirmation of a paid session is refused and pays nothing again | 2 | MERCHANT_PAY checkout idempotent |
| checkout.feature | a checkout payment whose core reply is lost is found by QueryTransaction and paid | 1 | MERCHANT_PAY checkout tsq-commit |
| checkout.feature | a checkout payment whose core refusal is lost is found by QueryTransaction and fails | 1 | MERCHANT_PAY checkout tsq-release |
| checkout.feature | a merchant request with a bad HMAC signature is refused at the edge | 1 | |
| checkout.feature | a merchant request without a signature is refused at the edge | 1 | |
| device-binding.feature | a P2P with a valid signature passes | 1 | |
| device-binding.feature | a replayed signed request is rejected and moves no money again | 1, 2 | |
| device-binding.feature | a body changed after signing is rejected | 1 | |
| merchant.feature | a customer pays a merchant and the merchant sees the collection | 1 | MERCHANT_PAY app success |
| merchant.feature | a payment the customer's balance cannot cover is rejected and moves no money | 1 | MERCHANT_PAY app failure |
| merchant.feature | a merchant payment to a wallet that is not a merchant is refused by the core | 1 | MERCHANT_PAY app failure |
| merchant.feature | <user> may not initiate <type> | 1 | |
| merchant.feature | <user> has no collections view | 1 | |
| merchant.feature | a merchant payment sent twice with the same idempotency key pays the merchant once | 2 | MERCHANT_PAY app idempotent |
| merchant.feature | a merchant payment whose core reply is lost is found by QueryTransaction and completes | 1 | MERCHANT_PAY app tsq-commit |
| merchant.feature | a merchant payment whose core refusal is lost is found by QueryTransaction and fails | 1 | MERCHANT_PAY app tsq-release |
| notify-flows.feature | a P2P the core refuses tells the sender it failed | 1 | |
| notify-flows.feature | a P2P whose core reply is lost tells the sender it is processing, then that it was sent | 1 | |
| notify-flows.feature | a cash-in notifies the agent and the customer | 1 | |
| notify-flows.feature | a cash-in the core refuses tells the agent it failed | 1 | |
| notify-flows.feature | a cash-out notifies the customer (with the fee) and the agent | 1 | |
| notify-flows.feature | a cash-out the core refuses tells the customer it failed | 1 | |
| notify-flows.feature | a top-up notifies the payer and the recharged number | 1 | |
| notify-flows.feature | a top-up the operator refuses tells the payer it failed | 1 | |
| notify-flows.feature | a top-up the operator answers too late tells the payer it is processing, then that it succeeded | 1 | |
| notify-flows.feature | a bill payment sends an SMS and an email receipt | 1 | |
| notify-flows.feature | a bill payment the aggregator refuses tells the payer it failed, without a receipt | 1 | |
| notify-flows.feature | a bill payment the aggregator answers too late tells the payer it is processing | 1 | |
| notify-flows.feature | money added from a bank account sends an SMS and an email receipt | 1 | |
| notify-flows.feature | a debit the bank refuses tells the customer it failed | 1 | |
| notify-flows.feature | a bank that does not answer in time tells the customer it is processing | 1 | |
| notify.feature | sender and receiver get the right SMS text | 1 | |
| operator-console.feature | an operator finds a P2P by txnId and by MSISDN and sees its timeline and audit trail | 4 | ops search-txnId; ops search-msisdn; ops timeline; ops audit |
| operator-console.feature | an operator finds a P2P by MSISDN and reverses it once a second operator approves | 4 | ops search-msisdn; ops reversal-approved; ops audit |
| p2p.feature | a P2P completes and moves the money once | 1 | P2P app success |
| p2p.feature | a core rejection (<reason>) moves no money | 1 | P2P app failure |
| p2p.feature | the same idempotency key sent twice debits once and returns the same txnId | 2 | P2P app idempotent |
| p2p.feature | the same idempotency key with a different body is refused | 2 | |
| p2p.feature | a completed P2P leaves a verifiable audit trail | 1 | |
| p2p.feature | a transfer whose core reply is lost is found by QueryTransaction and completes | 1 | P2P app tsq-commit |
| p2p.feature | a transfer whose core refusal is lost is found by QueryTransaction and fails | 1 | P2P app tsq-release |
| partner-config.feature | a disabled telco partner refuses top-ups, and works again once enabled | - | |
| partner-config.feature | a lowered time limit applies to the next call | - | |
| reversal.feature | a completed P2P is reversed and every balance is restored | 4 | |
| reversal.feature | a second reversal of the same transaction moves no money again | 2, 4 | |
| reversal.feature | a reversal the core refuses goes to manual review with an audit entry | 4 | |
| sp-query.feature | an org sees its batch children with their timeline, another org sees none of them | - | |
| sp-query.feature | the org a biller belongs to sees the bill payments it collects, tagged PAYEE | - | |
| sp-query.feature | a portal user without an org role may not query | - | |
| topup.feature | a successful top-up debits the payer once and leaves nothing held | 1 | TOPUP app success |
| topup.feature | an operator refusal releases the hold and moves no money | 1 | TOPUP app failure |
| topup.feature | a wrong PIN is refused by the core before anything is held | 1 | TOPUP app failure |
| topup.feature | a hold whose reply is lost is found by a status query and released | 1 | |
| topup.feature | the same idempotency key sent twice tops up once and returns the same txnId | 2 | TOPUP app idempotent |
| topup.feature | a top-up without operator is refused by the app API | 1 | |
| topup.feature | an OCS timeout is resolved by status inquiry and the hold is committed | 1 | TOPUP app tsq-commit |
| topup.feature | an OCS timeout the top-up did not survive is resolved by status inquiry and released | 1 | TOPUP app tsq-release |
| topup.feature | a top-up resolved by status inquiry records UNKNOWN in its audit trail | 1 | |
| topup.feature | a completed top-up leaves an audit trail of the hold path | 1 | |
| ussd-flows.feature | a top-up of the own number over USSD in English completes | 1, 5 | TOPUP ussd success; ussd http |
| ussd-flows.feature | a top-up of another number over USSD in Bangla completes | 1, 5 | TOPUP ussd success |
| ussd-flows.feature | an operator refusal over USSD moves no money | 1, 5 | |
| ussd-flows.feature | a bill pay over USSD in English fetches the bill and pays it | 1, 5 | BILL_PAY ussd success |
| ussd-flows.feature | a bill pay over USSD in Bangla completes | 1, 5 | BILL_PAY ussd success |
| ussd-flows.feature | an unknown bill number is refused and asked again, and no money moves | 1, 5 | |
| ussd-flows.feature | a customer cash-out to an agent over USSD completes | 1, 5 | CASH_OUT ussd success |
| ussd-smpp.feature | a P2P over SMPP USSD walks the same menu and moves the money once | 1, 5 | P2P ussd success; ussd smpp |
| ussd-smpp.feature | a bad number is asked again over SMPP and no money moves | 1, 5 | ussd smpp |
| ussd-smpp.feature | the binds are kept alive with enquire_link | 5 | ussd smpp |
| ussd-smpp.feature | after the gateway drops the binds mw-ussd reconnects and USSD works again | 5 | ussd smpp |
| ussd-smpp.feature | throttling, queue full and generic_nack are retried after a delay | 5 | ussd smpp |
| ussd.feature | a P2P over USSD in English completes and moves the money once | 1, 5 | P2P ussd success; ussd http |
| ussd.feature | a P2P over USSD in Bangla completes and moves the money once | 1, 5 | P2P ussd success; ussd http |
| ussd.feature | a bad number is refused and asked again, and no money moves | 1, 5 | ussd http |
| ussd.feature | a wrong PIN is refused by the core and no money moves | 1, 5 | ussd http |
| wallet-unlock.feature | five wrong PINs lock the wallet, an approved unlock lets the next correct-PIN P2P succeed | - | |

## Stack features the scenarios need

Every scenario needs the MVP stack from `make up` and the test-only routes `make e2e` applies
(`MW_E2E_ROUTES=1`: `/test/core-mock/v1/*`, `/test/sim/*`, `/test/mail/*`, the ops token route).

| Scenarios | Needs |
| --- | --- |
| `@tsq` with "the core drops its reply" / "the core refuses ... and its reply is lost" (p2p, cash, merchant, checkout, notify-flows) | core-mock faults DROP_REPLY and REJECT on `/test/core-mock/v1/faults`; orchestrator POSTING timeout 5 s and inquiry schedule 30 s, 1 m (resolved in about 40 s) |
| `@tsq` topup, billpay, add-money-bank | mw-simulators control API `/test/sim/{ocs,billagg,c2wbank}/scenario` and `/requests`; adapter read limits (telco 22 s, billpay / bank 27 s); a release takes up to about 2-3 minutes (NOT_FOUND counts only after partner limit + 30 s grace) |
| checkout | APISIX hmac-auth consumer `merchant_demo` (`CHECKOUT_DEMO_MERCHANT_SECRET`), SMSC simulator for the OTP, merchant IPN receiver simulator |
| operator-console, admin-*, wallet-unlock, partner-config | realm ops users `ops-e2e-maker01` / `ops-e2e-checker01` with the seeded TOTP secret, mw-admin-api, mw-audit-dw |
| ussd, ussd-flows | USSD gateway simulator in HTTP mode, APISIX `ussd-http-<mno>` routes with the generated basic-auth credentials (robi; bl for admin-content) |
| ussd-smpp | USSD gateway simulator SMPP server (gp, port 2776) |
| notify, notify-flows | SMSC simulator, GreenMail through `/test/mail/*`, seeded `notify.contact` |
| bulk-*, sp-query | realm portals users, mw-portal-api, org wallet `01311000001` |

Stability (run twice in a row): every scenario uses fresh idempotency keys and compares balances
before and after; faults are once-only and reset by `@After` hooks, simulator scenarios are reset
by `@After` hooks, and the "behaves normally again" steps of billagg / c2wbank first wait until the
simulator answered the forced payment, so the reset never races the PAY.
