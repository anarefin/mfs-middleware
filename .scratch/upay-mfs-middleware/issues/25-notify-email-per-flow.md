# 25: Notify email + per-flow notification checks

**What to build:** Notifications cover email as well as SMS, and every flow built so far sends the right messages. `mw-notify` sends email through SMTP to GreenMail (HTML + text templates). Rules and templates are seeded for P2P, CASH_IN, CASH_OUT, TOPUP, BILL_PAY and ADD_MONEY_BANK (success, failure, processing). Spec WP17 (rest).

**Blocked by:**

- 24: Notify SMS over SMPP

**Status:** done

- [x] Integration test: email reaches GreenMail with rendered subject and body (`EmailFlowIT`, in-process GreenMail; per-flow seeds in `FlowNotificationsIT`)
- [ ] e2e: for each flow above, the expected SMS (and email where configured) is asserted (written: `features/notify-flows.feature`; run deferred to milestone gate)
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (deferred to milestone gate)
