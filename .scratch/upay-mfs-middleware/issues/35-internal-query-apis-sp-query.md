# 35: Internal query APIs + SP txn query

**What to build:** Operators and service providers can look up transactions. `mw-orchestrator` exposes an internal query API: search by txnId, by MSISDN via the keyed hash (`initiator_hash`), by partner ref, status and date. It also returns the saga timeline (states, steps, partner calls). `mw-audit-dw` exposes an internal audit search API. `mw-portal-api` adds the SP transaction query for its own service provider. All three APIs are internal only, not routed on the public edge. Spec WP22 (rest) and WP23 (part).

**Blocked by:**

- 12: Audit trail
- 34: Bulk execution + result file

**Status:** done

- [x] The MSISDN search never needs the raw MSISDN indexed; results are masked
- [x] The timeline lists every state change in order for a TSQ'd top-up
- [x] An SP sees only its own transactions
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (green at the milestone gate, below)

**Notes (implementation):**

- e2e `tests/e2e/src/test/resources/features/sp-query.feature` (`@sp-query @mw-portal-api`) written; green at the milestone gate.
- Audit search has no MSISDN filter (events carry no keyed hash): find the txn by MSISDN on the orchestrator query API, then its audit by txnId. Documented in mw-audit-dw's CLAUDE.md and the contract.
- `initiator_hash` is nullable: rows from before V8 have no hash (dev data only).
- New contracts: `api/mw-orchestrator/txn-query.v1.openapi.yaml`, `api/mw-audit-dw/audit-query.v1.openapi.yaml`; portal paths added to `api/mw-portal-api/portal-org.v1.openapi.yaml`. No new APISIX routes.

## Milestone gate (2026-11-05, integration branch `spec/upay-mfs-middleware`)

- `make verify` (all 13 modules, at 9b73ac2 after the review fixes; later gate fixes verified per module): `==> verify passed: 13 module(s)`, exit 0
- Fresh stack at fcd51ef (`make down DOWN_ARGS=-v && make up`):
  - `make smoke`: `smoke: 46 passed, 0 failed`
  - `make edge-test`: `edge-test: 42 passed, 0 failed`
  - `make e2e` (full suite): `110 scenarios, 0 failed, 0 skipped`, `BUILD SUCCESSFUL in 21m 10s`
- Gate fixes and user decisions: see the merge commits after 70f7add and memory `upay-m3-decisions`.
