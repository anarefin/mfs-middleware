# 14: Core-mock full command set + fault injection

**What to build:** `mw-core-mock` supports every command the MVP flows need: `HoldFunds`, `CommitHold`, `ReleaseHold`, `CreditWallet`, `ReverseTransaction` and `QueryTransaction`, plus `core.payment.command.v1` where §6.3 places them. It has a `hold` table and realistic held-balance behaviour. An admin endpoint injects faults per command type or txnId: delay, drop reply, duplicate reply, reject. This lets UNKNOWN and TSQ paths be exercised. Spec WP06 (rest).

**Blocked by:**

- 07: Core-mock P2P slice

**Status:** ready-for-agent

- [ ] Contract test per command, success and failure event
- [ ] Hold reduces available balance but not the ledger balance; commit moves money; release restores availability
- [ ] QueryTransaction returns the true outcome of a command whose reply was dropped
- [ ] Each fault mode can be triggered by a test and is cleared by a reset call
- [ ] Balance invariants still hold across hold/commit/release/reverse sequences
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
