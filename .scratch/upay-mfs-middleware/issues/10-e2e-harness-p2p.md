# 10: E2E harness + P2P scenarios

**What to build:** `make e2e` runs a black-box Cucumber suite against the Compose stack, talking to it only over the network. The first feature covers P2P through APISIX: success, core business rejection (insufficient funds, wrong PIN), and a repeated request with the same idempotency key. The suite checks core-mock balances directly (through core-mock's test/admin API) to prove money never moves twice (acceptance criterion 2). Tags allow `make e2e TAGS=@p2p`. Spec WP26 (start).

**Blocked by:**

- 09: App gateway P2P + minimal APISIX route

**Status:** ready-for-agent

- [ ] `make e2e TAGS=@p2p` passes on a fresh `make up`
- [ ] Scenario: the same idempotency key sent twice debits once and returns the same txnId
- [ ] Scenario: the same key with a different body gives 409 `MW-IDEM-001`
- [ ] The harness has step definitions that later tickets reuse: obtain a token, encrypt a PIN with the core public key, submit a txn, poll status, read a balance
- [ ] Running the suite twice in a row is stable (no reliance on leftover state)
