# 36: Admin API: RBAC + txn search / timeline

**What to build:** Operators can search transactions and see their saga timeline and audit trail. `mw-admin-api` authenticates against Keycloak realm `ops` (MFA) on `/admin/v1/*`, maps the §15.4 roles, and calls the orchestrator and audit-dw query APIs. It masks fields by role: only `compliance-officer` and `auditor` see an unmasked MSISDN, and every reveal is audited. Spec WP23 (part).

**Blocked by:**

- 35: Internal query APIs + SP txn query

**Status:** done

- [x] Role tests per endpoint, including viewer read-only
- [x] An unmasked reveal by an allowed role writes an audit event; other roles get masked values
- [x] API test: search by txnId and by MSISDN returns the txn with timeline and audit
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (deferred to milestone gate) — evidence: M6 gate 2026-11-06 at 8e17214 (make verify-changed 14/14 modules, each a full ./gradlew check; fresh-stack make e2e 140/140; make edge-test 53/53; make log-scan clean)

**Notes (implementation):**

- New module `services/mw-admin-api` (package `mfs.mw.admin.api`, schema `admin`), contract
  `api/mw-admin-api/admin.v1.openapi.yaml`: `GET /admin/v1/transactions` and
  `GET /admin/v1/transactions/{txnId}[?reveal=true]` -> `{transaction, timeline, partnerCalls, auditTrail, revealed}`.
  Tests: `AdminTransactionApiIT` (roles per endpoint incl. viewer/auditor read-only on every write path,
  reveal audit, search by txnId and MSISDN), `ApiContractsTest`, `ProducedContractsTest`, `InternalClientAuthTest`.
- mw-orchestrator `txn-query.v1` 1.2.0: `?reveal=true` on the detail, for `reveal-clients` (mw-admin-api)
  only; MSISDN party ids whole, everything else stays masked (the query API masked everything, so an
  unmasked reveal needs it).
- Keycloak realm `ops` now carries the 15.4 roles (dev users viewer01, maker01, checker01, admin01,
  compliance01, auditor01); MFA stays the realm's (password + TOTP). APISIX route `admin-v1`.
- Checked here: `./gradlew test integrationTest` (mw-orchestrator), `./gradlew check` (mw-admin-api),
  `make test-scripts`, `make contracts-lint`, `make check-isolation M=mw-admin-api`, `make compose-check`.
  No e2e (ops tokens need TOTP enrolment; the e2e harness has no TOTP step yet).
