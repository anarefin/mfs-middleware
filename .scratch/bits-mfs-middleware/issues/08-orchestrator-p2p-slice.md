# 08: Orchestrator P2P slice

**What to build:** `mw-orchestrator` runs a P2P transaction as a persisted saga. It consumes `mw.txn.request.v1`, records idempotency in `txn.idempotency` (§7.3), and drives RECEIVED → VALIDATING → POSTING → COMPLETED, REJECTED or UNKNOWN by sending ValidateTransaction and PostTransfer to the core and consuming the correlated events. It publishes every state change on `mw.txn.status.v1` through the outbox. The state machine is plain Java (sealed interfaces + records) and models all 14 states and transitions of §7.2 now, even though only the P2P path is wired. Deadlines live in `txn.saga_timer` and are swept every second with `FOR UPDATE SKIP LOCKED` under ShedLock, and the §7.2 timeout actions apply (VALIDATING 5 s → FAILED, POSTING 5 s → UNKNOWN). `commandId` is derived from txnId + step. Spec WP07 (part).

**Blocked by:**

- 07: Core-mock P2P slice

**Status:** done

- [x] Unit tests cover 100% of the transitions in the §7.2 table, including invalid ones being rejected
- [x] Same idempotency key + same request hash returns the stored txnId and status; same key + different hash gives `MW-IDEM-001`
- [x] Timer test: a missing core reply moves VALIDATING → FAILED and POSTING → UNKNOWN after the configured deadline
- [x] Integration test with Testcontainers Kafka + PG and the orchestrator's own core stub: P2P reaches COMPLETED, and a core rejection reaches REJECTED
- [x] Against the running stack, a P2P request on `mw.txn.request.v1` completes against `mw-core-mock` and the balances move (manual run by the implementer: RECEIVED→VALIDATING→POSTING→COMPLETED; sender 1,000,000→989,500, receiver →1,010,000, FEE-POOL +500. Automated as an e2e scenario in ticket 10)
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
