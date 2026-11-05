# 44: Chaos: kill containers mid-flow

**What to build:** Killing any one middleware container mid-flow leaves no transaction stuck (acceptance criterion 3). A chaos script runs mixed load, kills and restarts each `mw-*` container plus Kafka and PostgreSQL in turn, waits for timer sweeps and TSQ, and then asserts that every txn is terminal and the balances are consistent. Spec WP27.

**Blocked by:**

- 43: E2E acceptance suite completion (criteria 1–5)

**Status:** ready-for-agent

- [ ] The script reports zero non-terminal txns after recovery for every killed container (deferred to milestone gate: needs a live run on a fresh stack; the check itself is built and self-tested in `scripts/test/test_chaos.sh`)
- [ ] No double debit or credit in core-mock after any kill (deferred to milestone gate: live run; the ledger checks are built and self-tested)
- [x] Runnable with one make target (`make chaos [TARGETS=]`, report `build/chaos/chaos-report-<UTC>.md`); attaching the live report to the PR is deferred to milestone gate
