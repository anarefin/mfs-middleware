# 33: Portal bulk upload + maker-checker

**What to build:** An org can upload a bulk disbursement file and have it approved by a second user (§9.4). `mw-portal-api` (realm `portals`, through APISIX) accepts a CSV of up to 10,000 rows and rejects anything larger. It validates rows (MSISDN, amount, reference), stores the batch in `bulk.batch` and `bulk.batch_item`, and needs approval by a different org user with the checker role. Spec WP22 (part).

**Blocked by:**

- 10: E2E harness + P2P scenarios
- 13: APISIX edge policies

**Status:** done

- [x] 10,001 rows → rejected with `MW-VAL-*`; malformed rows are reported per line
- [x] The maker cannot approve their own batch
- [x] API tests for upload → validate → approve and reject
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (deferred to milestone gate)

**Implementation notes (ticket 33):**

- New module `services/mw-portal-api` (schema `bulk`, role `portal_api_rw`), API contract
  `contracts/api/mw-portal-api/portal-org.v1.openapi.yaml`, APISIX routes `portal-org-v1` and
  `auth-portals-token`, realm `portals`: `orgId` attribute + mapper, users org-makerchecker01
  (both roles) and org-checker02 (ORG002).
- `./gradlew test` (160 tests, incl. `BulkBatchApiIT` upload -> validate -> approve/reject on
  PostgreSQL), `make contracts-lint`, `make test-scripts` green. e2e `features/bulk-upload.feature`
  (`@bulk @mw-portal-api`) and two `make edge-test` cases written, not run live (deferred to
  milestone gate). An existing stack needs `make down DOWN_ARGS=-v` for the realm changes.
