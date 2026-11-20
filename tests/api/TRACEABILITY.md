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
The FRS matrix at the bottom (ticket 104) has one row per use case UC-01..UC-22 and a column per
mandatory scenario T1..T12; a cell is a request name from this collection, or `n/a` plus a reason.
A name the collection does not have fails the check.

Requirement tags are the Cucumber flow tags without `@` (`p2p`, `cash`, `topup`, `billpay`,
`callback`, `addmoney`, `c2w`, `merchant`, `checkout`, `ipn`, `notify`, `tsq`, `device`, `reversal`,
`admin`, `operator`, `partner-config`, `wallet-unlock`, `ussd`, `smpp`, `bulk`, `sp-query`, `dlq` (retry topics to the DLQ, ticket 58, no Cucumber flow), plus
`health` and `seed` for folder 00, `auth` (mw-auth's login and tokens, ticket 69), `edge` (an APISIX edge policy proved through the edge), `frs` (a mandatory FRS 10.2 scenario, ticket 104), and since ticket 76 `pin` (the PIN lifecycle: policy, change, resets), `lock` (the PIN lockout and its unlock), `assertion` (the transaction authorisation assertion of every channel) and `crypto` (the HSM gateway mw-crypto), `history` (the wallet transaction history GET /app/v1/transactions, ADR D25), `balance` (the balance check over USSD and POST /app/v1/balance, ADR D26) and `docs` (the generated API documents of the dev stack under /docs/, ticket 101), `mock` (the mock-control API of mw-simulators, /test/mock-control/*, ADR D33, tickets 102a/102b, and its global reset, ticket 103), `audit` (mw-audit-dw's search, per-hop timeline and audited CSV export, ticket 130), `credentials` (partner credential upload into Vault and the expiry dashboard of the admin API, ticket 131a)); the module tags (`@mw-*`) were run filters, not requirements, and
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

Requirements (ticket 76): the optional fifth column names the spec requirement ids a request proves
(spec "Requirements traceability": `FR-01` gateway and auth, `FR-05` idempotency, `FR-18` audit trail,
`FR-19`/`FR-20` maker-checker, `SEC-04`/`SEC-05` encryption, keys and the HSM gateway, `SEC-09` RBAC,
MFA and wallet authentication (18.7), `SEC-10` masking). A request tagged `auth`, `pin`, `lock`,
`assertion` or `crypto` must name at least one; `scripts/check-api-traceability.sh` checks the ids'
form (`FR-nn`, `SEC-nn`, `NFR-nn`).

## Requests

