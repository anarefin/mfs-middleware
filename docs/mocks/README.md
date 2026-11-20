# Mock trigger rules (FRS MCK-03)

Every partner the middleware talks to has a test double (ADR D33): `services/mw-simulators` for the
partners, `services/mw-core-mock` for the core wallet, GreenMail for email. This page lists, per FRS party
(EXT-01 to EXT-18), the data triggers each mock answers by (amount endings, MSISDN, account and bill number
endings, seeded records), the scenarios it can carry out, its switch and its journal.

How a call is decided (mock control, only in the Spring profile `mock-control`, never in production):

1. the party's switch (`PUT /mock-control/<party>/switch {state UP|DOWN|MAINTENANCE|SLOW, latencyMinMs,
   latencyMaxMs, ratio}`): DOWN closes the connection unanswered, MAINTENANCE answers 503, nothing applied;
2. a scenario (`POST /mock-control/<party>/scenarios {operation?, behaviour, code?, delayMs?, httpStatus?,
   key?, nextN?}`): next-N scoped first, then per key, per operation, party-wide;
3. else the data triggers below (the magic values);
4. else SUCCESS.

Every party records each call: `GET /mock-control/<party>/journal[?path=&contains=&afterSeq=]`; `DELETE` clears
it and resets the party (scenarios, switch, settings, state back to the seed). `POST /mock-control/reset`
resets every party and the core. `GET /mock-control/parties` lists the parties with their operations, keys
and behaviours. Contract: `contracts/api/mw-simulators/mock-control.v1.openapi.yaml`. The test console
(`/tester`, `docs/testing-guide.html`) drives the same API.

The sections between the markers below are generated from mw-simulators' trigger registry
(`services/mw-simulators/src/main/java/mfs/mw/simulators/service/mockcontrol/MockTriggers.java`):
`MockTriggerDocsTest` (in `./gradlew :mw-simulators:test`) fails when the registry and its rendering
`services/mw-simulators/src/main/resources/mock-triggers.md` differ, or when a mock party has no section, and
`./gradlew :scriptTests` fails when this page does not carry that rendering. To change a trigger, change the
simulator and the registry, run the test, copy `services/mw-simulators/build/mock-triggers.md` over the
resource and paste it here.

<!-- mock-triggers:begin -->
<!-- generated from mw-simulators MockTriggers (ticket 136); do not edit by hand -->

## USSD gateways (EXT-01)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `ussdgw` | bind, submit, enquire_link, dial | systemId, msisdn, mno |
| `ussd-gp`, `ussd-robi`, `ussd-bl`, `ussd-teletalk` | bind, submit, enquire_link | systemId, msisdn |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, MALFORMED_RESPONSE, CONNECTION_RESET.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/<party>/journal` (`DELETE` clears it and resets the party).

Data triggers: none; a session is played by `POST /sim/ussdgw/sessions`; faults by scenario only.

## SMSC (EXT-02)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `smsc` | bind, submit | systemId, msisdn |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, CONNECTION_RESET, DUPLICATE_CALLBACK, LATE_CALLBACK, OUT_OF_ORDER_CALLBACK, NO_CALLBACK.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/smsc/journal` (`DELETE` clears it and resets the party).

| Input | Trigger | Outcome |
| --- | --- | --- |
| destination MSISDN | ending `999` | DLR UNDELIV |
| destination MSISDN | ending `998` | DLR EXPIRED |
| destination MSISDN | ending `997` | first two submits refused `0x58` (throttled), then accepted |
| destination MSISDN | ending `996` | submit refused `0x0B` |
| destination MSISDN | ending `995` | accepted, no DLR |
| destination MSISDN | anything else | accepted, DLR DELIVRD after `sim.smsc.dlr-delay` |

## Push server (EXT-04)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `push` | send | token, topic |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, PARTIAL_SUCCESS.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/push/journal` (`DELETE` clears it and resets the party).

| Input | Trigger | Outcome |
| --- | --- | --- |
| token | contains `invalid` | 404 `UNREGISTERED`, not stored |

## AML system (EXT-05)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `aml` | respond | file, date |

