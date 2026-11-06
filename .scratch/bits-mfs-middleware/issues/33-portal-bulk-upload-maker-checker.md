# 33: Portal bulk upload + maker-checker

**What to build:** An org can upload a bulk disbursement file and have it approved by a second user (§9.4). `mw-portal-api` (realm `portals`, through APISIX) accepts a CSV of up to 10,000 rows and rejects anything larger. It validates rows (MSISDN, amount, reference), stores the batch in `bulk.batch` and `bulk.batch_item`, and needs approval by a different org user with the checker role. Spec WP22 (part).

**Blocked by:**

- 10: E2E harness + P2P scenarios
- 13: APISIX edge policies

**Status:** done

- [x] 10,001 rows → rejected with `MW-VAL-*`; malformed rows are reported per line
- [x] The maker cannot approve their own batch
- [x] API tests for upload → validate → approve and reject
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (green at the milestone gate, below)

**Implementation notes (ticket 33):**

- New module `services/mw-portal-api` (schema `bulk`, role `portal_api_rw`), API contract
  `contracts/api/mw-portal-api/portal-org.v1.openapi.yaml`, APISIX routes `portal-org-v1` and
  `auth-portals-token`, realm `portals`: `orgId` attribute + mapper, users org-makerchecker01
  (both roles) and org-checker02 (ORG002).
- `./gradlew test` (160 tests, incl. `BulkBatchApiIT` upload -> validate -> approve/reject on
  PostgreSQL), `make contracts-lint`, `make test-scripts` green. e2e `features/bulk-upload.feature`
  (`@bulk @mw-portal-api`) and two `make edge-test` cases written; green at the
  milestone gate (below). An existing stack needs `make down DOWN_ARGS=-v` for the realm changes.

## Milestone gate (2026-11-05, integration branch `spec/bits-mfs-middleware`)

- `make verify` (all 13 modules, at 9b73ac2 after the review fixes; later gate fixes verified per module): `==> verify passed: 13 module(s)`, exit 0
- Fresh stack at fcd51ef (`make down DOWN_ARGS=-v && make up`):
  - `make smoke`: `smoke: 46 passed, 0 failed`
  - `make edge-test`: `edge-test: 42 passed, 0 failed`
  - `make e2e` (full suite): `110 scenarios, 0 failed, 0 skipped`, `BUILD SUCCESSFUL in 21m 10s`
- Final re-run after the masking fixes, fresh stack at bfd889b: `edge-test: 43 passed, 0 failed`; full `make e2e` 110 scenarios, 0 failed (`BUILD SUCCESSFUL in 21m 32s`). No full `make verify` after 9b73ac2 (user decision): the later fixes ran their targeted tests.
- Gate fixes and user decisions: see the merge commits after 70f7add and memory `bits-m3-decisions`.
