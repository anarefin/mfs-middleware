# 18: Unknown outcomes: UNKNOWN, TSQ, MANUAL_REVIEW

**What to build:** No transaction is ever left in an unknown state without a scheduled status inquiry (§7.4). A partner timeout or an UNKNOWN reply moves the txn to UNKNOWN. The orchestrator then schedules TSQ at 30 s, 1 min, 2 min, 5 min, 15 min and 1 h, then every 6 h until cut-off (per-partner override). The adapter implements inquiry against the OCS status endpoint. A SUCCESS result → COMMITTING; FAILED or NOT_FOUND → RELEASING; still pending → next slot; exhausted or conflicting → MANUAL_REVIEW. The customer gets a "processing" status immediately. Spec WP13 (part) and WP14 (rest).

**Blocked by:**

- 17: TOPUP end to end (hold → partner → commit / release)

**Status:** ready-for-agent

- [ ] Unit tests over the TSQ schedule and every UNKNOWN exit
- [ ] e2e `@topup @tsq`: OCS timeout → TSQ → commit; OCS timeout → TSQ → release
- [ ] e2e: a core reply dropped by fault injection resolves through QueryTransaction
- [ ] A test with schedule overrides shortened to seconds reaches MANUAL_REVIEW
- [ ] Metrics `mw_txn_unknown_open` and `mw_manual_review_open` reflect the backlog
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
