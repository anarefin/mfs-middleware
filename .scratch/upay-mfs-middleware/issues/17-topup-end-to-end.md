# 17: TOPUP end to end (hold → partner → commit / release)

**What to build:** A customer can top up airtime from the app. The orchestrator gains the partner path: VALIDATING → HOLDING (HoldFunds) → PARTNER_PENDING (`mw.partner.request.telco.v1`) → COMMITTING (CommitHold) → COMPLETED on partner success, or RELEASING (ReleaseHold) → FAILED on partner failure. COMMITTING and RELEASING retry until acknowledged. The flow registry adds `TopupFlow`, built from reusable steps (validate, hold, callPartner, commit, release). The app gateway accepts TOPUP. Spec WP13 (part) and WP14.

**Blocked by:**

- 10: E2E harness + P2P scenarios
- 14: Core-mock full command set + fault injection
- 16: Thin telco adapter

**Status:** ready-for-agent

- [ ] Unit tests for the hold path transitions and step reuse
- [ ] HOLDING timeout → QueryTransaction; if held → RELEASING
- [ ] e2e `@topup`: success debits once; partner failure releases the hold and the balance is unchanged
- [ ] e2e: a duplicate request with the same idempotency key never tops up twice
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