Scenarios: MISSING_FILE, LATE_FILE, DUPLICATE_FILE, CORRUPT_FILE, INJECTED_MISMATCH.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/aml/journal` (`DELETE` clears it and resets the party).

| Input | Trigger | Outcome |
| --- | --- | --- |
| amount | above 5,000,000 poisha | ALERT `AMOUNT_THRESHOLD` (payer) |
| WALLET or MSISDN party | on the watch-list (`01711000777`, `01811000777`, `01911000777`) | HOLD `WATCH_LIST` |
| WALLET or MSISDN party | ending `6666` | BLOCK `BLOCK_SUFFIX` (the strongest action wins) |

## Trust bank (EXT-06)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `trustbank` | mt940, mt942 | date, account |

Scenarios: MISSING_FILE, LATE_FILE, DUPLICATE_FILE, CORRUPT_FILE, INJECTED_MISMATCH.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/trustbank/journal` (`DELETE` clears it and resets the party).

Data triggers: none; the statements are built from the mw-core-mock ledger; mismatches by INJECTED_MISMATCH (`MISSING_IN_PARTNER`, `MISSING_IN_UPAY`, `AMOUNT_MISMATCH`, `DC_FLIP`).

## OCS, legacy single profile (EXT-08)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `ocs` | topup, status, reverse | msisdn, txnRef |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, DUPLICATE_CALLBACK, LATE_CALLBACK, OUT_OF_ORDER_CALLBACK, NO_CALLBACK, PARTIAL_SUCCESS.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/ocs/journal` (`DELETE` clears it and resets the party).

| Input | Trigger | Outcome |
| --- | --- | --- |
| MSISDN | ending `999` | 422 `2001` business failure |
| amount | fraction `.13` | timeout: applied, the reply held `sim.timeout-delay` |
| amount | fraction `.77` | HTTP 500 after the effect (applied) |
| amount | fraction `.99` | 422 unknown code `9876` (ambiguous) |
| amount | fraction `.55` | malformed (cut-short) body, applied |

## Operator OCS (GP, Robi, Teletalk, Banglalink) (EXT-08)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `ocs-gp`, `ocs-robi`, `ocs-teletalk`, `ocs-bl` | topup, inquiry, postpaid, status, reverse | msisdn, txnRef |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, DUPLICATE_CALLBACK, LATE_CALLBACK, OUT_OF_ORDER_CALLBACK, NO_CALLBACK, PARTIAL_SUCCESS.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/<party>/journal` (`DELETE` clears it and resets the party).

| Input | Trigger | Outcome |
| --- | --- | --- |
| MSISDN | ending `999` | the operator's barred code |
| postpaid MSISDN | seeded (`01711000021`, `01811000021`, `01511000021`, `01911000021`) | the seeded bill (outstanding, minimum, bill id) |
| postpaid MSISDN | any other | the operator's unknown-bill code |

## Utility aggregator (EXT-09)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `billagg` | billers, fetch, pay, status, reverse | biller, billNumber, txnRef |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, DUPLICATE_CALLBACK, LATE_CALLBACK, OUT_OF_ORDER_CALLBACK, NO_CALLBACK, PARTIAL_SUCCESS.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/billagg/journal` (`DELETE` clears it and resets the party).

| Input | Trigger | Outcome |
| --- | --- | --- |
| bill number | seeded (`1149...`) | its own amount; PAID refused `3002` on fetch and pay |
| bill number | ending `404` | fetch 422 `3001` not found |
| bill number | ending `000` | fetch 422 `3002` already paid |
| biller | unknown, or a SOAP biller on the REST front end | 422 `3004` unknown biller |
| bill number | ending `999` | pay 422 `3003` refused by the biller |
| bill number | anything else | amount due 100 taka + the last three digits |
| amount | fraction `.13` | timeout: applied, the reply held `sim.timeout-delay` |
| amount | fraction `.77` | HTTP 500 after the effect (applied) |
| amount | fraction `.99` | 422 unknown code `9876` (ambiguous) |
| amount | fraction `.55` | malformed (cut-short) body, applied |

## SSLCommerz, eKpay, aggregator 3, aggregator 4 (EXT-10)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `sslcommerz`, `ekpay`, `agg3`, `agg4` | fetch, pay, status, reverse | biller, billNumber, txnRef |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, DUPLICATE_CALLBACK, LATE_CALLBACK, OUT_OF_ORDER_CALLBACK, NO_CALLBACK, PARTIAL_SUCCESS.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/<party>/journal` (`DELETE` clears it and resets the party).

