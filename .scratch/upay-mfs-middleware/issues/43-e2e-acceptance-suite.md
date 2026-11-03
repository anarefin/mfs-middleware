# 43: E2E acceptance suite completion (criteria 1–5)

**What to build:** One command proves MVP acceptance criteria 1–5. The Cucumber suite covers every MVP flow (P2P, CASH_IN, CASH_OUT, TOPUP, BILL_PAY, ADD_MONEY_BANK, MERCHANT_PAY via app and checkout) from its channel, with success, business failure, timeout → TSQ → commit and timeout → TSQ → release scenarios. It also covers idempotent repeats with balance checks, operator search + timeline + approved reversal, and USSD over both transports. Gaps found in earlier tickets' scenarios are filled here. Spec WP26.

**Blocked by:**

- 23: ADD_MONEY_BANK (C2W)
- 25: Notify email + per-flow notification checks
- 27: USSD SMPP transport for GP
- 28: USSD menus for all MVP flows
- 32: Checkout signed IPN with retries
- 34: Bulk execution + result file
- 37: Maker-checker engine + manual TSQ / reversal

**Status:** ready-for-agent

- [ ] `make e2e` is green on the MVP host from a fresh `make up`
- [ ] A traceability table maps each scenario to acceptance criteria 1–5
- [ ] The suite runs twice in a row with no flakiness
