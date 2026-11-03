# 37: Maker-checker engine + manual TSQ / reversal

**What to build:** Operators can run a manual TSQ, force commit/release from MANUAL_REVIEW, or reverse a transaction, always approved by a second user (acceptance criterion 4). `mw-admin-api` has a change-request engine (`admin.change_request`, §15.3): writes to protected resources create requests; the checker must be a different user with the module's checker role; requests expire after 72 h. On approval the change is applied in one transaction, audited, and published. Approved TSQ, commit/release and reversal actions are sent to the orchestrator. Spec WP23 (part).

**Blocked by:**

- 36: Admin API: RBAC + txn search / timeline
- 18: Unknown outcomes: UNKNOWN, TSQ, MANUAL_REVIEW
- 19: Reversal flow

**Status:** ready-for-agent

- [ ] API test: maker ≠ checker is enforced; an expired request cannot be approved
- [ ] API test: an approved reversal reaches REVERSED; a rejected one changes nothing
- [ ] e2e: a txn in MANUAL_REVIEW is force-committed after approval
- [ ] Every decision is audited with before/after
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
