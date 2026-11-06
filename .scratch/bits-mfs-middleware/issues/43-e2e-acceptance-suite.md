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

**Status:** done

- [x] `make e2e` is green on the MVP host from a fresh `make up` (deferred to milestone gate: needs the live stack) — evidence: M6 gate 2026-11-06 at 8e17214 (make verify-changed 14/14 modules, each a full ./gradlew check; fresh-stack make e2e 140/140; make edge-test 53/53; make log-scan clean)
- [x] A traceability table maps each scenario to acceptance criteria 1–5 (`tests/e2e/TRACEABILITY.md`, kept honest by the offline `TraceabilityTest`)
- [x] The suite runs twice in a row with no flakiness (deferred to milestone gate: needs the live stack) — evidence: two consecutive fresh-stack make e2e runs on 2026-11-06 (M6 gate and final re-check), both 140/140