| Input | Trigger | Outcome |
| --- | --- | --- |
| bill | not in the seed, biller known | fetch and pay 422 the profile's code n=1 NOT_FOUND |
| biller | not in the profile's seed | fetch and pay 422 the profile's code n=4 UNKNOWN_BILLER |
| bill | PAID (seeded or paid earlier) | fetch 200 the paid word; pay 422 the profile's code n=2 PAID |
| amount | not the bill's | pay 422 the profile's code n=6 AMOUNT_MISMATCH |
| bill number | ending `999` | pay 422 the profile's code n=3 REFUSED |
| reverse | of a payment not paid | 422 the profile's code n=5 NOT_PAID |
| amount | fraction `.13` | timeout: applied, the reply held `sim.timeout-delay` |
| amount | fraction `.77` | HTTP 500 after the effect (applied) |
| amount | fraction `.99` | 422 unknown code `9876` (ambiguous) |
| amount | fraction `.55` | malformed (cut-short) body, applied |

## Sample REST biller of the connector guide (CON-06)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `samplebiller` | fetch, pay, status, reverse, health | biller, billNumber, txnRef |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, DUPLICATE_CALLBACK, LATE_CALLBACK, OUT_OF_ORDER_CALLBACK, NO_CALLBACK, PARTIAL_SUCCESS.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/samplebiller/journal` (`DELETE` clears it and resets the party).

| Input | Trigger | Outcome |
| --- | --- | --- |
| bill number | seeded (`1360000001`..`1360000005`, biller `SAMPLEWATER`) | its own amount and state |
| bill | not in the seed, biller known | fetch and pay 422 `SB0`1 NOT_FOUND |
| biller | not in the profile's seed | fetch and pay 422 `SB0`4 UNKNOWN_BILLER |
| bill | PAID (seeded or paid earlier) | fetch 200 the paid word; pay 422 `SB0`2 PAID |
| amount | not the bill's | pay 422 `SB0`6 AMOUNT_MISMATCH |
| bill number | ending `999` | pay 422 `SB0`3 REFUSED |
| reverse | of a payment not paid | 422 `SB0`5 NOT_PAID |
| amount | fraction `.13` | timeout: applied, the reply held `sim.timeout-delay` |
| amount | fraction `.77` | HTTP 500 after the effect (applied) |
| amount | fraction `.99` | 422 unknown code `9876` (ambiguous) |
| amount | fraction `.55` | malformed (cut-short) body, applied |

## Merchant IPN receiver (EXT-11)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `merchant` | ipn | ipnId, path |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, DUPLICATE_CALLBACK, LATE_CALLBACK, OUT_OF_ORDER_CALLBACK, NO_CALLBACK, PARTIAL_SUCCESS.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/merchant/journal` (`DELETE` clears it and resets the party).

Data triggers: none; every IPN is answered 200 and recorded; faults by scenario only.

## Merchant system over SOAP (EXT-11)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `merchant-soap` | validate, notify, query, refund | merchantId, txnRef |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, DUPLICATE_CALLBACK, LATE_CALLBACK, OUT_OF_ORDER_CALLBACK, NO_CALLBACK, PARTIAL_SUCCESS.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/merchant-soap/journal` (`DELETE` clears it and resets the party).

| Input | Trigger | Outcome |
| --- | --- | --- |
| merchantId | `M-0001` | active, tills `TILL-01`, `TILL-02` |
| merchantId | `M-0002` | fault `5002` inactive |
| merchantId | `M-0003`, or a till it lacks | fault `5003` (M-0003 has no till) |
| merchantId | any other | fault `5001` unknown merchant |
| refund | above the notified amount | fault `5004` |

## C2W partner bank (EXT-12)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `c2wbank` | debit, status, reverse | account, txnRef |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, DUPLICATE_CALLBACK, LATE_CALLBACK, OUT_OF_ORDER_CALLBACK, NO_CALLBACK, PARTIAL_SUCCESS.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/c2wbank/journal` (`DELETE` clears it and resets the party).

