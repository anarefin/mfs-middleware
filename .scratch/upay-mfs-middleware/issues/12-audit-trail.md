# 12: Audit trail

**What to build:** Every saga state change is recorded in a tamper-evident audit trail (§14.3). `mw-audit-dw` (audit only in the MVP) consumes `mw.audit.event.v1` and writes to `audit.event`, an append-only table with monthly partitions. The writer role has INSERT only. A hash chain per partition (`prevHash`, `hash` = SHA-256 over row + prevHash) makes edits detectable. The orchestrator emits an AuditEvent on every state transition, through its outbox. Spec WP10.

**Blocked by:**

- 08: Orchestrator P2P slice

**Status:** done

- [x] Tamper-detection test: changing or deleting any row (as superuser) makes chain verification fail at that row
- [x] A permission test proves the writer role cannot UPDATE or DELETE
- [x] The same `auditId` delivered twice is stored once
- [x] e2e: a P2P txn produces audit rows for every state it passed through (covered by tests/e2e p2p audit scenario)
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