| Folder | Request | Criteria | Covers | Requirements |
| --- | --- | --- | --- | --- |
| 00 | [health seed] the seeded personas sign in and the seeded wallets exist | - | |
| 00 | [health] the edge and every test-only route answer | - | |
| 00 | [health] the money path is warm: a P2P per app user completes | - | |
| 08 | [addmoney c2w] a confirmed debit credits the wallet once and holds nothing | 1 | ADD_MONEY_BANK app success |
| 08 | [addmoney c2w callback] a replayed bank callback has no second effect | 1, 2 |  |
| 08 | [addmoney c2w callback] a callback the bank sends twice credits the wallet once | 1, 2 |  |
| 08 | [addmoney c2w] a debit the bank refuses fails and moves nothing | 1 | ADD_MONEY_BANK app failure |
| 08 | [addmoney c2w] a wrong PIN is refused by mw-auth and the bank is never asked | 1 | ADD_MONEY_BANK app failure |
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
| 06 | [billpay] a bill of a SOAP biller (KGDCL) is fetched and paid through the aggregator to COMPLETED | 1 |  |
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
| 02 | [device auth] a login on the bound device gets mw-auth tokens and the same signed login again is a replay | - |  | SEC-09, FR-01 |
| 02 | [device auth] a wrong PIN at login is refused | - |  | SEC-09 |
| 02 | [device auth] a refresh rotates the token and the used token again revokes the session | - |  | SEC-09 |
| 02 | [device auth edge] the app API refuses no token and serves an mw-auth token; the JWK Set is public | - |  | FR-01, SEC-09 |
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
| 46 | [p2p] a preview shows the fee, VAT, commission and the masked payee, and confirm debits that fee | 1 | P2P app success |
| 46 | [p2p] a preview alone moves no money | - | |
| 46 | [p2p] a confirm whose amount differs from the preview is refused | - | |
| 46 | [p2p] an amount over the per-transaction limit is refused at preview and moves nothing | 1 | P2P app failure |
| 48 | [p2p] a SOAP preview and confirm complete a P2P and debit the previewed fee once | 1 | P2P app success |
| 48 | [p2p] a SOAP confirm and a REST confirm with the same idempotency key move the money once | 2 | P2P app idempotent |
| 48 | [p2p] a SOAP request that is not the schema is MW-VAL-001 and does not echo the MSISDN | - | |
| 48 | [docs] the app SOAP WSDL is listed on the dev docs page | - | |
| 49 | [billpay] the app biller list comes from the catalogue and shows its MINIMUM and OPEN billers | - | |
| 49 | [billpay] a FIXED biller refuses an amount other than the bill the app fetched before any hold | 1 | BILL_PAY app failure |
| 49 | [billpay] a MINIMUM biller refuses an amount below its minimum before any hold | 1 | BILL_PAY app failure |
| 49 | [billpay] an OPEN biller refuses an amount below its range before any hold | 1 | BILL_PAY app failure |
| 50 | [checkout frs] T1 two partial refunds return the money to the payer and each sends a signed refund IPN | - | |
| 50 | [checkout frs] T2 a refund past the payment is refused with MW-CORE-REFUND_EXCEEDS and moves nothing | - | |
| 50 | [checkout frs] T6 the same Idempotency-Key twice refunds once and another body is MW-IDEM-001 | 2 | |
| 50 | [checkout frs] T9 a session that was never paid is not refunded and nothing moves | - | |
| 50 | [checkout frs] T10 a refund keeps the ledger identity: what the merchant gives back the payer gets | - | |
| 01 | [p2p] a P2P completes and moves the money once | 1 | P2P app success |
| 01 | [p2p] a wrong PIN is refused by mw-auth at once, nothing published, no money moves | 1 | P2P app failure |
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
| 05 | [topup] a wrong PIN is refused by mw-auth before anything is published or held | 1 | TOPUP app failure |
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
| 18 | [ussd] a wrong PIN is refused by mw-auth and asked again, the right one then pays once | 1, 5 | ussd http |
| 18 | [ussd] a dial with another MNO's code ends at once with the wrong-code screen, and no money moves | 1, 5 | ussd http |
| 16 | [admin wallet-unlock] three wrong PINs lock the PIN at mw-auth, an approved unlock lets the next correct-PIN P2P succeed | - |  |
| 24 | [auth] an OTP for an unknown MSISDN is refused; a known wallet gets a masked answer and the SMS | - |  | SEC-09, SEC-10 |
| 24 | [auth] a wallet without a PIN logs in with a LOGIN OTP, sets its first PIN and gets a PIN session | - |  | SEC-09, FR-01 |
| 24 | [auth device] a wrong binding OTP is refused, the right one binds the device and a new-device SMS follows | - |  | SEC-09 |
| 24 | [auth device] binding a second device unbinds the first: its login and refresh are refused, the new one logs in | - |  | SEC-09, FR-01 |
| 24 | [auth] logout ends the session: the refresh token is revoked and the app API refuses the token | - |  | SEC-09, FR-01 |
| 24 | [auth edge] the key documents are public: access-token JWKS RS256, assertion JWKS ES256, the PIN transport key | - |  | SEC-04, FR-01 |
| 25 | [auth pin] a weak PIN is refused naming the rule; a good one is set | - |  | SEC-09, SEC-04 |
| 25 | [auth pin] a PIN change needs the current PIN, refuses the same PIN again and replaces the session | - |  | SEC-09 |
| 25 | [auth pin] a self-service reset is verified by the core, sets a new PIN and ends every session | - |  | SEC-09 |
| 25 | [auth pin admin] a care PIN reset needs a second operator; the customer then sets a new PIN on a new phone | - |  | SEC-09, FR-20 |
| 25 | [auth pin lock admin] three wrong PINs lock the PIN, the right one is not even checked, an approved ops unlock opens it | - |  | SEC-09, FR-20 |
| 26 | [assertion p2p] a wrong PIN is refused by mw-auth before the saga: the orchestrator never sees the request | - |  | SEC-09, FR-04 |
| 26 | [assertion p2p] a resend with the same idempotency key inside the assertion life reuses it: same txnId, money moves once | - |  | SEC-09, FR-05 |
| 26 | [assertion ussd lock] USSD asks mw-auth for the assertion: wrong PINs are asked again, the third locks the PIN and ends the session | - |  | SEC-09, FR-10 |
| 26 | [assertion checkout] a wrong PIN at checkout is refused by mw-auth and the session stays open; a new OTP and the right PIN pay once | - |  | SEC-09, FR-02 |
| 27 | [crypto] a PIN block under the TPK gets a PVV from the payShield; it verifies the PIN and refuses another | - |  | SEC-04, SEC-05 |
| 27 | [crypto] the test-only client is bound to the TPK and PVK only: a ZPK block is refused, the PVK is never served | - |  | SEC-04, SEC-09 |
| 28 | [history p2p] a sent transfer is OUT in the payer's history and IN in the payee's | - |  | SEC-10 |
| 28 | [history cash] an agent cash-in is IN in the customer's history and OUT in the agent's | - |  |
| 28 | [history merchant] a merchant payment is IN in the merchant's history | - |  |
| 28 | [history] the history filters by direction, pages with hasMore and refuses bad parameters | - |  |
| 28 | [history] the signed-in wallet saves a biller account and another wallet cannot see it | - |  |
| 28 | [history] the signed-in wallet saves a favourite and another wallet cannot delete it | - |  |
| 28 | [history] a statement longer than 90 days is refused and a short range lists the transfer | - |  | SEC-10 |
| 29 | [balance ussd] option 5 asks for the PIN and shows the available balance in English; no money moves | - |  | FR-10 |
| 29 | [balance ussd] the balance check in Bangla shows the same figure | - |  | FR-10 |
| 29 | [balance assertion ussd] a wrong PIN on the balance check is asked again and shows no balance | - |  | SEC-09, FR-10 |
| 29 | [balance] the app balance check answers the core's available balance and moves no money | - |  |
| 29 | [balance assertion] a wrong PIN on the app balance check is refused by mw-auth with MW-AUTH-040 | - |  | SEC-09 |
| 29 | [balance] a core refusal of the balance check is 422 MW-CORE-WALLET_NOT_ACTIVE | - |  |
| 35 | [topup] a top-up with no partnerId is routed from the 017 prefix and completes | - |  |
| 35 | [topup admin] an MNP override to an unknown operator refuses the number with MW-PTR-UNKNOWN | - |  |
| 35 | [topup mock] ocs switched DOWN fails the top-up at once and is switched back UP | - |  |
| 35 | [billpay admin] a maintenance message disables DESCO with the Bangla and English text | - |  |
| 29 | [balance history] a balance check is not in the wallet history | - |  | SEC-10 |
| 29 | [notify] the signed-in wallet reads its notification inbox and cannot mark another id read | - |  |
| 29 | [balance] the signed-in wallet's profile shows its name, status, KYC level and Bangla | - |  |
| 29 | [balance] the signed-in wallet's limits are what it may still pay | - |  |
| 36 | [p2p] the same payment under a new key within the window asks to confirm (MW-VAL-024); the confirmed resend completes | - |  | FR-05 |
| 36 | [p2p] another amount within the window is no duplicate | - |  | FR-05 |
| 36 | [p2p reversal] a key answered 200 replays that very body after a reversal; other content is 409 MW-IDEM-001 | - |  | FR-05 |
| 36 | [checkout edge] a signed session create replayed with its nonce is refused (MW-AUTH-006) | - |  |
| 36 | [checkout edge] a signed session create dated 6 minutes ago is refused | - |  |
| 36 | [callback edge] a partner callback replayed with its nonce or a stale timestamp is refused | - |  |
| 38 | [docs] /docs/ lists the Swagger UI and the OpenAPI document of every service | - |  |
| 38 | [docs] every listed service serves its OpenAPI 3.1 document and its Swagger UI under /docs/<service>/ | - |  |
| 38 | [docs] /docs/<service>/ reaches nothing of the service but its document and UI | - |  |
| 39 | [mock] every mock party is listed with its operations, keys and behaviours | - |  |
| 39 | [mock] a scenario the party cannot carry out is refused and an unknown party is 404 | - |  |
| 39 | [mock topup] a key-scoped BUSINESS_ERROR on ocs refuses that number only; the magic values decide the rest | - |  |
| 39 | [mock p2p notify smpp] the smsc journal records the SMS submits; a NO_CALLBACK scenario leaves out that DLR | - |  |
| 39 | [mock ussd smpp] a ussdgw scenario refuses the next screen with ESME_RTHROTTLED and mw-ussd retries it | - |  |
| 40 | [mock] one call resets every mock party and brings the core ledger back to its seed | - |  |
| 40 | [mock] a fault set through the old core fault API is a core scenario the reset clears | - |  |
| 40 | [mock] an unknown core seed version is reported FAILED and changes nothing else | - |  |
| 41 | [p2p frs] T1 a P2P completes with the seed tariff fee, VAT and commission and the SMS is in the SMSC journal | - |  |
| 41 | [p2p frs] T2 a core business rejection shows the English and Bangla text and moves nothing | - |  |
| 41 | [p2p frs] T3 a core timeout on the next transfer is completed by the status inquiry and debited once | - |  |
| 41 | [p2p frs] T4 a lost core refusal is failed by the status inquiry and moves nothing | - |  |
| 41 | [p2p frs] T6 the same Idempotency-Key twice debits once and returns the same txnId | - |  |
| 41 | [p2p frs] T7 a duplicate core result event posts the transfer once | - |  |
| 41 | [p2p frs] T8 a core that refuses connections fails the preview at once and moves nothing | - |  |
| 41 | [p2p frs] T9 a limit is refused before any transfer and an insufficient balance moves nothing | - |  |
| 41 | [p2p frs] T10 a P2P keeps the ledger identity; mid-saga restart is scripts/chaos.sh | - |  |
| 42 | [audit topup] a search by partner and amount range finds the top-up and its partner call | - |  | FR-18 |
| 42 | [audit p2p] the timeline of a P2P lists its hops in order with latencies and no clear MSISDN | - |  | FR-18, SEC-10 |
| 42 | [audit] a CSV export of a transaction returns its rows and records one operator action | - |  | FR-18 |
| 43 | [p2p notify] the sender gets a push with the transaction text next to the SMS | - |  |
| 43 | [p2p notify mock] a push server that answers 5xx is retried and stores nothing; the SMS for the same transaction still goes | - |  |
| 43 | [p2p notify mock] a token the push server does not know gets no push and the SMS still goes | - |  |
| 43 | [notify] a push token with an unknown platform is refused 400 MW-VAL-001 | - |  |
| 44 | [mock] the trust bank writes an MT940 on request and the journal lists it with its checksum | - |  |
| 44 | [mock] a MISSING_FILE scenario skips the next statement only and an injected mismatch reaches the one after | - |  |
| 44 | [mock] the AML mock answers an uploaded transaction file with HOLD and BLOCK lines | - |  |
| 44 | [mock] the DW drop check rejects a control file with a wrong row count | - |  |
| 45 | [admin partner-config credentials] an uploaded partner credential is approved by another checker into Vault and only its metadata is ever shown | - |  | FR-19, SEC-04 |
| 45 | [admin credentials] the expiry dashboard lists a credential expiring within 30 days, soonest first | - |  |
| 45 | [admin partner-config] an approved response-code map is published as the partner's next version | - |  | FR-19 |
| 47 | [notify] an opted-out customer still gets the P2P SMS | - |  |
| 47 | [admin notify] an approved campaign change request is APPROVED | - |  |
| 60 | [mock] an injected trust-bank line is missing in Upay | - | |
| 62 | [mock] today's data warehouse export is accepted by the drop check | - |  |
| 90 | [auth pin lock] the lockout escalates: level 1, level 2 after it ran out, then a permanent lock that only a reset ends | - |  | SEC-09 |
| 90 | [assertion p2p] after the assertion life (60 s) a resend with the same key checks the PIN again; the right PIN gets the same txnId | - |  | SEC-09, FR-05 |
| 90 | [p2p frs] T5 a core that never answers ends in MANUAL_REVIEW and alerts operations | - |  |
| 43 | [ussd frs] a payment to a merchant over USSD shows a free fee and moves the money once | - |  |
| 43 | [ussd frs] add money from a bank account over USSD credits the wallet once | - |  |
| 43 | [ussd frs] my account shows a mini statement and does not move money | - |  |
| 43 | [ussd frs] change PIN on USSD stays on the maintenance text and moves nothing | - |  |
| 43 | [ussd frs] a remittance reference that is not an Upay txn id says there is none | - |  |
| 30 | [topup reversal frs] a completed top-up is reversed at the operator before the core and every balance is restored | - |  | FR-07 |
| 30 | [topup reversal frs] a partner refusal to reverse a completed top-up ends in manual review and keeps the posting | - |  | FR-07 |
| 63 | [b2b] a distributor float transfer to an agent completes | - |  |
| 63 | [b2b] a customer cannot start a float transfer | - |  |
| 63 | [b2b] a partner app cannot start a float transfer | - |  |
| 63 | [b2b] a DSO preview of a float transfer is allowed and moves nothing | - |  |
| 46 | [topup frs] T1 a GP top-up on gp-ocs completes with the seed tariff fee, VAT and commission and the SMS is sent | 1 | TOPUP app success |
| 46 | [topup frs] T1 a Robi top-up on robi-ocs completes and debits the payer once | 1 | TOPUP app success |
| 46 | [topup frs] T2 a GP subscriber-barred refusal shows the English and Bangla text and moves nothing | 1 | TOPUP app failure |
| 46 | [topup frs] T2 a Robi barred refusal shows the English and Bangla text and moves nothing | 1 | TOPUP app failure |
| 46 | [topup tsq frs] T3 a GP top-up that the OCS answers unknown is completed by a driven status inquiry and debited once | 1 | TOPUP app tsq-commit |
| 46 | [topup tsq frs] T4 a GP top-up the status inquiry says failed is released and moves nothing | 1 | TOPUP app tsq-release |
| 46 | [topup frs] T6 the same Idempotency-Key twice tops up once and returns the same txnId | 2 | TOPUP app idempotent |
| 46 | [topup frs] T7 a duplicate core commit of a top-up posts the money once | 1 | TOPUP app success |
| 46 | [topup frs] T8 a GP top-up uses the ocs fallback when gp-ocs is DOWN | 1 | TOPUP app success |
| 46 | [topup frs] T9 a limit is refused before any recharge and an insufficient balance moves nothing | 1 | TOPUP app failure |
| 46 | [topup frs] T10 a top-up keeps the ledger identity; mid-saga restart is scripts/chaos.sh | - |  |
| 46 | [topup reversal frs] a second reverse of a completed gp-ocs recharge returns the first outcome | - |  | FR-07 |

