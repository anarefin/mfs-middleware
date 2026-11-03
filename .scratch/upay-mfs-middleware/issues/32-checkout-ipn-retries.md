# 32: Checkout signed IPN with retries

**What to build:** Merchants reliably learn the outcome server-to-server. After a terminal status, `mw-checkout` posts an HMAC-SHA256 signed IPN to the merchant's IPN URL, retrying at 1 min, 5 min, 30 min, 2 h and 6 h until a 2xx. Every attempt is recorded in `checkout.ipn_delivery`. Spec WP21 (rest).

**Blocked by:**

- 31: Checkout sessions + pay + redirect

**Status:** ready-for-agent

- [ ] Unit test of the retry schedule (with a test clock)
- [ ] e2e `@checkout`: the merchant endpoint fails twice and then succeeds; the IPN is delivered once with a valid signature
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
