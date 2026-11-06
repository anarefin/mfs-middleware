# MVP acceptance traceability of the API suite (tickets 43, 58)

Maps every request of the Newman collection (`mfs-mw.postman_collection.json`, one request per
scenario) to the MVP acceptance criteria (spec "MVP acceptance criteria"). It carries over the
table of the Cucumber suite (tests/e2e, removed in ticket 58) row for row; `PARITY.md` maps each
Cucumber scenario to its request. `scripts/check-api-traceability.sh` (offline, part of
`make contracts-lint`; self-test `scripts/test/test_api_traceability.sh`) fails when a request is
missing here, a row names no request, a request name lacks its requirement tags, a Covers item is
claimed by a request without the tag its flow or outcome needs (`P2P app tsq-commit` needs `p2p`
and `tsq`; `ops *` needs `operator` or `admin`; `<FLOW> ussd *` and `ussd http` need `ussd`,
`ussd smpp` needs `smpp`), or a coverage item the criteria demand is claimed by no request.

Requirement tags are the Cucumber flow tags without `@` (`p2p`, `cash`, `topup`, `billpay`,
`callback`, `addmoney`, `c2w`, `merchant`, `checkout`, `ipn`, `notify`, `tsq`, `device`, `reversal`,
`admin`, `operator`, `partner-config`, `wallet-unlock`, `ussd`, `smpp`, `bulk`, `sp-query`, `dlq` (retry topics to the DLQ, ticket 58, no Cucumber flow), plus
`health` and `seed` for folder 00); the module tags (`@mw-*`) were run filters, not requirements, and
are not carried over (`make api-test FOLDER=` selects folders).

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

## Requests

