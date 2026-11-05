# 11: Agent CASH_IN / CASH_OUT end to end

**What to build:** An agent can cash a customer in from the Agent app, and a customer can cash out at an agent from the Customer app. The orchestrator registers `CASH_IN` and `CASH_OUT` flows (validate → PostTransfer, no partner). The app gateway accepts agent tokens and enforces role per `txnType`: only agents can initiate CASH_IN (debit their own float, own PIN); only customers can initiate CASH_OUT (debit their own wallet, own PIN, payee is the agent). Core-mock enforces the allowed wallet types per txnType and a 5-failure PIN lockout. (Decision 2026-11-04: customer-initiated CASH_OUT, to avoid agents entering customer PINs.) Spec WP07 (rest) and WP20 (agent role part).

**Blocked by:**

- 10: E2E harness + P2P scenarios

**Status:** done

- [x] Unit tests for both flow definitions in the flow registry
- [x] Role test: a customer token trying CASH_IN and an agent token trying CASH_OUT are rejected with `MW-AUTH-*`
- [x] e2e `@cash`: agent cash-in and customer cash-out success, plus insufficient agent float rejection; balances asserted (simulated via core-mock fault injection; a real float drain is blocked by the per-txn limit)
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
