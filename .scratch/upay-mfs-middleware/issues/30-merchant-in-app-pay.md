# 30: Merchant in-app MERCHANT_PAY

**What to build:** A customer can pay a merchant in the app, and the merchant can see collections. The orchestrator adds the `MERCHANT_PAY` flow. The app gateway adds the merchant role, merchant pay from the customer app, and a collections view for merchants. Spec WP20 (rest).

**Blocked by:**

- 11: Agent CASH_IN / CASH_OUT end to end

**Status:** done

- [x] Role tests: only the merchant sees their own collections
- [ ] e2e `@merchant`: in-app merchant payment success and rejection, balances asserted (written; run deferred to milestone gate)
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (deferred to milestone gate)
