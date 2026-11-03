# 33: Portal bulk upload + maker-checker

**What to build:** An org can upload a bulk disbursement file and have it approved by a second user (§9.4). `mw-portal-api` (realm `portals`, through APISIX) accepts a CSV of up to 10,000 rows and rejects anything larger. It validates rows (MSISDN, amount, reference), stores the batch in `bulk.batch` and `bulk.batch_item`, and needs approval by a different org user with the checker role. Spec WP22 (part).

**Blocked by:**

- 10: E2E harness + P2P scenarios
- 13: APISIX edge policies

**Status:** ready-for-agent

- [ ] 10,001 rows → rejected with `MW-VAL-*`; malformed rows are reported per line
- [ ] The maker cannot approve their own batch
- [ ] API tests for upload → validate → approve and reject
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
