# 19: Reversal flow

**What to build:** A completed transaction can be compensated. A `REVERSAL` request carrying `originalTxnId` makes the orchestrator move the original from COMPLETED to REVERSING. It sends ReverseTransaction and ends in REVERSED, or in MANUAL_REVIEW on rejection or exhaustion. Only COMPLETED txns can be reversed, each one once. In this ticket a test or e2e step triggers the reversal through the request topic; the admin maker-checker trigger comes in ticket 37. Spec WP13 (rest).

**Blocked by:**

- 17: TOPUP end to end (hold → partner → commit / release)

**Status:** done

- [x] Unit tests: reversing a non-COMPLETED txn is rejected; a second reversal of the same txn is idempotent
- [x] e2e `@reversal`: P2P completed → reversal → balances restored
- [x] Core ReversalRejected → MANUAL_REVIEW with an audit entry
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
