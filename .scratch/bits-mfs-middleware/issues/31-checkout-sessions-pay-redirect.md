# 31: Checkout sessions + pay + redirect

**What to build:** An online merchant can take wallet payments through web checkout (§9.3). `mw-checkout` offers `POST /checkout/v1/sessions` (HMAC-signed through APISIX `hmac-auth`) returning `checkoutUrl` and `sessionId` (TTL 15 min), and `GET /checkout/v1/sessions/{id}`. The hosted-page API covers wallet number entry, an OTP sent via `mw-notify`, and PIN (encrypted) to submit MERCHANT_PAY. On a terminal status the browser is redirected to the merchant URL with a signed result. Spec WP21 (part).

**Blocked by:**

- 30: Merchant in-app MERCHANT_PAY
- 13: APISIX edge policies
- 24: Notify SMS over SMPP

**Status:** done

- [x] Bad HMAC → 401; an expired session cannot be paid (HMAC at the edge: `@checkout` e2e + `make edge-test` cases, live run green at the milestone gate; expiry: `CheckoutFlowIT`)
- [x] Wrong OTP is rejected; OTP is sent with OTP priority
- [x] e2e `@checkout`: create session → pay → signed redirect; the merchant verifies the signature (green at the milestone gate, below)
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (green at the milestone gate, below)

## Milestone gate (2026-11-05, integration branch `spec/bits-mfs-middleware`)

- `make verify` (all 13 modules, at 9b73ac2 after the review fixes; later gate fixes verified per module): `==> verify passed: 13 module(s)`, exit 0
- Fresh stack at fcd51ef (`make down DOWN_ARGS=-v && make up`):
  - `make smoke`: `smoke: 46 passed, 0 failed`
  - `make edge-test`: `edge-test: 42 passed, 0 failed`
  - `make e2e` (full suite): `110 scenarios, 0 failed, 0 skipped`, `BUILD SUCCESSFUL in 21m 10s`
- Final re-run after the masking fixes, fresh stack at bfd889b: `edge-test: 43 passed, 0 failed`; full `make e2e` 110 scenarios, 0 failed (`BUILD SUCCESSFUL in 21m 32s`). No full `make verify` after 9b73ac2 (user decision): the later fixes ran their targeted tests.
- Gate fixes and user decisions: see the merge commits after 70f7add and memory `bits-m3-decisions`.
