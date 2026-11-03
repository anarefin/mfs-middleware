# 44: Chaos: kill containers mid-flow

**What to build:** Killing any one middleware container mid-flow leaves no transaction stuck (acceptance criterion 3). A chaos script runs mixed load, kills and restarts each `mw-*` container plus Kafka and PostgreSQL in turn, waits for timer sweeps and TSQ, and then asserts that every txn is terminal and the balances are consistent. Spec WP27.

**Blocked by:**

- 43: E2E acceptance suite completion (criteria 1–5)

**Status:** ready-for-agent

- [ ] The script reports zero non-terminal txns after recovery for every killed container
- [ ] No double debit or credit in core-mock after any kill
- [ ] Runnable with one make target; the report is attached to the PR