| Input | Trigger | Outcome |
| --- | --- | --- |
| account | ending `999` | FAILED `4002` debit refused, by callback |
| amount | fraction `.13` | timeout: applied, the reply held `sim.timeout-delay` |
| amount | fraction `.77` | HTTP 500 after the effect (applied) |
| amount | fraction `.99` | 422 unknown code `9876` (ambiguous) |
| amount | fraction `.55` | malformed (cut-short) body, applied |

## Bank gateway (one party per bank) (EXT-12)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `bank-dbbl`, `bank-brac`, `bank-city`, `bank-ebl`, `bank-ucb`, `bank-mtb`, `bank-prime`, `bank-pubali`, `bank-dhaka`, `bank-one`, `bank-southeast`, `bank-standard`, `bank-sonali`, `bank-janata`, `bank-agrani`, `bank-rupali`, `bank-ibbl`, `bank-ab`, `bank-premier`, `bank-nrbc` | verify, transfer, status, reverse, health | account, txnRef |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, DUPLICATE_CALLBACK, LATE_CALLBACK, OUT_OF_ORDER_CALLBACK, NO_CALLBACK, PARTIAL_SUCCESS.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/<party>/journal` (`DELETE` clears it and resets the party).

| Input | Trigger | Outcome |
| --- | --- | --- |
| account | ending `00` (not seeded) | 422 `4001` ACCOUNT_NOT_FOUND |
| account | ending `98`, or seeded DORMANT / CLOSED | 422 `4002` ACCOUNT_INACTIVE |
| account | ending `99`, or a balance short of the amount | DEBIT 422 `4003` INSUFFICIENT_FUNDS |
| account | ending `97` | transfer 422 `4004` LIMIT_EXCEEDED |
| account | ending `96` | transfer 422 `4005` TRANSFER_REFUSED |
| account | ending `95` | CREDIT 202 PENDING, SUCCESS by callback and status |
| amount | fraction `.13` | timeout: applied, the reply held `sim.timeout-delay` |
| amount | fraction `.77` | HTTP 500 after the effect (applied) |
| amount | fraction `.99` | 422 unknown code `9876` (ambiguous) |
| amount | fraction `.55` | malformed (cut-short) body, applied |

## NPSB switch (EXT-13)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `npsb` | participants, transfer, status, return, inbound | participant, txnRef |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, DUPLICATE_CALLBACK, LATE_CALLBACK, OUT_OF_ORDER_CALLBACK, NO_CALLBACK, PARTIAL_SUCCESS.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/npsb/journal` (`DELETE` clears it and resets the party).

| Input | Trigger | Outcome |
| --- | --- | --- |
| creditor agent | not in the participant seed, or suspended (`FAKEBDX9`) | RJCT `RC01` |
| creditor account | ending `991` | RJCT `AC01` |
| creditor account | ending `992` | RJCT `AM04` |
| creditor account | ending `993` | PDNG, then ACSC after `sim.npsb.settle-delay` |
| creditor account | ending `994` | ACSC booked, the first answer held `sim.timeout-delay` |
| creditor account | ending `995` | PDNG, then RJCT `AC01` after the settle delay |
| creditor account | anything else | ACSC, `ClrSysRef` `NPSB-CLR-n` |

## CyberSource (EXT-14)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `cybersource` | session, authenticate, capture, status, refund | txnRef, session |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, DUPLICATE_CALLBACK, LATE_CALLBACK, OUT_OF_ORDER_CALLBACK, NO_CALLBACK, PARTIAL_SUCCESS.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/cybersource/journal` (`DELETE` clears it and resets the party).

| Input | Trigger | Outcome |
| --- | --- | --- |
| test card | `4111111111111111` | authenticated, captured |
| test card | `4000000000000002` | declined `4001` |
| test card | `4000000000003063` | 3-D Secure failed `4002` (webhook FAILED, capture refused) |
| test card | `4000000000000119` | captured, the answer held `sim.timeout-delay` |
| test card | any other | declined |

## UCB IMT (EXT-15)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `ucb-imt` | trigger, confirm, status | msisdn, partnerRef |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, DUPLICATE_CALLBACK, LATE_CALLBACK, OUT_OF_ORDER_CALLBACK, NO_CALLBACK, PARTIAL_SUCCESS.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/ucb-imt/journal` (`DELETE` clears it and resets the party).