| Folder | Request | Criteria | Covers |
| --- | --- | --- | --- |
| 00 | [health seed] the seeded personas sign in and the seeded wallets exist | - | |
| 00 | [health] the edge and every test-only route answer | - | |
| 00 | [health] the money path is warm: a P2P per app user completes | - | |
| 08 | [addmoney c2w] a confirmed debit credits the wallet once and holds nothing | 1 | ADD_MONEY_BANK app success |
| 08 | [addmoney c2w callback] a replayed bank callback has no second effect | 1, 2 |  |
| 08 | [addmoney c2w callback] a callback the bank sends twice credits the wallet once | 1, 2 |  |
| 08 | [addmoney c2w] a debit the bank refuses fails and moves nothing | 1 | ADD_MONEY_BANK app failure |
| 08 | [addmoney c2w] a wrong PIN is refused by the core and the bank is never asked | 1 | ADD_MONEY_BANK app failure |
| 08 | [addmoney c2w tsq] a bank timeout is resolved by status inquiry and the wallet is credited | 1 | ADD_MONEY_BANK app tsq-commit |
| 08 | [addmoney c2w] an add-money request sent twice with the same idempotency key credits the wallet once | 2 | ADD_MONEY_BANK app idempotent |
| 08 | [addmoney c2w tsq] a bank timeout the debit did not survive is resolved by status inquiry and fails | 1 | ADD_MONEY_BANK app tsq-release |
| 17 | [admin] an approved menu text change shows in the next USSD session but not in a running one | - |  |
| 17 | [admin p2p] an approved SMS template change is used for the next notification | - |  |
| 13 | [admin topup] a top-up in MANUAL_REVIEW is force-committed after a second operator approves | 4 |  |
| 13 | [admin reversal] a completed P2P is reversed after a second operator approves | 4 | ops reversal-approved; ops audit |
| 13 | [admin reversal] a rejected reversal changes nothing | 4 |  |
| 07 | [billpay callback] a callback-only payment is completed by the aggregator's callback | 1 | BILL_PAY app success |
| 07 | [billpay callback] a refusal reported by callback releases the hold | 1 | BILL_PAY app failure |
| 07 | [billpay callback] a duplicate callback has no second effect | 1, 2 |  |
| 07 | [billpay callback] a stale PENDING callback after the outcome changes nothing | 1 |  |
| 07 | [billpay callback tsq] a late callback after the status inquiry resolved the payment is recorded and changes nothing | 1 | BILL_PAY app tsq-commit |
| 07 | [billpay callback] a late callback contradicting the resolution is recorded as a conflict and changes nothing | 1 |  |
| 07 | [billpay callback] a callback with a bad signature is rejected and changes nothing | 1 |  |
| 06 | [billpay] the customer sees the billers and fetches the amount due on a bill | 1 |  |
| 06 | [billpay] an unknown bill number is a mapped aggregator error | 1 | BILL_PAY app failure |
| 06 | [billpay] an unknown biller is not found | 1 |  |
| 06 | [billpay] a paid bill debits the payer once and leaves nothing held | 1 | BILL_PAY app success |
| 06 | [billpay] a biller refusal releases the hold and moves no money | 1 | BILL_PAY app failure |
| 06 | [billpay] a bill payment without a biller is refused by the app API | 1 |  |
| 06 | [billpay tsq] an aggregator timeout is resolved by status inquiry and the hold is committed | 1 | BILL_PAY app tsq-commit |
| 06 | [billpay] a paid bill leaves an audit trail of the hold path | 1 |  |
| 06 | [billpay] a bill payment sent twice with the same idempotency key pays the bill once | 2 | BILL_PAY app idempotent |
| 06 | [billpay tsq] an aggregator timeout the payment did not survive is resolved by status inquiry and released | 1 | BILL_PAY app tsq-release |
| 90 | [bulk] a 10,000-row batch completes, the result file matches and the org balance is correct | - |  |
| 90 | [dlq] a request the orchestrator always refuses is retried three times and parked on its DLQ, nothing stored | - |  |
| 22 | [bulk] a row the core refuses is reported in the result file and costs the org nothing | - |  |
| 22 | [bulk] a paused batch sends nothing until it is resumed, then completes | - |  |
| 21 | [bulk] a valid file is stored awaiting approval and a different checker approves it | - |  |
| 21 | [bulk] a checker rejects a batch | - |  |
| 21 | [bulk] the maker cannot approve their own batch, even with the checker role | - |  |
| 21 | [bulk] another org cannot see or approve the batch | - |  |
| 21 | [bulk] the org lists its batches by any batch status, the M3 ones included | - |  |
| 21 | [bulk] 10,000 rows are accepted | - |  |
| 21 | [bulk] 10,001 rows are rejected | - |  |
| 21 | [bulk] malformed rows are reported per line and nothing is stored | - |  |
| 03 | [cash] an agent cash-in moves the amount from the agent's float to the customer | 1 | CASH_IN app success |
| 03 | [cash] a customer cash-out pays the agent, the fee goes to the pool | 1 | CASH_OUT app success |
| 03 | [cash] a cash-in the agent's float cannot cover is rejected and moves no money | 1 | CASH_IN app failure |
| 03 | [cash] a cash-out to a wallet that is not an agent is refused by the core | 1 | CASH_OUT app failure |
| 03 | [cash] the agent's own wallet is never a counterparty | 1 |  |
| 03 | [cash] customer01 may not initiate CASH_IN | 1 |  |
| 03 | [cash] agent01 may not initiate CASH_OUT | 1 |  |
| 03 | [cash] an agent cash-in sent twice with the same idempotency key moves the money once | 2 | CASH_IN app idempotent |
| 03 | [cash] a customer cash-out sent twice with the same idempotency key moves the money once | 2 | CASH_OUT app idempotent |
| 03 | [cash tsq] a cash-in whose core reply is lost is found by QueryTransaction and completes | 1 | CASH_IN app tsq-commit |
| 03 | [cash tsq] a cash-in whose core refusal is lost is found by QueryTransaction and fails | 1 | CASH_IN app tsq-release |
| 03 | [cash tsq] a cash-out whose core reply is lost is found by QueryTransaction and completes | 1 | CASH_OUT app tsq-commit |
| 03 | [cash tsq] a cash-out whose core refusal is lost is found by QueryTransaction and fails | 1 | CASH_OUT app tsq-release |
| 09 | [checkout] create session, pay with OTP and PIN, signed redirect the merchant verifies | 1 | MERCHANT_PAY checkout success |
| 09 | [checkout] a payment the core rejects fails the session and sends the browser to the fail URL | 1 | MERCHANT_PAY checkout failure |
| 09 | [checkout ipn] the merchant's IPN endpoint fails twice, then the signed IPN is delivered once | 1 |  |
| 09 | [checkout] a second confirmation of a paid session is refused and pays nothing again | 2 | MERCHANT_PAY checkout idempotent |
| 09 | [checkout tsq] a checkout payment whose core reply is lost is found by QueryTransaction and paid | 1 | MERCHANT_PAY checkout tsq-commit |
| 09 | [checkout tsq] a checkout payment whose core refusal is lost is found by QueryTransaction and fails | 1 | MERCHANT_PAY checkout tsq-release |
| 09 | [checkout] a merchant request with a bad HMAC signature is refused at the edge | 1 |  |
| 09 | [checkout] a merchant request without a signature is refused at the edge | 1 |  |
| 02 | [device p2p] a P2P with a valid signature passes | 1 |  |
| 02 | [device p2p] a replayed signed request is rejected and moves no money again | 1, 2 |  |
| 02 | [device p2p] a body changed after signing is rejected | 1 |  |
| 04 | [merchant] a customer pays a merchant and the merchant sees the collection | 1 | MERCHANT_PAY app success |
| 04 | [merchant] a payment the customer's balance cannot cover is rejected and moves no money | 1 | MERCHANT_PAY app failure |
| 04 | [merchant] a merchant payment to a wallet that is not a merchant is refused by the core | 1 | MERCHANT_PAY app failure |
| 04 | [merchant] agent01 may not initiate MERCHANT_PAY | 1 |  |
| 04 | [merchant] merchant01 may not initiate MERCHANT_PAY | 1 |  |
| 04 | [merchant] merchant01 may not initiate P2P | 1 |  |
| 04 | [merchant] customer01 has no collections view | 1 |  |
| 04 | [merchant] agent01 has no collections view | 1 |  |
| 04 | [merchant] a merchant payment sent twice with the same idempotency key pays the merchant once | 2 | MERCHANT_PAY app idempotent |
| 04 | [merchant tsq] a merchant payment whose core reply is lost is found by QueryTransaction and completes | 1 | MERCHANT_PAY app tsq-commit |
| 04 | [merchant tsq] a merchant payment whose core refusal is lost is found by QueryTransaction and fails | 1 | MERCHANT_PAY app tsq-release |
| 11 | [p2p notify] a P2P the core refuses tells the sender it failed | 1 |  |
| 11 | [p2p notify tsq] a P2P whose core reply is lost tells the sender it is processing, then that it was sent | 1 |  |
| 11 | [cash notify] a cash-in notifies the agent and the customer | 1 |  |
| 11 | [cash notify] a cash-in the core refuses tells the agent it failed | 1 |  |
| 11 | [cash notify] a cash-out notifies the customer (with the fee) and the agent | 1 |  |
| 11 | [cash notify] a cash-out the core refuses tells the customer it failed | 1 |  |
| 11 | [topup notify] a top-up notifies the payer and the recharged number | 1 |  |
| 11 | [topup notify] a top-up the operator refuses tells the payer it failed | 1 |  |
| 11 | [topup notify tsq] a top-up the operator answers too late tells the payer it is processing, then that it succeeded | 1 |  |
| 11 | [billpay notify] a bill payment sends an SMS and an email receipt | 1 |  |
| 11 | [billpay notify] a bill payment the aggregator refuses tells the payer it failed, without a receipt | 1 |  |
| 11 | [billpay notify tsq] a bill payment the aggregator answers too late tells the payer it is processing | 1 |  |
| 11 | [addmoney c2w notify] money added from a bank account sends an SMS and an email receipt | 1 |  |
| 11 | [addmoney c2w notify] a debit the bank refuses tells the customer it failed | 1 |  |
| 11 | [addmoney c2w notify tsq] a bank that does not answer in time tells the customer it is processing | 1 |  |
| 10 | [p2p notify] sender and receiver get the right SMS text | 1 |  |
| 14 | [admin operator p2p] an operator finds a P2P by txnId and by MSISDN and sees its timeline and audit trail | 4 | ops search-txnId; ops search-msisdn; ops timeline; ops audit |
| 14 | [admin operator reversal] an operator finds a P2P by MSISDN and reverses it once a second operator approves | 4 | ops search-msisdn; ops reversal-approved; ops audit |
| 01 | [p2p] a P2P completes and moves the money once | 1 | P2P app success |
| 01 | [p2p] a core rejection (wrong PIN) moves no money | 1 | P2P app failure |
| 01 | [p2p] a core rejection (over per-txn limit) moves no money | 1 | P2P app failure |
| 01 | [p2p] the same idempotency key sent twice debits once and returns the same txnId | 2 | P2P app idempotent |
| 01 | [p2p] the same idempotency key with a different body is refused | 2 |  |
| 01 | [p2p] a completed P2P leaves a verifiable audit trail | 1 |  |
| 01 | [p2p tsq] a transfer whose core reply is lost is found by QueryTransaction and completes | 1 | P2P app tsq-commit |
| 01 | [p2p tsq] a transfer whose core refusal is lost is found by QueryTransaction and fails | 1 | P2P app tsq-release |
| 15 | [admin partner-config topup] a disabled telco partner refuses top-ups, and works again once enabled | - |  |
| 15 | [admin partner-config topup tsq] a lowered time limit applies to the next call | - |  |
| 12 | [reversal] a completed P2P is reversed and every balance is restored | 4 |  |
| 12 | [reversal] a second reversal of the same transaction moves no money again | 2, 4 |  |
| 12 | [reversal] a reversal the core refuses goes to manual review with an audit entry | 4 |  |
| 23 | [sp-query bulk] an org sees its batch children with their timeline, another org sees none of them | - |  |
| 23 | [sp-query billpay] the org a biller belongs to sees the bill payments it collects, tagged PAYEE | - |  |
| 23 | [sp-query] a portal user without an org role may not query | - |  |
| 05 | [topup] a successful top-up debits the payer once and leaves nothing held | 1 | TOPUP app success |
| 05 | [topup] an operator refusal releases the hold and moves no money | 1 | TOPUP app failure |
| 05 | [topup] a wrong PIN is refused by the core before anything is held | 1 | TOPUP app failure |
| 05 | [topup] a hold whose reply is lost is found by a status query and released | 1 |  |
| 05 | [topup] the same idempotency key sent twice tops up once and returns the same txnId | 2 | TOPUP app idempotent |
| 05 | [topup] a top-up without operator is refused by the app API | 1 |  |
| 05 | [topup tsq] an OCS timeout is resolved by status inquiry and the hold is committed | 1 | TOPUP app tsq-commit |
| 05 | [topup tsq] an OCS timeout the top-up did not survive is resolved by status inquiry and released | 1 | TOPUP app tsq-release |
| 05 | [topup tsq] a top-up resolved by status inquiry records UNKNOWN in its audit trail | 1 |  |
| 05 | [topup] a completed top-up leaves an audit trail of the hold path | 1 |  |
| 19 | [ussd topup] a top-up of the own number over USSD in English completes | 1, 5 | TOPUP ussd success; ussd http |
| 19 | [ussd topup] a top-up of another number over USSD in Bangla completes | 1, 5 | TOPUP ussd success |
| 19 | [ussd topup] an operator refusal over USSD moves no money | 1, 5 |  |
| 19 | [ussd billpay] a bill pay over USSD in English fetches the bill and pays it | 1, 5 | BILL_PAY ussd success |
| 19 | [ussd billpay] a bill pay over USSD in Bangla completes | 1, 5 | BILL_PAY ussd success |
| 19 | [ussd billpay] an unknown bill number is refused and asked again, and no money moves | 1, 5 |  |
| 19 | [ussd cash] a customer cash-out to an agent over USSD completes | 1, 5 | CASH_OUT ussd success |
| 20 | [ussd smpp p2p] a P2P over SMPP USSD walks the same menu and moves the money once | 1, 5 | P2P ussd success; ussd smpp |
| 20 | [ussd smpp] a bad number is asked again over SMPP and no money moves | 1, 5 | ussd smpp |
| 20 | [ussd smpp] the binds are kept alive with enquire_link | 5 | ussd smpp |
| 20 | [ussd smpp] after the gateway drops the binds mw-ussd reconnects and USSD works again | 5 | ussd smpp |
| 20 | [ussd smpp] throttling, queue full and generic_nack are retried after a delay | 5 | ussd smpp |
| 18 | [ussd p2p] a P2P over USSD in English completes and moves the money once | 1, 5 | P2P ussd success; ussd http |
| 18 | [ussd p2p] a P2P over USSD in Bangla completes and moves the money once | 1, 5 | P2P ussd success; ussd http |
| 18 | [ussd] a bad number is refused and asked again, and no money moves | 1, 5 | ussd http |
| 18 | [ussd] a wrong PIN is refused by the core and no money moves | 1, 5 | ussd http |
| 16 | [admin wallet-unlock] five wrong PINs lock the wallet, an approved unlock lets the next correct-PIN P2P succeed | - |  |
