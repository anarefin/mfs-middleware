# 07: Core-mock P2P slice

**What to build:** `mw-core-mock` acts as the core wallet for P2P. It consumes `core.wallet.command.v1` and handles `ValidateTransaction` (flat fee per `txnType`, per-txn and daily limits, PIN check, account status) and `PostTransfer` (double-entry move). It emits the §6.3 events on `core.wallet.event.v1`, deduplicating on `commandId` so that a repeated command returns the same result event. The PIN arrives RSA-OAEP (SHA-256) encrypted. Core-mock holds the private key in OpenBao, decrypts the PIN and checks it against a bcrypt hash. The ledger lives in schema `coremock` (`wallet`, `entry`, `command_log`), seeded with 1,000 customers, 50 agents, 20 merchants and 2 orgs. It is a test double and never ships to production. Spec WP06 (part).

**Blocked by:**

- 06: Template part 2b: outbox, inbox, conformance suite
- 48: Contracts v1.1: envelope + per-message payload schemas
- 49: M0 cleanup: spec sync, remove static analysis, dev gaps

**Status:** ready-for-agent

- [ ] Contract test per command: valid → success event; wrong PIN, insufficient funds and over limit → failure event with a reason code
- [ ] The same `commandId` sent twice gives one ledger effect and two identical result events
- [ ] Balance invariant test: the sum of all balances is unchanged by any number of transfers
- [ ] The seed loads on start and is reproducible; the public key is exportable for channels and tests
- [ ] PIN plaintext never appears in logs (the test captures log output)
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
