# 34: Bulk execution + result file

**What to build:** An approved bulk batch pays out every row and produces a result file. The portal creates a parent `BULK_DISBURSE` txn and streams child txns to the orchestrator at a configured rate, each with a deterministic idempotency key per row. It tracks child outcomes and produces a downloadable result file. Pausing and resuming the batch is safe. Spec WP22 (part).

**Blocked by:**

- 33: Portal bulk upload + maker-checker

**Status:** done

- [x] A re-run after a crash mid-batch never pays a row twice (portal `BulkExecutionIT`: same txnId, key and hashed content on resend; orchestrator `BulkDisburseSagaIT`: a resent child is one transaction)
- [x] The throttle rate is honoured (measured in a test: `BulkExecutionIT.theThrottleRateIsHonoured`, test clock, 20/s over 10 s and every 1 s window)
- [ ] e2e `@bulk`: a 10,000-row batch completes, the result file matches the outcomes, and the org balance is correct (`features/bulk-execution.feature` written; live run deferred to milestone gate)
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (deferred to milestone gate; `./gradlew test` green for mw-portal-api, mw-orchestrator, mw-core-mock)

Decisions taken (for the gate):

- The parent `BULK_DISBURSE` txn is the batch's own record and id (`batch.parent_txn_id`, derived from the batch id), not an orchestrator saga: it moves no money. Children carry it in `attributes.parentTxnId`.
- Children are `BULK_DISBURSE` from channel `ORG_PORTAL`, debit `{ORG, <org wallet>}`, no PIN (maker-checker is the authorisation). Contract change (owner mw-orchestrator): `core.command.v1` ValidateTransaction/PostTransfer require `encryptedPin` except for `BULK_DISBURSE`; needs the core team's confirmation (open point 1). The orchestrator only accepts BULK_DISBURSE from `bulk-channels` (`ORG_PORTAL`, else `MW-AUTH-021`); the core mock only ORG -> CUSTOMER, with a per-wallet-type daily limit for ORG.
- Org -> payout wallet mapping is config (`mw.portal-api.bulk.org-wallets`); approval without one is `409 MW-VAL-034`.
- Default rate 50 rows/s (`MW_BULK_RATE_PER_SECOND`); MANUAL_REVIEW rows count as settled for batch completion; no automatic re-send of a SUBMITTED row that never settles.