## FRS UC x T1-T12

Ticket 104. P2P (UC-04) is filled in; T5 is the folder 90 request. UC-08 top-up (ticket 112c) is
folder `46-topup-operators`; its T5 waits out the inquiry schedule, so that cell stays `n/a`.
UC-12 merchant refund (ticket 116) is folder `50-checkout-refund`; a refund calls the core only, so the
external-partner columns T3/T4/T5/T7/T8 are `n/a`. T11 and T12 stay `n/a` until tickets 125 and 127. T10 names the request that checks the ledger
identity; the mid-saga kill is `scripts/chaos.sh` with `CHAOS_TARGETS=mw-orchestrator`. A use
case still waiting on its ticket stays `n/a`.

| UC | T1 | T2 | T3 | T4 | T5 | T6 | T7 | T8 | T9 | T10 | T11 | T12 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| UC-01 | n/a ticket 124 | n/a ticket 124 | n/a ticket 124 | n/a ticket 124 | n/a ticket 124 | n/a ticket 124 | n/a ticket 124 | n/a ticket 124 | n/a ticket 124 | n/a ticket 124 | n/a ticket 125 | n/a ticket 127 |
| UC-02 | n/a not a financial transfer | n/a not a financial transfer | n/a not a financial transfer | n/a not a financial transfer | n/a not a financial transfer | n/a not a financial transfer | n/a not a financial transfer | n/a not a financial transfer | n/a not a financial transfer | n/a not a financial transfer | n/a ticket 125 | n/a ticket 127 |
| UC-03 | n/a not a financial transfer | n/a not a financial transfer | n/a not a financial transfer | n/a not a financial transfer | n/a not a financial transfer | n/a not a financial transfer | n/a not a financial transfer | n/a not a financial transfer | n/a not a financial transfer | n/a not a financial transfer | n/a ticket 125 | n/a ticket 127 |
| UC-04 | [p2p frs] T1 a P2P completes with the seed tariff fee, VAT and commission and the SMS is in the SMSC journal | [p2p frs] T2 a core business rejection shows the English and Bangla text and moves nothing | [p2p frs] T3 a core timeout on the next transfer is completed by the status inquiry and debited once | [p2p frs] T4 a lost core refusal is failed by the status inquiry and moves nothing | [p2p frs] T5 a core that never answers ends in MANUAL_REVIEW and alerts operations | [p2p frs] T6 the same Idempotency-Key twice debits once and returns the same txnId | [p2p frs] T7 a duplicate core result event posts the transfer once | [p2p frs] T8 a core that refuses connections fails the preview at once and moves nothing | [p2p frs] T9 a limit is refused before any transfer and an insufficient balance moves nothing | [p2p frs] T10 a P2P keeps the ledger identity; mid-saga restart is scripts/chaos.sh | n/a ticket 125 | n/a ticket 127 |
| UC-05 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 125 | n/a ticket 127 |
| UC-06 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 125 | n/a ticket 127 |
| UC-07 | [b2b] a distributor float transfer to an agent completes | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 125 | n/a ticket 127 |
| UC-08 | [topup frs] T1 a GP top-up on gp-ocs completes with the seed tariff fee, VAT and commission and the SMS is sent | [topup frs] T2 a GP subscriber-barred refusal shows the English and Bangla text and moves nothing | [topup tsq frs] T3 a GP top-up that the OCS answers unknown is completed by a driven status inquiry and debited once | [topup tsq frs] T4 a GP top-up the status inquiry says failed is released and moves nothing | n/a waits out the inquiry schedule | [topup frs] T6 the same Idempotency-Key twice tops up once and returns the same txnId | [topup frs] T7 a duplicate core commit of a top-up posts the money once | [topup frs] T8 a GP top-up uses the ocs fallback when gp-ocs is DOWN | [topup frs] T9 a limit is refused before any recharge and an insufficient balance moves nothing | [topup frs] T10 a top-up keeps the ledger identity; mid-saga restart is scripts/chaos.sh | n/a ticket 125 | n/a ticket 127 |
| UC-09 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 125 | n/a ticket 127 |
| UC-10 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 125 | n/a ticket 127 |
| UC-11 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 125 | n/a ticket 127 |
| UC-12 | [checkout frs] T1 two partial refunds return the money to the payer and each sends a signed refund IPN | [checkout frs] T2 a refund past the payment is refused with MW-CORE-REFUND_EXCEEDS and moves nothing | n/a no external partner (core only; ticket 116) | n/a no external partner (core only; ticket 116) | n/a no external partner (core only; ticket 116) | [checkout frs] T6 the same Idempotency-Key twice refunds once and another body is MW-IDEM-001 | n/a no external partner (core only; ticket 116) | n/a no external partner (core only; ticket 116) | [checkout frs] T9 a session that was never paid is not refunded and nothing moves | [checkout frs] T10 a refund keeps the ledger identity: what the merchant gives back the payer gets | n/a ticket 125 | n/a ticket 127 |
| UC-13 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 125 | n/a ticket 127 |
| UC-14 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 125 | n/a ticket 127 |
| UC-15 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 125 | n/a ticket 127 |
| UC-16 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 125 | n/a ticket 127 |
| UC-17 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 125 | n/a ticket 127 |
| UC-18 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 125 | n/a ticket 127 |
| UC-19 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 125 | n/a ticket 127 |
| UC-20 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 137 | n/a ticket 125 | n/a ticket 127 |
| UC-21 | n/a ticket 125 | n/a ticket 125 | n/a ticket 125 | n/a ticket 125 | n/a ticket 125 | n/a ticket 125 | n/a ticket 125 | n/a ticket 125 | n/a ticket 125 | n/a ticket 125 | n/a ticket 125 | n/a ticket 127 |
| UC-22 | n/a ticket 127 | n/a ticket 127 | n/a ticket 127 | n/a ticket 127 | n/a ticket 127 | n/a ticket 127 | n/a ticket 127 | n/a ticket 127 | n/a ticket 127 | n/a ticket 127 | n/a ticket 125 | n/a ticket 127 |
