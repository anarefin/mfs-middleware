# Testing guide: every feature, step by step, from a browser

For testers who check the Bits MFS middleware by hand. The middleware has no customer UI of its
own: the mobile app, USSD handsets, merchant web shops, banks, MNOs and billers are all outside it.
On a laptop stack two web UIs stand in for them:

| UI | URL | Plays |
| --- | --- | --- |
| **Test console** (dev/test only) | https://localhost/tester | the mobile app (customer, agent, merchant), a USSD handset, the partners (OCS, bill aggregator, bank: simulators you can switch to fail), the checkout shop and buyer, the org portal, the SMS and email inboxes |
| **Ops console** | https://localhost/console | the operations team: search, timeline, audit trail, reversals, approvals (maker-checker), partners, USSD menus, SMS templates, wallet unlock |

Every action in the test console goes through the API gateway (APISIX) exactly like a real client.
Each result shows **every API call it made** (open a step to see the answer), the final transaction
status, the **balances before and after** (from the core mock), and the **SMS** the wallets got.

Contents: [0 Setup](#0-setup) · [1 Reading a result](#1-reading-a-result) ·
[2 App flows](#2-app-flows) · [3 USSD](#3-ussd) · [4 Failures and recovery](#4-failures-timeouts-and-recovery) ·
[5 Notifications](#5-notifications-sms-and-email) · [6 Web checkout](#6-web-checkout) ·
[7 Bulk disbursement](#7-bulk-disbursement-and-sp-query) · [8 Ops console](#8-ops-console) ·
[9 Not in the MVP](#9-not-in-the-mvp) · [10 Troubleshooting](#10-troubleshooting) ·
[11 Automated equivalents](#11-the-same-checks-automated)

---

## 0. Setup

Prerequisites as in the [README](../README.md) (Docker, JDK 25, Node.js 24 + pnpm). From the repo root:

```sh
make reset                 # fresh volumes: seeded balances, daily limits, users
make build                 # jars and images (the test console image too)
make up TIMERS=fast        # the stack with short timeouts, so timeout tests take seconds, not minutes
make seed                  # seeded users and wallets
make tester                # turns on the test-only routes and starts the test console
```

`make tester` prints `https://localhost/tester`. The certificate is self-signed: accept it once in
the browser. Run `make tester` again after any plain `make up` (that removes the test routes).

**Test data you will use**

| Who | Sign-in user | Wallet | PIN |
| --- | --- | --- | --- |
| Customer 1 (payer in most tests) | customer01 | 01711000001 | 1234 |
| Customer 2 (payee) | customer02 | 01711000002 | 1234 |
| Agent | agent01 | 01811000001 | 1234 |
| Merchant "Demo Shop" | merchant01 | 01911000001 | 1234 |
| Org maker / checker (bulk) | org-maker01 / org-checker01 (org ORG001) | - | - |
| Service provider (SP query) | sp-viewer01 | - | - |
| Ops maker / checker (ops console) | ops-maker01 / ops-checker01 | - | password `KC_OPS_TEST_PASSWORD` in `deploy/.env`, plus TOTP |

Fees: the core charges the payer the amount **plus a fee** into the `FEE-POOL` account (P2P Tk 5,
top-up Tk 2, …). A Tk 10 P2P therefore shows `-Tk 15.00` on the payer, `+Tk 10.00` on the payee and
`+Tk 5.00` on FEE-POOL. Daily limit: Tk 15,000 debited per wallet per UTC day.

**Magic values** (the partner simulators react to them, no setup needed):

| Value | Effect |
| --- | --- |
| number / account / bill ending `999` | refused by the partner (business failure) |
| amount with `.13` paisa (e.g. `20.13`) | partner times out but **applied** the request |
| amount `.77` | partner answers 500 after applying |
| amount `.99` | partner answers an unknown code |
| amount `.55` | partner answers a broken body (applied) |
| bill number ending `404` / `000` | bill not found / already paid |
| SMS number ending `999` / `998` / `995` | delivery report UNDELIV / EXPIRED / none |

Before each failure test, and after it, press **Home → Reset every simulator and core fault**.

---

## 1. Reading a result

A result panel shows:

1. **Headline**: green ✔ or red ✖, the txnId and its status, and the error code if refused
   (for example `REJECTED MW-CORE-INVALID_PIN`).
2. **Final status** with a link **open in the ops console** (timeline, partner calls, audit trail).
3. **Balances** of every wallet involved: before, after, change, amount held now.
4. **SMS received since the submit.**
5. **Steps**: every call in order, each with its HTTP status and time. Click a step to see the answer.

Statuses: `COMPLETED` (done), `REJECTED` (refused before money moved), `FAILED` (attempted, nothing
moved), `PROCESSING` / `UNKNOWN` (the outcome is being resolved), `MANUAL_REVIEW` (an operator must
decide), `REVERSED`.

---

## 2. App flows

Page **App flows**. Pick the user (section 1), the transaction type (section 2), and press **Submit**.
The first submit binds the user's device automatically.

### 2.1 Device binding

1. User `customer01`, press **Bind device** (or **Re-bind (new device)** to bind a fresh one).
2. Expect three steps: *ask for the binding OTP* (HTTP 202), *read the OTP from the SMSC simulator*
   (the 6-digit OTP is shown), *bind the device* (HTTP 201).
3. Every later request is signed with this device's key.
   - Check: on **SMS & email**, `01711000001` received "Your Bits code to bind a new device is …".

### 2.2 Send money (P2P)

1. User `customer01`, type **Send money (P2P)**, counterparty `01711000002`, amount `10`, PIN `1234`, **Submit**.
2. Expect:
   - status `COMPLETED`;
   - balances: payer `-Tk 15.00`, payee `+Tk 10.00`, FEE-POOL `+Tk 5.00`;
   - two SMS (Bangla by default): the sender's says it was sent, with the fee; the receiver's says it was received. Both show masked numbers (`0171****002`).
3. **Idempotency**: press **Resend the last submit (same Idempotency-Key)**.
   - Expect the **same txnId** and **no balance change** (all `Tk 0.00`).
4. **Wrong PIN**: PIN `9999`, Submit.
   - Expect `REJECTED`, `MW-CORE-INVALID_PIN`, HTTP 422, no balance change.
   - Do not do this 5 times in a row on customer01: the wallet locks (see 8.6).
5. **Over the limit**: amount `6000` (per-transaction limit).
   - Expect `REJECTED`, `MW-CORE-LIMIT_EXCEEDED_PER_TXN`.
6. Open the txn in the ops console (link in the result): the timeline shows the states and the core command.

### 2.3 Cash in (agent → customer)

1. User `agent01`, type **Cash in**, counterparty `01711000001`, amount `20`.
2. Expect `COMPLETED`, the customer `+Tk 20.00`, the agent debited, and SMS to both.
3. Negative: counterparty `01811000001` (an agent, not a customer).
   - Expect the core to refuse it.

### 2.4 Cash out (customer → agent)

1. User `customer01`, type **Cash out**, counterparty `01811000001`, amount `20`.
2. Expect `COMPLETED`, and the customer's SMS shows the fee.
3. Negative: counterparty `01711000002` (not an agent).
   - Expect a refusal.

### 2.5 Merchant payment (in-app)

1. User `customer01`, type **Merchant payment**, counterparty `01911000001`, amount `25`.
2. Expect `COMPLETED`, and the merchant wallet `+Tk 25.00`.
3. Switch the user to `merchant01` and press **Merchant collections** (section 3).
   - Expect the payment in the list.
4. Negative: counterparty `01711000002` (not a merchant).
   - Expect a refusal.

### 2.6 Mobile top-up (OCS)

1. User `customer01`, type **Mobile top-up**, operator `gp`, product `PREPAID`, number `01711000002`, amount `20`.
2. Expect `COMPLETED`, and the payer `-Tk 22.00`.
   - The money is **held**, then committed when the operator confirms.
3. Variants:

   | Change | Expect |
   | --- | --- |
   | number `01711000999` | `FAILED` with `MW-PTR-*`, nothing moved, held amount back to 0 |
   | amount `20.13` | the answer is first 202 `PROCESSING` (the operator timed out), then `COMPLETED` after the status inquiry (about 6–10 s with `TIMERS=fast`); money moved once |
   | PIN `9999` | `REJECTED`, the operator is never called (Simulators → ocs → *Show scenario and last 10 requests*: no new request) |

### 2.7 Bill payment (aggregator)

1. User `customer01`, section 3: biller `DESCO`, bill `1234567`, press **Fetch bill**.
   - Expect the amount due: Tk 667.00 (Tk 100 plus the bill number's last 3 digits).
2. Type **Bill payment**, biller `DESCO`, bill `1234567`, amount `100`, **Submit**.
   - Expect `COMPLETED`, an SMS, and an email receipt (see 5).
3. Variants:

   | Change | Expect |
   | --- | --- |
   | bill `1234999` | refused by the biller, hold released |
   | Fetch bill `1234404` | not found |
   | amount `100.13` | timeout → status inquiry → `COMPLETED` |

4. Callback-only billers: see 4.4.

### 2.8 Add money from bank (C2W)

1. User `customer01`, type **Add money from bank**, account `1234567890123`, amount `500`.
2. Expect:
   - the bank simulator answers 202 PENDING;
   - its signed callback completes the transaction: `COMPLETED`, wallet `+Tk 500.00`;
   - an SMS and an email receipt.
   - If the bank simulator had no callback set, the first step sets "callbacks ONCE" and says so.
3. Variants:

   | Change | Expect |
   | --- | --- |
   | account `1234567890999` | `FAILED`, nothing credited |
   | PIN `9999` | `REJECTED`, the bank is never asked |
   | amount `500.13` | the bank times out and sends no callback; the status inquiry finds the debit and the wallet is credited |
   | **Resend** (same key) | credited once |
   | Simulators → preset *Bank: every callback sent twice* | credited once |

### 2.9 Transaction status

In section 3, the txnId of the last submit is filled in. Press **Status** to see the transaction as the app does.

---

## 3. USSD

Page **USSD phone**. It is a feature phone dialling `*268#`.

- **robi** is connected over **HTTP**.
- **gp** is connected over **SMPP**.

The menus and the engine are the same; only the transport differs. Type a reply in the box (or use
the keypad), then press **Send**.

### 3.1 Send money over HTTP (robi), in English

1. MNO `robi (HTTP)`, subscriber `01711000001`, **Dial**.
   - The screen shows the Bangla main menu.
2. Send `9`: the menu switches to English.
3. Send `1` (Send Money), `01711000002` (recipient), `10` (amount), `1` (confirm), `1234` (PIN).
4. Expect:
   - the session ends ([END] in the transcript) with a confirmation carrying the TxnID;
   - the PIN shows as `****` in the transcript;
   - the balances on **Home** changed (payer -Tk 15, payee +Tk 10);
   - SMS to both numbers.

### 3.2 The same over SMPP (gp)

1. Choose MNO `gp (SMPP)` and repeat 3.1.
2. Expect the same screens and result. Each key press replays the session from the dial; only the last step moves money.
3. Resilience: Simulators → **USSD SMPP gateway (gp)**:
   - **Show binds**: mw-ussd is bound.
   - **Drop every bind**, wait a few seconds, then **Show binds** again: re-bound. Dial on gp again: it works.

### 3.3 Other menus (robi, after `9` for English)

| Inputs after `9` | Flow | Expect |
| --- | --- | --- |
| `1, 01711000002, 10, 1, 1234` | Send money | ends with the TxnID |
| `1, 01711000002, 10, 1, 9999` | wrong PIN | ends with a PIN error, nothing moved |
| `1, 12345` | invalid number | "Enter a valid mobile number", session stays open |
| `2, 01811000001, 50, 1, 1234` | Cash out to the agent | ends with the TxnID |
| `3, 1, 20, 1, 1234` | Recharge own number | ends with success or processing |
| `3, 2, 01711000999, 1, 20, 1, 1234` | Recharge another number (refused) | ends with a failure |
| `4, 1, 1234567, 1, 1234` | Pay bill (first biller) | ends with the TxnID |
| `4, 1, 1234404` | Pay bill, unknown bill | bill not found |

Without `9`, the same flows run in Bangla: check the Bangla texts and that the SMS are in Bangla.
Three invalid entries in a row end the session ("Too many invalid entries").

---

## 4. Failures, timeouts and recovery

Page **Simulators & faults**. A **preset** is a ready-made failure test: each says what it proves and
what to do next. Apply it, run the flow on the App / USSD / Checkout page, check, then press
**Reset everything**.

### 4.1 Lost core reply → status inquiry → COMPLETED (timeout → TSQ → commit)

1. Preset **Core: the reply of the next transfer is lost**.
2. App: P2P `01711000002`, Tk 10.
3. Expect:
   - a step *poll the transaction until it is final (was PROCESSING)*;
   - then `COMPLETED`;
   - the payer debited **once** (`-Tk 15.00`).
   - The SMS say "processing", then "sent".

### 4.2 Lost refusal → status inquiry → FAILED (timeout → TSQ → release)

1. Preset **Core: the next transfer is refused and the refusal lost**.
2. App: P2P.
3. Expect `FAILED` (code `MW-TMO-003` in the ops console), with no balance changed.

### 4.3 Partner timeouts on held flows (UNKNOWN)

| Preset | Flow | Expect |
| --- | --- | --- |
| *OCS: every top-up times out (applied)* | top-up | `PROCESSING`, then `COMPLETED` after the inquiry; the hold is committed |
| *OCS: answers too late and refuses (not applied)* | top-up | `PROCESSING`; about 25 s later press **Reset ocs**; the inquiry finds nothing, the hold is released, `FAILED` |
| *OCS (top-up) outage* | top-up | `FAILED` at once, hold released |
| *Core: the reply of the next hold is lost* | top-up or bill pay | resolved by the inquiry |
| *Core: the next validation says insufficient balance* | any | `REJECTED MW-CORE-INSUFFICIENT_BALANCE` |

### 4.4 Callbacks (bill aggregator and bank)

| Preset | Flow | Expect |
| --- | --- | --- |
| *Bill aggregator: callback-only payments* | bill pay | the payment is answered 202 PENDING; the aggregator's signed callback completes it |
| *… every callback sent twice* | bill pay | balance moved once; the duplicate is ignored (audit trail in the ops console) |
| *… outcome then a stale PENDING* | bill pay | stays `COMPLETED` |
| *Bank: every callback sent twice* | add money | credited once |
| *Bank: the callback comes 20 s late* | add money | completes when the callback (or the inquiry) arrives |

**Replay a callback by hand**:
1. Simulators → billagg or c2wbank → **Show scenario and last 10 requests**.
2. Copy the `transactionId` the partner received.
3. Paste it under **Replay a callback**, press **Replay**.
4. Expect no second balance change.

### 4.5 MANUAL_REVIEW

1. Preset **Core: the next hold commit is refused (→ MANUAL_REVIEW)**.
2. App: top-up.
3. Expect `MANUAL_REVIEW`.
4. Resolve it in the ops console (8.3).

### 4.6 Custom behaviour

The partner forms set any combination:
- outcome (forceMode), latency, error rate, outage, timeout hold;
- callback mode and delay, callback-only.

The core-mock form injects DROP_REPLY / REJECT / DELAY / DUPLICATE_REPLY on any core command. **Clear
every fault** removes them.

---

## 5. Notifications (SMS and email)

Page **SMS & email**. Enter a number and/or an email address, then press **Read** (newest first).

| After | Expect |
| --- | --- |
| P2P | sender: "sent … fee …"; receiver: "received …"; Bangla by default; numbers masked `0171****001`; DLR `DELIVRD` |
| P2P refused | sender: "failed" |
| P2P with lost core reply (4.1) | "processing", then "sent" |
| Cash in / cash out | both parties (cash out shows the fee) |
| Top-up | payer and the recharged number |
| Bill pay, add money | SMS plus an email receipt to `customer01@example.test` (multipart text + HTML) |
| Device binding, checkout OTP | the OTP SMS |
| SMS to a number ending 999 / 998 / 995 | DLR UNDELIV / EXPIRED / none (mw-notify keeps the delivery state) |

Bangla SMS are UCS-2 (shown as "UCS2 · n part(s)"); English ones are GSM-7.

---

## 6. Web checkout

Page **Web checkout**. The shop is "Demo Shop" (merchant wallet 01911000001).

1. **Create checkout session**, Tk 25.
   - Expect HTTP 201 and status `OPEN`.
   - The step shows the signed request: Date, Digest and an hmac Signature.
2. **Open the checkout page**.
   - Expect "Demo Shop Tk 25.00 (OPEN)".
3. Payer wallet `01711000001`, **Send OTP**.
   - Expect HTTP 202; the OTP is read from the SMS and filled in.
4. PIN `1234`, **Pay**.
   - Expect `result PAID` with a txnId, and the payer balance lower.
5. **Return to the shop (signed redirect)**.
   - Expect HTTP 303 to `https://shop.example.test/checkout/ok` with status PAID, and the step *signature valid*.
6. **Shop server: session status and IPNs**.
   - Expect the session PAID, and one IPN answered 200 with a valid signature.

Variants:
- **Wrong OTP** (edit the OTP field): `MW-AUTH-010`, nothing paid.
- **Pay twice**: the second is refused, nothing paid again.
- **IPN retries**: before step 4, apply the preset *Merchant IPN endpoint fails the next 2 IPNs*. In step 6 the IPNs show answered 503, 503, then 200 (attempts 1, 2, 3; press it again after a few seconds).
- **Core refuses**: apply *Core: the next validation says insufficient balance* before Pay. The result is FAILED and the redirect goes to the fail URL.

---

## 7. Bulk disbursement and SP query

Page **Bulk portal**.

1. User `org-maker01`, rows `5`, Tk `1.00`, **Generate sample**.
   - The CSV `msisdn,amount,reference` appears and can be edited.
2. **Upload as org-maker01**.
   - Expect a batch id (filled in below) and its status.
3. Switch the user to `org-checker01`, press **Approve**.
   - Then press **Batch status** until the batch has finished.
   - Each row is paid as a throttled child transaction.
4. **Result file**: one line per row with its outcome. **Transactions**: the batch's transactions.
5. Variants:

   | Change | Expect |
   | --- | --- |
   | approve as `org-maker01` (own batch) | HTTP 403 (maker ≠ checker); `org-makerchecker01` may not approve its own upload either, even with the checker role |
   | approve as `org-checker02` (other org) | HTTP 404 `MW-VAL-003`: another org cannot see the batch |
   | generate 1000 rows → fine; a file over 10,000 rows | refused as a whole |
   | **Reject** with a reason | the batch is REJECTED, nothing paid |
   | **Pause** / **Resume** during execution | rows stop, then continue |
   | a broken CSV (wrong header, bad number) | upload refused with `MW-VAL-*` details |

6. SP query: user `sp-viewer01`, empty batch id, **Transactions**.
   - Expect the transactions where the SP is initiator or payee.

---

## 8. Ops console

https://localhost/console. Sign in as `ops-maker01` (password `KC_OPS_TEST_PASSWORD` from
`deploy/.env`) and enrol TOTP with an authenticator app at the first sign-in. Use a private window
for `ops-checker01`, the second operator. Run a few app flows first so there are transactions.

### 8.1 Find a transaction, timeline and audit

1. **Transactions**: search MSISDN `01711000001`.
   - The list shows masked numbers.
2. Or paste a txnId from a test console result: the *open in the ops console* link goes straight there.
3. Open a COMPLETED P2P. Expect:
   - the summary;
   - the saga **timeline** (STATE and CORE steps);
   - the **partner calls** (top-up / bill pay / add money);
   - the **audit trail**.

### 8.2 Reversal (maker → checker)

1. As `ops-maker01`, on a COMPLETED P2P: type a reason, **Request reversal**.
   - Expect the console to land on Approvals with a PENDING request (COMPLETED → REVERSING).
2. The maker cannot approve their own request ("another checker decides").
3. As `ops-checker01`: Approvals → **Approve**.
4. Reload the transaction. Expect:
   - `REVERSED`, with a linked REVERSAL transaction;
   - in the test console, the balances restored.
5. **Reject** instead: nothing changes.
6. Preset *Core: the next reversal is refused*, then reverse: the reversal ends `MANUAL_REVIEW`, with an audit entry.

### 8.3 MANUAL_REVIEW: force commit or release

1. Make a top-up in MANUAL_REVIEW (4.5).
2. As the maker, on that transaction: **Request force commit** (or force release / manual TSQ).
3. Approve it as the checker.
4. Expect:
   - force commit: `COMPLETED`, money moved once, no hold left;
   - force release: `FAILED`, hold released.

### 8.4 Partners

1. **Partners** → `ocs`: request **disable** as maker, approve as checker.
   - The partner shows disabled (version + 1).
   - Test console: a top-up now fails at once.
2. Enable it again the same way.
3. **Timeouts** form: change the call timeout (maker, then checker) and see the new version.

### 8.5 USSD menus and SMS templates

1. **USSD menus** (`/console/ussd?menuId=main`): edit a text, preview it in the browser, propose, approve as the checker.
   - The new version is PUBLISHED.
   - With go-live now, the next USSD session (test console) shows the new text; a session already open keeps the old one.
2. **SMS templates** (`/console/templates`): edit the P2P sender text, preview it (masked sample values), propose, approve.
   - The next P2P SMS uses the new text.
3. Put both back afterwards, or `make reset` later.

### 8.6 Wallet unlock

1. Test console App: user `customer02`, P2P to `01711000001` with PIN `9999` **five times**.
   - Then with `1234`: `MW-CORE-ACCOUNT_LOCKED`.
   - Simulators → *A wallet in core-mock* `01711000002`: status LOCKED.
2. Ops console **Wallets**: request UNLOCK for `01711000002` (maker), approve (checker).
3. The wallet is ACTIVE again and a P2P with `1234` completes.

---

## 9. Not in the MVP

These appear in the spec's catalogue but are **not built**, so there is nothing to test yet:

- **Send money to bank (W2B, wallet to bank account)** via a partner bank or NPSB
- NPSB / BanglaQR interoperable QR payments (`QR_PAY_INTEROP`)
- Bank account verification / lookup (AVL) and card payments
- Live partner integrations: every partner is a simulator; there is no real MNO, bank or biller

"Add money from bank" (bank → wallet, C2W) **is** built (2.8).

---

## 10. Troubleshooting

| Symptom | Fix |
| --- | --- |
| https://localhost/tester answers 404 or a JSON error | the test routes were removed by a plain `make up`: run `make tester` |
| Home shows ✖ "test-only routes are on" | `make tester` |
| `MW-CORE-LIMIT_EXCEEDED_DAILY` | the wallet spent Tk 15,000 today (UTC): use customer02 as payer, or `make reset && make up TIMERS=fast && make seed && make tester` |
| `MW-CORE-ACCOUNT_LOCKED` | five wrong PINs: unlock in the ops console (8.6) |
| Everything times out or fails oddly | a preset or fault is still on: Home → Reset every simulator and core fault |
| Timeout tests take minutes | the stack runs production timers: `make up TIMERS=fast && make tester` |
| A submit says no binding OTP arrived | mw-notify or the SMSC simulator is down: `docker compose -f deploy/docker-compose.yml ps` |
| The API suite (`make api-test`) fails a device test after you used the test console | each wallet has one active device; rerun the suite (it binds again) |
| Console sign-in loops | TOTP code reused within 30 s: wait for the next code |

---

## 11. The same checks, automated

Each section has an automated twin in the Newman API suite (`make api-test FOLDER=…`, see
`tests/api/README.md`) and in the test console's own browser suite (`make tester-e2e`):

| Section | `make api-test FOLDER=` |
| --- | --- |
| 2.1 device binding | `02` |
| 2.2 P2P | `01` |
| 2.3, 2.4 cash | `03` |
| 2.5 merchant | `04` |
| 2.6 top-up | `05` |
| 2.7, 4.4 bill pay | `06, 07` |
| 2.8 add money | `08` |
| 3 USSD | `18, 19, 20` |
| 5 notifications | `10, 11` |
| 6 checkout | `09` |
| 7 bulk | `21, 22, 23` |
| 8.2 reversal | `12, 13` |
| 8.1 search and timeline | `14` |
| 8.4 partners | `15` |
| 8.6 wallet unlock | `16` |
| 8.5 menus and templates | `17` |
