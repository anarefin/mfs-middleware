# 37: Maker-checker engine + manual TSQ / reversal

**What to build:** Operators can run a manual TSQ, force commit/release from MANUAL_REVIEW, or reverse a transaction, always approved by a second user (acceptance criterion 4). `mw-admin-api` has a change-request engine (`admin.change_request`, §15.3): writes to protected resources create requests; the checker must be a different user with the module's checker role; requests expire after 72 h. On approval the change is applied in one transaction, audited, and published. Approved TSQ, commit/release and reversal actions are sent to the orchestrator. Spec WP23 (part).

**Blocked by:**

- 36: Admin API: RBAC + txn search / timeline
- 18: Unknown outcomes: UNKNOWN, TSQ, MANUAL_REVIEW
- 19: Reversal flow

**Status:** done

- [x] API test: maker ≠ checker is enforced; an expired request cannot be approved
  (`mw-admin-api` `ChangeRequestApiIT`)
- [x] API test: an approved reversal reaches REVERSED; a rejected one changes nothing
  (`ChangeRequestApiIT`: the approval sends one schema-valid full-amount REVERSAL, a rejection
  sends nothing; REVERSAL -> REVERSED is mw-orchestrator's `ReversalSagaIT`; end to end in
  `tests/e2e/.../admin-maker-checker.feature`)
- [x] e2e: a txn in MANUAL_REVIEW is force-committed after approval (written: — evidence: M6 gate 2026-11-06 at 8e17214 (make verify-changed 14/14 modules, each a full ./gradlew check; fresh-stack make e2e 140/140; make edge-test 53/53; make log-scan clean)
  `admin-maker-checker.feature`, `@admin @mw-admin-api`; runs at the milestone gate)
- [x] Every decision is audited with before/after (admin-api `admin.change_request.*`, orchestrator
  `txn.operator.action`)
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR — evidence: M6 gate 2026-11-06 at 8e17214 (make verify-changed 14/14 modules, each a full ./gradlew check; fresh-stack make e2e 140/140; make edge-test 53/53; make log-scan clean)
  (deferred to milestone gate)
