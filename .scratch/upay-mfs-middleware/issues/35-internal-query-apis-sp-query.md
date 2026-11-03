# 35: Internal query APIs + SP txn query

**What to build:** Operators and service providers can look up transactions. `mw-orchestrator` exposes an internal query API: search by txnId, by MSISDN via the keyed hash (`initiator_hash`), by partner ref, status and date. It also returns the saga timeline (states, steps, partner calls). `mw-audit-dw` exposes an internal audit search API. `mw-portal-api` adds the SP transaction query for its own service provider. All three APIs are internal only, not routed on the public edge. Spec WP22 (rest) and WP23 (part).

**Blocked by:**

- 12: Audit trail
- 34: Bulk execution + result file

**Status:** ready-for-agent

- [ ] The MSISDN search never needs the raw MSISDN indexed; results are masked
- [ ] The timeline lists every state change in order for a TSQ'd top-up
- [ ] An SP sees only its own transactions
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
