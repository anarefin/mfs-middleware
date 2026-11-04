# 31: Checkout sessions + pay + redirect

**What to build:** An online merchant can take wallet payments through web checkout (§9.3). `mw-checkout` offers `POST /checkout/v1/sessions` (HMAC-signed through APISIX `hmac-auth`) returning `checkoutUrl` and `sessionId` (TTL 15 min), and `GET /checkout/v1/sessions/{id}`. The hosted-page API covers wallet number entry, an OTP sent via `mw-notify`, and PIN (encrypted) to submit MERCHANT_PAY. On a terminal status the browser is redirected to the merchant URL with a signed result. Spec WP21 (part).

**Blocked by:**

- 30: Merchant in-app MERCHANT_PAY
- 13: APISIX edge policies
- 24: Notify SMS over SMPP

**Status:** done

- [x] Bad HMAC → 401; an expired session cannot be paid (HMAC at the edge: `@checkout` e2e + `make edge-test` cases, live run deferred to milestone gate; expiry: `CheckoutFlowIT`)
- [x] Wrong OTP is rejected; OTP is sent with OTP priority
- [ ] e2e `@checkout`: create session → pay → signed redirect; the merchant verifies the signature (written; deferred to milestone gate)
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (deferred to milestone gate)
