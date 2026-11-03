# 36: Admin API: RBAC + txn search / timeline

**What to build:** Operators can search transactions and see their saga timeline and audit trail. `mw-admin-api` authenticates against Keycloak realm `ops` (MFA) on `/admin/v1/*`, maps the §15.4 roles, and calls the orchestrator and audit-dw query APIs. It masks fields by role: only `compliance-officer` and `auditor` see an unmasked MSISDN, and every reveal is audited. Spec WP23 (part).

**Blocked by:**

- 35: Internal query APIs + SP txn query

**Status:** ready-for-agent

- [ ] Role tests per endpoint, including viewer read-only
- [ ] An unmasked reveal by an allowed role writes an audit event; other roles get masked values
- [ ] API test: search by txnId and by MSISDN returns the txn with timeline and audit
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
