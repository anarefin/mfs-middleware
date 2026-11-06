# 16: Thin telco adapter

**What to build:** `mw-adapter-telco` turns a canonical top-up request into an OCS call and a canonical reply. It consumes `mw.partner.request.telco.v1`, maps the request to the OCS simulator's REST/JSON contract, and sends a stable partner reference derived from txnId (§7.3.5). The call runs behind Resilience4j (bulkhead, circuit breaker, time limiter, 20 s default). It publishes `mw.partner.reply.v1` with SUCCESS, FAILED (with the mapped `MW-PTR-*` code) or UNKNOWN (timeout, reset after send, 5xx after send, ambiguous code). Config is static for now; the adapter framework comes in ticket 20. Spec WP14 (part).

**Blocked by:**

- 15: Simulators module + OCS simulator

**Status:** done

- [x] Route tests against WireMock: each outcome maps to the right reply status and code
- [x] Circuit opens after the configured failure rate and replies FAILED/UNKNOWN fast while open
- [x] A duplicate request for the same txnId reuses the same partner reference
- [x] No automatic retry of the non-idempotent top-up call
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
