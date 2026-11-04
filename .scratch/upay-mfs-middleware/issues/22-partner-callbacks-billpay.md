# 22: Partner callbacks (billpay async)

**What to build:** Partners that confirm by callback are supported (§11.2 inbound path). APISIX routes `/partner/{partnerId}/callback/*` (ip-restriction + hmac-auth) to the owning adapter. The adapter verifies the signature, deduplicates on partner reference, writes the event durably to Kafka before acknowledging the partner, and publishes `mw.partner.inbound.v1`. The orchestrator resolves PARTNER_PENDING or UNKNOWN from the inbound event. The aggregator sim gains callback-only mode with late, duplicate and out-of-order callbacks. Spec WP15 (rest).

**Blocked by:**

- 21: BILL_PAY sync path
- 13: APISIX edge policies

**Status:** done

- [x] A bad signature is rejected with 401 and nothing is published
- [x] e2e: callback-only bill pay completes
- [x] e2e: a duplicate callback has no second effect
- [ ] e2e: a late callback after TSQ already resolved the txn is recorded but does not change the terminal state (conflicting results → MANUAL_REVIEW)
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