Data triggers: none; `POST /mock-control/ucb-imt/trigger` pushes one signed credit on demand.

## UCB ATM switch (EXT-16)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `ucbatm` | status | txnRef |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, DUPLICATE_CALLBACK, LATE_CALLBACK, OUT_OF_ORDER_CALLBACK, NO_CALLBACK, PARTIAL_SUCCESS.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/ucbatm/journal` (`DELETE` clears it and resets the party).

Data triggers: none; a known reference is DISPENSED; a BUSINESS_ERROR scenario code `5404` is NOT_FOUND, any other code NOT_DISPENSED.

## Data Warehouse drop (EXT-17)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `dw` | validate | file, date |

Scenarios: SUCCESS, BUSINESS_ERROR.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/dw/journal` (`DELETE` clears it and resets the party).

| Input | Trigger | Outcome |
| --- | --- | --- |
| control file | a listed file missing, its sha256 or row count wrong, or a wrong header | verdict REJECTED with the reasons |
| control file | everything matches | verdict ACCEPTED |

## Bangladesh Election Commission NID (EXT-07)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `ec` | verify | nid |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, DUPLICATE_CALLBACK, LATE_CALLBACK, OUT_OF_ORDER_CALLBACK, NO_CALLBACK, PARTIAL_SUCCESS.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/ec/journal` (`DELETE` clears it and resets the party).

| Input | Trigger | Outcome |
| --- | --- | --- |
| NID | ending `000`, or not in `seed/v1/ec/nid.json` | 404 `NID_NOT_FOUND` |
| NID | ending `999` | timeout: the answer held `sim.timeout-delay` |
| NID | ending `111` | the record with the date of birth one year after the one asked |
| NID | any other seeded one | the record (names, parents, address, photo reference) |

## payShield 10K HSM (spec 18.8)

| Party | Operations | Scope keys |
| --- | --- | --- |
| `payshield` | command | command |

Scenarios: SUCCESS, BUSINESS_ERROR, TIMEOUT, DELAY, MALFORMED_RESPONSE, CONNECTION_RESET.
Switch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.
Journal: `GET /mock-control/payshield/journal` (`DELETE` clears it and resets the party).

Data triggers: none; the commands run on real key material; error codes by scenario (BUSINESS_ERROR `code`, default `15`).
<!-- mock-triggers:end -->

The three parties below are not mw-simulators parties.

## Email server (EXT-03)

GreenMail (compose service `greenmail`, SMTP 3025, web inbox and REST API 8025 on 127.0.0.1) receives every
email mw-notify sends. No data triggers, scenarios, switch or journal: it accepts every message; the inbox
is the record (`docs/testing-guide.html`).

## Core wallet (EXT-18)

`services/mw-core-mock` (the REST core of `contracts/api/mw-core-mock/core-wallet.v1.openapi.yaml`) runs its own
copy of the engine with one party `core` (contract `contracts/api/mw-core-mock/core-mock-control.v1.openapi.yaml`).

| Party | Operations | Scope keys |
| --- | --- | --- |
| `core` | validate, hold, commit, release, transfer, credit, reversal, query, balance, charges, limits, accounts, statements, unlock, factors | debit, credit, wallet, txnId, holdId, originalTxnId |

Scenarios: BUSINESS_ERROR (the command's failure, reason `code`, default `INJECTED_REJECT`; REST `422
MW-CORE-<code>`), DELAY, TIMEOUT, LATE_CALLBACK, DUPLICATE_CALLBACK, NO_CALLBACK (REST `504
MW-TMO-CORE_ANSWER_LOST` after the call was applied), HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET.
Switch: UP, DOWN, MAINTENANCE (`503 MW-SYS-CORE_UNAVAILABLE`), SLOW.
Journal: `GET /mock-control/core/journal` (one entry per handled command; `DELETE` clears it, the ledger stays).

Data triggers: none; the ledger rules decide (balances, holds, limits of the seeded wallets, `seed/<version>/`),
`POST /mock-control/reset {seedVersion?}` puts the seed back.
