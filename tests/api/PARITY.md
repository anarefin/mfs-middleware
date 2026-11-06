# Parity: Cucumber suite (tests/e2e) -> Newman API suite (tests/api)

Written before tests/e2e was deleted (ticket 58). Every Cucumber scenario (each Scenario Outline
example row counted on its own) is mapped to one Newman request, or would be listed as dropped with
its reason. Scenarios: 132 `Scenario` / `Scenario Outline` blocks in 23 features; the 4 outlines have
2, 2, 3 and 2 example rows, so 137 rows below. Mapped: 137. Dropped: 0 (one step dropped, see the
notes). Request names are the scenario names with their requirement tags in front.

Changes that apply to every row:
- audit trail of a transaction (`the audit trail of the transaction records ...`, partner call and
  callback rows): read from the admin API's transaction detail `auditTrail`
  (`GET /admin/v1/transactions/{txnId}`, as an operator, ops-e2e-checker01), which mw-admin-api reads
  from mw-audit-dw; Cucumber read `audit.event` with psql;
- `@After` hooks (core faults, simulator scenarios, IPN receiver, SMPP scenario, partner restore)
  are clean-up functions every request runs even when a check fails (`S.after` in lib.js);
- no request needs Docker: everything goes through APISIX (real routes, plus the test-only routes
  of `deploy/apisix/routes-test`, applied by run.sh).

