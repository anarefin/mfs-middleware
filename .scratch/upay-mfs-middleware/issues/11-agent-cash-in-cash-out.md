# 11: Agent CASH_IN / CASH_OUT end to end

**What to build:** An agent can cash a customer in or out from the Agent app. The orchestrator registers `CASH_IN` and `CASH_OUT` flows (validate → PostTransfer, no partner). The app gateway accepts agent tokens and enforces role per `txnType`: only agents can initiate CASH_IN and CASH_OUT. Spec WP07 (rest) and WP20 (agent role part).

**Blocked by:**

- 10: E2E harness + P2P scenarios

**Status:** ready-for-agent

- [ ] Unit tests for both flow definitions in the flow registry
- [ ] Role test: a customer token trying CASH_IN is rejected with `MW-AUTH-*`
- [ ] e2e `@cash`: cash-in and cash-out success, plus insufficient agent float rejection; balances asserted
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
