# 50: M2 gate code follow-ups

**What to build:** This ticket carries out the code changes decided at the M2 gate (2026-11-04). The spec text changes are ticket 51.

1. **Valkey idempotency lock (spec §7.3 rule 4):**
   - The orchestrator takes a 60 s Valkey lock per idempotency key (`SET NX PX`) before the PostgreSQL claim.
   - The primary key on the idempotency row stays as the final guarantee.
   - If Valkey is unreachable, fall back to the primary key alone: never block or fail the request because the lock is unavailable. Log the fallback as a business event.
2. **Core command topics:**
   - `HoldFunds`, `CommitHold` and `ReleaseHold` go on `core.payment.command.v1`, and their results come back on `core.payment.event.v1`.
   - Every other core command stays on `core.wallet.*`.
   - The orchestrator sends and consumes accordingly. `mw-core-mock` keeps accepting either topic, but replies on the topic paired with the one the command arrived on.
   - Update both modules' `CLAUDE.md`.
3. **Rate-limit alert:** APISIX `limit-count` stays fail-open when Valkey is down. Add a stable alert signal for limiter errors, through APISIX logs or a metric that `make smoke` can see, and document it in `deploy/apisix/README.md`.
4. **Audit chain verification:**
   - Add a read-only `audit_reader` database role, provisioned by the audit-dw init.
   - Add a scheduled job, ShedLock-guarded, that uses `audit_reader` to verify each month's hash chain. On a broken chain it raises an ERROR business event `audit.chain.broken` and sets a metric.
   - The writer role stays INSERT-only.
5. **MSISDN with separators:**
   - The log scrubber masks MSISDNs written with single spaces or dashes between digit groups, for example `01711-234567`, `0171 123 4567` and `+880 1711-234567`.
   - Tests prove that amounts, dates and times are not masked.
   - Make the change identically in the template and every service.
6. **PIN ciphertext cleanup test:** `transaction.encrypted_pin` must be null after every terminal state (COMPLETED, REJECTED, FAILED, REVERSED) and after MANUAL_REVIEW. Add a test per path.
7. **Insecure partner HTTP default:**
   - `MW_PARTNER_INSECURE_HTTP_HOSTS` defaults to empty in each adapter's `application.yml`.
   - Only the dev Compose stack and the tests allow the simulator host.

**Blocked by:** None (can start immediately). Run before tickets 24+ so later work builds on it.

**Status:** done

- [x] A Valkey lock test shows the second concurrent first request is answered as a duplicate without a second claim attempt, and a Valkey-down test shows requests still succeed on the primary key (`IdempotencyGateTest`, `ValkeyIdempotencyLockTest`, `IdempotencyLockIntakeIT`)
- [x] Hold, commit and release travel on `core.payment.*` (done: `CoreTopicsTest`, `TopupSagaIT`), and the `@topup`, `@billpay`, `@tsq` and `@reversal` e2e scenarios pass unchanged: green at the milestone gate (below)
- [x] The limiter-error alert is observable and documented (`mw.edge.rate_limit_degraded`, `deploy/apisix/README.md`; the live `make smoke` / `make edge-test` checks are green at the milestone gate)
- [x] The audit chain verify job detects a tampered row using `audit_reader`, and the writer still cannot SELECT (`AuditChainVerifyIT`, `ChainVerifyServiceTest`)
- [x] Scrubber tests cover the separator formats and the non-MSISDN numbers, and every `MaskingPolicy` copy is identical apart from the package
- [x] PIN ciphertext is null on every terminal path (tests) (`TransactionRecordPinTest`; MANUAL_REVIEW did not clear it before, fixed)
- [x] Adapters refuse plain http to the simulator unless the dev stack or a test enables it (`PartnerSecurityDefaultsTest` in all three adapters)
- [x] `make verify` passes for every module touched, and the full `make e2e` passes on a fresh stack: green at the milestone gate (below)

## Milestone gate (2026-11-05, integration branch `spec/bits-mfs-middleware`)

- `make verify` (all 13 modules, at 9b73ac2 after the review fixes; later gate fixes verified per module): `==> verify passed: 13 module(s)`, exit 0
- Fresh stack at fcd51ef (`make down DOWN_ARGS=-v && make up`):
  - `make smoke`: `smoke: 46 passed, 0 failed`
  - `make edge-test`: `edge-test: 42 passed, 0 failed`
  - `make e2e` (full suite): `110 scenarios, 0 failed, 0 skipped`, `BUILD SUCCESSFUL in 21m 10s`
- Final re-run after the masking fixes, fresh stack at bfd889b: `edge-test: 43 passed, 0 failed`; full `make e2e` 110 scenarios, 0 failed (`BUILD SUCCESSFUL in 21m 32s`). No full `make verify` after 9b73ac2 (user decision): the later fixes ran their targeted tests.
- Gate fixes and user decisions: see the merge commits after 70f7add and memory `bits-m3-decisions`.
