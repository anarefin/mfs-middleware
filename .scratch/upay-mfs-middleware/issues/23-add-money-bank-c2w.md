# 23: ADD_MONEY_BANK (C2W)

**What to build:** A customer can add money from a linked bank account. A C2W bank simulator is added. `mw-adapter-bank` is created with the framework copied. The orchestrator adds the `ADD_MONEY_BANK` flow: the bank debit request goes out, the bank confirms by callback, and the core gets CreditWallet. The flow works with no hold, since money is inbound. Spec WP12 (part) and WP16.

**Blocked by:**

- 22: Partner callbacks (billpay async)

**Status:** ready-for-agent

- [ ] e2e `@addmoney`: success credits the wallet once
- [ ] e2e: bank callback replay is deduplicated, with no double credit
- [ ] e2e: bank failure and bank timeout → TSQ → resolved
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
