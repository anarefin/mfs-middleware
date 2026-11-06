# 09: App gateway P2P + minimal APISIX route

**What to build:** A customer app can send a P2P transfer through APISIX and get an answer in one request. `mw-app-gateway` validates the Keycloak JWT (realm `customers`), requires `Idempotency-Key`, mints a UUIDv7 `txnId`, normalises the request to the canonical envelope (§5.1), passes the PIN ciphertext through untouched, and publishes `mw.txn.request.v1`. It then waits on `mw.txn.status.v1` for that txnId: a per-instance reply consumer filtered by the `mw-reply-to` header, with a 25 s budget (§7.5). On a terminal status it returns the result; when the budget runs out it returns `202 PROCESSING`. `GET /app/v1/transactions/{txnId}` returns the current status. APISIX gets one declarative route `/app/v1/*` with `openid-connect`. Spec WP08.

**Blocked by:**

- 08: Orchestrator P2P slice
- 02: Platform Compose stack

**Status:** done

- [x] `@WebMvcTest`: a missing or invalid `Idempotency-Key` gives `MW-VAL-*`, and an amount ≤ 0 is rejected
- [x] Integration test: a terminal status arriving within the budget returns 200 with the result; no reply returns 202 with txnId
- [x] Kafka is unavailable → retryable `503 MW-SYS-*` and nothing is accepted
- [x] Manual or scripted check: `curl` through APISIX with a Keycloak token completes a P2P transfer against core-mock (manual live run by implementer; automated in ticket 10)
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