| Feature | Scenario | Folder | Newman request | Status | Notes |
| --- | --- | --- | --- | --- | --- |
| add-money-bank.feature | a confirmed debit credits the wallet once and holds nothing | 08 | [addmoney c2w] a confirmed debit credits the wallet once and holds nothing | mapped |  |
| add-money-bank.feature | a replayed bank callback has no second effect | 08 | [addmoney c2w callback] a replayed bank callback has no second effect | mapped |  |
| add-money-bank.feature | a callback the bank sends twice credits the wallet once | 08 | [addmoney c2w callback] a callback the bank sends twice credits the wallet once | mapped |  |
| add-money-bank.feature | a debit the bank refuses fails and moves nothing | 08 | [addmoney c2w] a debit the bank refuses fails and moves nothing | mapped |  |
| add-money-bank.feature | a wrong PIN is refused by the core and the bank is never asked | 08 | [addmoney c2w] a wrong PIN is refused by the core and the bank is never asked | mapped |  |
| add-money-bank.feature | a bank timeout is resolved by status inquiry and the wallet is credited | 08 | [addmoney c2w tsq] a bank timeout is resolved by status inquiry and the wallet is credited | mapped |  |
| add-money-bank.feature | an add-money request sent twice with the same idempotency key credits the wallet once | 08 | [addmoney c2w] an add-money request sent twice with the same idempotency key credits the wallet once | mapped |  |
| add-money-bank.feature | a bank timeout the debit did not survive is resolved by status inquiry and fails | 08 | [addmoney c2w tsq] a bank timeout the debit did not survive is resolved by status inquiry and fails | mapped |  |
| admin-content.feature | an approved menu text change shows in the next USSD session but not in a running one | 17 | [admin] an approved menu text change shows in the next USSD session but not in a running one | mapped |  |
| admin-content.feature | an approved SMS template change is used for the next notification | 17 | [admin p2p] an approved SMS template change is used for the next notification | mapped | the 5 s sleep is replaced by repeating the USSD P2P until its SMS uses the new template (at most 4 tries) |
| admin-maker-checker.feature | a top-up in MANUAL_REVIEW is force-committed after a second operator approves | 13 | [admin topup] a top-up in MANUAL_REVIEW is force-committed after a second operator approves | mapped |  |
| admin-maker-checker.feature | a completed P2P is reversed after a second operator approves | 13 | [admin reversal] a completed P2P is reversed after a second operator approves | mapped |  |
| admin-maker-checker.feature | a rejected reversal changes nothing | 13 | [admin reversal] a rejected reversal changes nothing | mapped |  |
| billpay-callback.feature | a callback-only payment is completed by the aggregator's callback | 07 | [billpay callback] a callback-only payment is completed by the aggregator's callback | mapped |  |
| billpay-callback.feature | a refusal reported by callback releases the hold | 07 | [billpay callback] a refusal reported by callback releases the hold | mapped |  |
| billpay-callback.feature | a duplicate callback has no second effect | 07 | [billpay callback] a duplicate callback has no second effect | mapped |  |
| billpay-callback.feature | a stale PENDING callback after the outcome changes nothing | 07 | [billpay callback] a stale PENDING callback after the outcome changes nothing | mapped |  |
| billpay-callback.feature | a late callback after the status inquiry resolved the payment is recorded and changes nothing | 07 | [billpay callback tsq] a late callback after the status inquiry resolved the payment is recorded and changes nothing | mapped | callback 20 s late instead of 90 s when the seeded partner limit is the TIMERS=fast one (still after the status inquiry resolved the payment); 90 s otherwise |
| billpay-callback.feature | a late callback contradicting the resolution is recorded as a conflict and changes nothing | 07 | [billpay callback] a late callback contradicting the resolution is recorded as a conflict and changes nothing | mapped |  |
| billpay-callback.feature | a callback with a bad signature is rejected and changes nothing | 07 | [billpay callback] a callback with a bad signature is rejected and changes nothing | mapped |  |
| billpay.feature | the customer sees the billers and fetches the amount due on a bill | 06 | [billpay] the customer sees the billers and fetches the amount due on a bill | mapped |  |
| billpay.feature | an unknown bill number is a mapped aggregator error | 06 | [billpay] an unknown bill number is a mapped aggregator error | mapped |  |
| billpay.feature | an unknown biller is not found | 06 | [billpay] an unknown biller is not found | mapped |  |
| billpay.feature | a paid bill debits the payer once and leaves nothing held | 06 | [billpay] a paid bill debits the payer once and leaves nothing held | mapped |  |
| billpay.feature | a biller refusal releases the hold and moves no money | 06 | [billpay] a biller refusal releases the hold and moves no money | mapped |  |
| billpay.feature | a bill payment without a biller is refused by the app API | 06 | [billpay] a bill payment without a biller is refused by the app API | mapped |  |
| billpay.feature | an aggregator timeout is resolved by status inquiry and the hold is committed | 06 | [billpay tsq] an aggregator timeout is resolved by status inquiry and the hold is committed | mapped |  |
| billpay.feature | a paid bill leaves an audit trail of the hold path | 06 | [billpay] a paid bill leaves an audit trail of the hold path | mapped |  |
| billpay.feature | a bill payment sent twice with the same idempotency key pays the bill once | 06 | [billpay] a bill payment sent twice with the same idempotency key pays the bill once | mapped |  |
| billpay.feature | an aggregator timeout the payment did not survive is resolved by status inquiry and released | 06 | [billpay tsq] an aggregator timeout the payment did not survive is resolved by status inquiry and released | mapped |  |
| bulk-execution.feature | a 10,000-row batch completes, the result file matches and the org balance is correct | 90 | [bulk] a 10,000-row batch completes, the result file matches and the org balance is correct | mapped | moved to folder 90 Slow (run with `ALL=1`): minutes even with TIMERS=fast |
| bulk-execution.feature | a row the core refuses is reported in the result file and costs the org nothing | 22 | [bulk] a row the core refuses is reported in the result file and costs the org nothing | mapped | references unique per run instead of the fixed OK-1 / NOPE-2 |
| bulk-execution.feature | a paused batch sends nothing until it is resumed, then completes | 22 | [bulk] a paused batch sends nothing until it is resumed, then completes | mapped | batch audit as above; keeps the 3 s negative window of "sends no more rows"; 1,000 rows instead of 2,000 (20 s at the 50 rows/s dispatch rate) |
| bulk-upload.feature | a valid file is stored awaiting approval and a different checker approves it | 21 | [bulk] a valid file is stored awaiting approval and a different checker approves it | mapped | batch audit through `GET /test/audit-dw/v1/audit/events?resource=bulk-batch/<id>` (was psql) |
| bulk-upload.feature | a checker rejects a batch | 21 | [bulk] a checker rejects a batch | mapped | batch audit as above |
| bulk-upload.feature | the maker cannot approve their own batch, even with the checker role | 21 | [bulk] the maker cannot approve their own batch, even with the checker role | mapped |  |
| bulk-upload.feature | another org cannot see or approve the batch | 21 | [bulk] another org cannot see or approve the batch | mapped |  |
| bulk-upload.feature | the org lists its batches by any batch status, the M3 ones included | 21 | [bulk] the org lists its batches by any batch status, the M3 ones included | mapped |  |
| bulk-upload.feature | 10,000 rows are accepted | 21 | [bulk] 10,000 rows are accepted | mapped |  |
| bulk-upload.feature | 10,001 rows are rejected | 21 | [bulk] 10,001 rows are rejected | mapped |  |
| bulk-upload.feature | malformed rows are reported per line and nothing is stored | 21 | [bulk] malformed rows are reported per line and nothing is stored | mapped |  |
| cash.feature | an agent cash-in moves the amount from the agent's float to the customer | 03 | [cash] an agent cash-in moves the amount from the agent's float to the customer | mapped |  |
| cash.feature | a customer cash-out pays the agent, the fee goes to the pool | 03 | [cash] a customer cash-out pays the agent, the fee goes to the pool | mapped |  |
| cash.feature | a cash-in the agent's float cannot cover is rejected and moves no money | 03 | [cash] a cash-in the agent's float cannot cover is rejected and moves no money | mapped |  |
| cash.feature | a cash-out to a wallet that is not an agent is refused by the core | 03 | [cash] a cash-out to a wallet that is not an agent is refused by the core | mapped |  |
| cash.feature | the agent's own wallet is never a counterparty | 03 | [cash] the agent's own wallet is never a counterparty | mapped |  |
| cash.feature | <user> may not initiate <type> (row: customer01 may not initiate CASH_IN) | 03 | [cash] customer01 may not initiate CASH_IN | mapped |  |
| cash.feature | <user> may not initiate <type> (row: agent01 may not initiate CASH_OUT) | 03 | [cash] agent01 may not initiate CASH_OUT | mapped |  |
| cash.feature | an agent cash-in sent twice with the same idempotency key moves the money once | 03 | [cash] an agent cash-in sent twice with the same idempotency key moves the money once | mapped |  |
| cash.feature | a customer cash-out sent twice with the same idempotency key moves the money once | 03 | [cash] a customer cash-out sent twice with the same idempotency key moves the money once | mapped |  |
| cash.feature | a cash-in whose core reply is lost is found by QueryTransaction and completes | 03 | [cash tsq] a cash-in whose core reply is lost is found by QueryTransaction and completes | mapped |  |
| cash.feature | a cash-in whose core refusal is lost is found by QueryTransaction and fails | 03 | [cash tsq] a cash-in whose core refusal is lost is found by QueryTransaction and fails | mapped |  |
| cash.feature | a cash-out whose core reply is lost is found by QueryTransaction and completes | 03 | [cash tsq] a cash-out whose core reply is lost is found by QueryTransaction and completes | mapped |  |
| cash.feature | a cash-out whose core refusal is lost is found by QueryTransaction and fails | 03 | [cash tsq] a cash-out whose core refusal is lost is found by QueryTransaction and fails | mapped |  |
| checkout.feature | create session, pay with OTP and PIN, signed redirect the merchant verifies | 09 | [checkout] create session, pay with OTP and PIN, signed redirect the merchant verifies | mapped | step "the OTP was sent with OTP priority" dropped: it read `notify.message` with psql and no API exposes a message's priority (mw-notify's dispatcher tests cover the per-priority binds) |
| checkout.feature | a payment the core rejects fails the session and sends the browser to the fail URL | 09 | [checkout] a payment the core rejects fails the session and sends the browser to the fail URL | mapped |  |
| checkout.feature | the merchant's IPN endpoint fails twice, then the signed IPN is delivered once | 09 | [checkout ipn] the merchant's IPN endpoint fails twice, then the signed IPN is delivered once | mapped | "no further IPN within 15 s" is a 5 s negative window (TIMERS=fast retries after 1 s, 2 s, 3 s) |
| checkout.feature | a second confirmation of a paid session is refused and pays nothing again | 09 | [checkout] a second confirmation of a paid session is refused and pays nothing again | mapped |  |
| checkout.feature | a checkout payment whose core reply is lost is found by QueryTransaction and paid | 09 | [checkout tsq] a checkout payment whose core reply is lost is found by QueryTransaction and paid | mapped |  |
| checkout.feature | a checkout payment whose core refusal is lost is found by QueryTransaction and fails | 09 | [checkout tsq] a checkout payment whose core refusal is lost is found by QueryTransaction and fails | mapped |  |
| checkout.feature | a merchant request with a bad HMAC signature is refused at the edge | 09 | [checkout] a merchant request with a bad HMAC signature is refused at the edge | mapped |  |
| checkout.feature | a merchant request without a signature is refused at the edge | 09 | [checkout] a merchant request without a signature is refused at the edge | mapped |  |
| device-binding.feature | a P2P with a valid signature passes | 02 | [device p2p] a P2P with a valid signature passes | mapped |  |
| device-binding.feature | a replayed signed request is rejected and moves no money again | 02 | [device p2p] a replayed signed request is rejected and moves no money again | mapped |  |
| device-binding.feature | a body changed after signing is rejected | 02 | [device p2p] a body changed after signing is rejected | mapped |  |
| merchant.feature | a customer pays a merchant and the merchant sees the collection | 04 | [merchant] a customer pays a merchant and the merchant sees the collection | mapped |  |
| merchant.feature | a payment the customer's balance cannot cover is rejected and moves no money | 04 | [merchant] a payment the customer's balance cannot cover is rejected and moves no money | mapped |  |
| merchant.feature | a merchant payment to a wallet that is not a merchant is refused by the core | 04 | [merchant] a merchant payment to a wallet that is not a merchant is refused by the core | mapped |  |
| merchant.feature | <user> may not initiate <type> (row: agent01 may not initiate MERCHANT_PAY) | 04 | [merchant] agent01 may not initiate MERCHANT_PAY | mapped |  |
| merchant.feature | <user> may not initiate <type> (row: merchant01 may not initiate MERCHANT_PAY) | 04 | [merchant] merchant01 may not initiate MERCHANT_PAY | mapped |  |
| merchant.feature | <user> may not initiate <type> (row: merchant01 may not initiate P2P) | 04 | [merchant] merchant01 may not initiate P2P | mapped |  |
| merchant.feature | <user> has no collections view (row: customer01 has no collections view) | 04 | [merchant] customer01 has no collections view | mapped |  |
| merchant.feature | <user> has no collections view (row: agent01 has no collections view) | 04 | [merchant] agent01 has no collections view | mapped |  |
| merchant.feature | a merchant payment sent twice with the same idempotency key pays the merchant once | 04 | [merchant] a merchant payment sent twice with the same idempotency key pays the merchant once | mapped |  |
| merchant.feature | a merchant payment whose core reply is lost is found by QueryTransaction and completes | 04 | [merchant tsq] a merchant payment whose core reply is lost is found by QueryTransaction and completes | mapped |  |
| merchant.feature | a merchant payment whose core refusal is lost is found by QueryTransaction and fails | 04 | [merchant tsq] a merchant payment whose core refusal is lost is found by QueryTransaction and fails | mapped |  |
| notify-flows.feature | a P2P the core refuses tells the sender it failed | 11 | [p2p notify] a P2P the core refuses tells the sender it failed | mapped |  |
| notify-flows.feature | a P2P whose core reply is lost tells the sender it is processing, then that it was sent | 11 | [p2p notify tsq] a P2P whose core reply is lost tells the sender it is processing, then that it was sent | mapped |  |
| notify-flows.feature | a cash-in notifies the agent and the customer | 11 | [cash notify] a cash-in notifies the agent and the customer | mapped |  |
| notify-flows.feature | a cash-in the core refuses tells the agent it failed | 11 | [cash notify] a cash-in the core refuses tells the agent it failed | mapped |  |
| notify-flows.feature | a cash-out notifies the customer (with the fee) and the agent | 11 | [cash notify] a cash-out notifies the customer (with the fee) and the agent | mapped |  |
| notify-flows.feature | a cash-out the core refuses tells the customer it failed | 11 | [cash notify] a cash-out the core refuses tells the customer it failed | mapped |  |
| notify-flows.feature | a top-up notifies the payer and the recharged number | 11 | [topup notify] a top-up notifies the payer and the recharged number | mapped |  |
| notify-flows.feature | a top-up the operator refuses tells the payer it failed | 11 | [topup notify] a top-up the operator refuses tells the payer it failed | mapped |  |
| notify-flows.feature | a top-up the operator answers too late tells the payer it is processing, then that it succeeded | 11 | [topup notify tsq] a top-up the operator answers too late tells the payer it is processing, then that it succeeded | mapped |  |
| notify-flows.feature | a bill payment sends an SMS and an email receipt | 11 | [billpay notify] a bill payment sends an SMS and an email receipt | mapped |  |
| notify-flows.feature | a bill payment the aggregator refuses tells the payer it failed, without a receipt | 11 | [billpay notify] a bill payment the aggregator refuses tells the payer it failed, without a receipt | mapped |  |
| notify-flows.feature | a bill payment the aggregator answers too late tells the payer it is processing | 11 | [billpay notify tsq] a bill payment the aggregator answers too late tells the payer it is processing | mapped |  |
| notify-flows.feature | money added from a bank account sends an SMS and an email receipt | 11 | [addmoney c2w notify] money added from a bank account sends an SMS and an email receipt | mapped |  |
| notify-flows.feature | a debit the bank refuses tells the customer it failed | 11 | [addmoney c2w notify] a debit the bank refuses tells the customer it failed | mapped |  |
| notify-flows.feature | a bank that does not answer in time tells the customer it is processing | 11 | [addmoney c2w notify tsq] a bank that does not answer in time tells the customer it is processing | mapped |  |
| notify.feature | sender and receiver get the right SMS text | 10 | [p2p notify] sender and receiver get the right SMS text | mapped |  |
| operator-console.feature | an operator finds a P2P by txnId and by MSISDN and sees its timeline and audit trail | 14 | [admin operator p2p] an operator finds a P2P by txnId and by MSISDN and sees its timeline and audit trail | mapped |  |
| operator-console.feature | an operator finds a P2P by MSISDN and reverses it once a second operator approves | 14 | [admin operator reversal] an operator finds a P2P by MSISDN and reverses it once a second operator approves | mapped |  |
| p2p.feature | a P2P completes and moves the money once | 01 | [p2p] a P2P completes and moves the money once | mapped |  |
| p2p.feature | a core rejection (<reason>) moves no money (row: a core rejection (wrong PIN) moves no money) | 01 | [p2p] a core rejection (wrong PIN) moves no money | mapped |  |
| p2p.feature | a core rejection (<reason>) moves no money (row: a core rejection (over per-txn limit) moves no money) | 01 | [p2p] a core rejection (over per-txn limit) moves no money | mapped |  |
| p2p.feature | the same idempotency key sent twice debits once and returns the same txnId | 01 | [p2p] the same idempotency key sent twice debits once and returns the same txnId | mapped |  |
| p2p.feature | the same idempotency key with a different body is refused | 01 | [p2p] the same idempotency key with a different body is refused | mapped |  |
| p2p.feature | a completed P2P leaves a verifiable audit trail | 01 | [p2p] a completed P2P leaves a verifiable audit trail | mapped | hash chain through `GET /test/audit-dw/v1/audit/chains/{month}` (was psql) |
| p2p.feature | a transfer whose core reply is lost is found by QueryTransaction and completes | 01 | [p2p tsq] a transfer whose core reply is lost is found by QueryTransaction and completes | mapped |  |
| p2p.feature | a transfer whose core refusal is lost is found by QueryTransaction and fails | 01 | [p2p tsq] a transfer whose core refusal is lost is found by QueryTransaction and fails | mapped |  |
| partner-config.feature | a disabled telco partner refuses top-ups, and works again once enabled | 15 | [admin partner-config topup] a disabled telco partner refuses top-ups, and works again once enabled | mapped | the 5 s pause "the partner change has reached the adapters" is replaced by repeating the top-up until it shows the change |
| partner-config.feature | a lowered time limit applies to the next call | 15 | [admin partner-config topup tsq] a lowered time limit applies to the next call | mapped | the 5 s pause is replaced by repeating the top-up until it answers 202; OCS delay and new limit follow the seeded limit (Cucumber 6 s / 3 s, TIMERS=fast 3 s / 1.5 s) |
| reversal.feature | a completed P2P is reversed and every balance is restored | 12 | [reversal] a completed P2P is reversed and every balance is restored | mapped | REVERSAL through the test-only `POST /test/orchestrator/v1/reversals` (was kafka-console-producer); hash chain through `GET /test/audit-dw/v1/audit/chains/{month}` (was psql) |
| reversal.feature | a second reversal of the same transaction moves no money again | 12 | [reversal] a second reversal of the same transaction moves no money again | mapped | REVERSAL as above; "the orchestrator has handled the reversal request" through `GET /test/orchestrator/v1/txn-requests/{key}` (was psql on `txn.idempotency`) |
| reversal.feature | a reversal the core refuses goes to manual review with an audit entry | 12 | [reversal] a reversal the core refuses goes to manual review with an audit entry | mapped | REVERSAL as above |
| sp-query.feature | an org sees its batch children with their timeline, another org sees none of them | 23 | [sp-query bulk] an org sees its batch children with their timeline, another org sees none of them | mapped |  |
| sp-query.feature | the org a biller belongs to sees the bill payments it collects, tagged PAYEE | 23 | [sp-query billpay] the org a biller belongs to sees the bill payments it collects, tagged PAYEE | mapped |  |
| sp-query.feature | a portal user without an org role may not query | 23 | [sp-query] a portal user without an org role may not query | mapped |  |
| topup.feature | a successful top-up debits the payer once and leaves nothing held | 05 | [topup] a successful top-up debits the payer once and leaves nothing held | mapped |  |
| topup.feature | an operator refusal releases the hold and moves no money | 05 | [topup] an operator refusal releases the hold and moves no money | mapped |  |
| topup.feature | a wrong PIN is refused by the core before anything is held | 05 | [topup] a wrong PIN is refused by the core before anything is held | mapped |  |
| topup.feature | a hold whose reply is lost is found by a status query and released | 05 | [topup] a hold whose reply is lost is found by a status query and released | mapped |  |
| topup.feature | the same idempotency key sent twice tops up once and returns the same txnId | 05 | [topup] the same idempotency key sent twice tops up once and returns the same txnId | mapped |  |
| topup.feature | a top-up without operator is refused by the app API | 05 | [topup] a top-up without operator is refused by the app API | mapped |  |
| topup.feature | an OCS timeout is resolved by status inquiry and the hold is committed | 05 | [topup tsq] an OCS timeout is resolved by status inquiry and the hold is committed | mapped |  |
| topup.feature | an OCS timeout the top-up did not survive is resolved by status inquiry and released | 05 | [topup tsq] an OCS timeout the top-up did not survive is resolved by status inquiry and released | mapped |  |
| topup.feature | a top-up resolved by status inquiry records UNKNOWN in its audit trail | 05 | [topup tsq] a top-up resolved by status inquiry records UNKNOWN in its audit trail | mapped |  |
| topup.feature | a completed top-up leaves an audit trail of the hold path | 05 | [topup] a completed top-up leaves an audit trail of the hold path | mapped |  |
| ussd-flows.feature | a top-up of the own number over USSD in English completes | 19 | [ussd topup] a top-up of the own number over USSD in English completes | mapped |  |
| ussd-flows.feature | a top-up of another number over USSD in Bangla completes | 19 | [ussd topup] a top-up of another number over USSD in Bangla completes | mapped |  |
| ussd-flows.feature | an operator refusal over USSD moves no money | 19 | [ussd topup] an operator refusal over USSD moves no money | mapped |  |
| ussd-flows.feature | a bill pay over USSD in English fetches the bill and pays it | 19 | [ussd billpay] a bill pay over USSD in English fetches the bill and pays it | mapped |  |
| ussd-flows.feature | a bill pay over USSD in Bangla completes | 19 | [ussd billpay] a bill pay over USSD in Bangla completes | mapped |  |
| ussd-flows.feature | an unknown bill number is refused and asked again, and no money moves | 19 | [ussd billpay] an unknown bill number is refused and asked again, and no money moves | mapped |  |
| ussd-flows.feature | a customer cash-out to an agent over USSD completes | 19 | [ussd cash] a customer cash-out to an agent over USSD completes | mapped |  |
| ussd-smpp.feature | a P2P over SMPP USSD walks the same menu and moves the money once | 20 | [ussd smpp p2p] a P2P over SMPP USSD walks the same menu and moves the money once | mapped |  |
| ussd-smpp.feature | a bad number is asked again over SMPP and no money moves | 20 | [ussd smpp] a bad number is asked again over SMPP and no money moves | mapped |  |
| ussd-smpp.feature | the binds are kept alive with enquire_link | 20 | [ussd smpp] the binds are kept alive with enquire_link | mapped |  |
| ussd-smpp.feature | after the gateway drops the binds mw-ussd reconnects and USSD works again | 20 | [ussd smpp] after the gateway drops the binds mw-ussd reconnects and USSD works again | mapped |  |
| ussd-smpp.feature | throttling, queue full and generic_nack are retried after a delay | 20 | [ussd smpp] throttling, queue full and generic_nack are retried after a delay | mapped |  |
| ussd.feature | a P2P over USSD in English completes and moves the money once | 18 | [ussd p2p] a P2P over USSD in English completes and moves the money once | mapped |  |
| ussd.feature | a P2P over USSD in Bangla completes and moves the money once | 18 | [ussd p2p] a P2P over USSD in Bangla completes and moves the money once | mapped |  |
| ussd.feature | a bad number is refused and asked again, and no money moves | 18 | [ussd] a bad number is refused and asked again, and no money moves | mapped |  |
| ussd.feature | a wrong PIN is refused by the core and no money moves | 18 | [ussd] a wrong PIN is refused by the core and no money moves | mapped |  |
| wallet-unlock.feature | five wrong PINs lock the wallet, an approved unlock lets the next correct-PIN P2P succeed | 16 | [admin wallet-unlock] five wrong PINs lock the wallet, an approved unlock lets the next correct-PIN P2P succeed | mapped |  |
