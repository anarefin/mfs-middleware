# 34: Bulk execution + result file

**What to build:** An approved bulk batch pays out every row and produces a result file. The portal creates a parent `BULK_DISBURSE` txn and streams child txns to the orchestrator at a configured rate, each with a deterministic idempotency key per row. It tracks child outcomes and produces a downloadable result file. Pausing and resuming the batch is safe. Spec WP22 (part).

**Blocked by:**

- 33: Portal bulk upload + maker-checker

**Status:** ready-for-agent

- [ ] A re-run after a crash mid-batch never pays a row twice
- [ ] The throttle rate is honoured (measured in a test)
- [ ] e2e `@bulk`: a 10,000-row batch completes, the result file matches the outcomes, and the org balance is correct
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
