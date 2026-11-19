# 99: Business-duplicate check, replay nonces, stored responses, Retry-After (FRS 3.1, 3.2)

**What to build:** Close the edge and idempotency gaps found in the gap sweep:
- PLT-013 has no code: nothing stops the same customer sending the same amount to the same counterparty twice in a minute.
- PLT-008: the app has `X-Timestamp` + `X-Nonce` (`services/mw-app-gateway/.../security/DeviceBindingInterceptor.java`),
  but checkout HMAC (`deploy/apisix/routes/checkout-v1-sessions.json`) and partner callbacks
  (`partner-billagg-callback.json`, `partner-c2wbank-callback.json`) have no nonce/timestamp window.
- PLT-011: a same-key repeat is rebuilt from the live transaction row (`mw-app-gateway/.../service/TransactionService.java`),
  not from the stored original response, so the body can differ after a state change.
- PLT-004: 429 has no `Retry-After` (`deploy/apisix/lua/edge-error-headers.lua`) and the limit is a fixed window per route.

**Scope:**
- **Business duplicate (PLT-013)** in `mw-orchestrator` (`service/DuplicateGuard`): key = initiator + txnType +
  counterparty + amount, window configurable (default 60 s, Redis `SET NX EX`; Valkey until ticket 90 lands, same API).
  App/USSD: reply `MW-VAL-0xx DUPLICATE_SUSPECTED` with `retryable=false` and a `confirmDuplicate` flag the channel can
  resend (app: request field; USSD: an extra confirm node). API clients (checkout, portal, partner): reject.
- **Stored response (PLT-011)**: mw-app-gateway keeps the first final response body per idempotency key (hash + body,
  TTL = key TTL) and replays it byte-identical; a different body is still 409 `MW-IDEM-001`.
- **Replay (PLT-008)**: checkout merchant HMAC requests and partner callbacks carry timestamp + nonce; reject older than
  5 min or a reused nonce (Redis). Checkout: in `mw-checkout` `security/`; callbacks: in the adapters' inbound
  `partner/auth` HMAC scheme (all three copies identical). `services/mw-simulators/.../client/CallbackSigner.java` adds
  the headers.
- **Edge (PLT-004)**: `Retry-After` on every 429; APISIX `limit-req`/`limit-count` keyed by consumer + route so a limit is
  per client and API (client registry itself is ticket 100).
- Contracts: mw-checkout and app OpenAPI (headers, new error code), `deploy/apisix/README.md`.

**FRS IDs covered:** PLT-004, PLT-008, PLT-011, PLT-012 (unchanged, re-tested), PLT-013, PLT-014 (nonce part)

**Blocked by:** 89

**Status:** open

- [ ] `DuplicateGuardTest`: same tuple within window flagged; outside window or different amount passes; confirm flag passes
- [ ] `DuplicateGuardIT` (orchestrator): app duplicate gets the prompt code, checkout duplicate is rejected, no core call
- [ ] app-gateway `IdempotentReplayIT`: repeat after completion returns the stored body byte for byte; different body → 409
- [ ] checkout `HmacReplayTest` and adapter `CallbackReplayIT`: stale timestamp and reused nonce rejected, fresh accepted
- [ ] `scripts/edge-test.sh` case: 429 carries `Retry-After`; limits are per consumer
- [ ] `./gradlew :mw-orchestrator:build :mw-app-gateway:build :mw-checkout:build :mw-adapter-telco:build :mw-adapter-billpay:build :mw-adapter-bank:build :mw-simulators:build :contractsLint :scriptTests` green with Docker
- [ ] With the stack up: new API folder `36-duplicates-replay`; folders 01, 07, 08, 09 still green
