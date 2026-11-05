# 30: Merchant in-app MERCHANT_PAY

**What to build:** A customer can pay a merchant in the app, and the merchant can see collections. The orchestrator adds the `MERCHANT_PAY` flow. The app gateway adds the merchant role, merchant pay from the customer app, and a collections view for merchants. Spec WP20 (rest).

**Blocked by:**

- 11: Agent CASH_IN / CASH_OUT end to end

**Status:** done

- [x] Role tests: only the merchant sees their own collections
- [x] e2e `@merchant`: in-app merchant payment success and rejection, balances asserted (green at the milestone gate, below)
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (green at the milestone gate, below)

## Milestone gate (2026-11-05, integration branch `spec/upay-mfs-middleware`)

- `make verify` (all 13 modules, at 9b73ac2 after the review fixes; later gate fixes verified per module): `==> verify passed: 13 module(s)`, exit 0
- Fresh stack at fcd51ef (`make down DOWN_ARGS=-v && make up`):
  - `make smoke`: `smoke: 46 passed, 0 failed`
  - `make edge-test`: `edge-test: 42 passed, 0 failed`
  - `make e2e` (full suite): `110 scenarios, 0 failed, 0 skipped`, `BUILD SUCCESSFUL in 21m 10s`
- Gate fixes and user decisions: see the merge commits after 70f7add and memory `upay-m3-decisions`.
