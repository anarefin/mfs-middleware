# 32: Checkout signed IPN with retries

**What to build:** Merchants reliably learn the outcome server-to-server. After a terminal status, `mw-checkout` posts an HMAC-SHA256 signed IPN to the merchant's IPN URL, retrying at 1 min, 5 min, 30 min, 2 h and 6 h until a 2xx. Every attempt is recorded in `checkout.ipn_delivery`. Spec WP21 (rest).

**Blocked by:**

- 31: Checkout sessions + pay + redirect

**Status:** done

- [x] Unit test of the retry schedule (with a test clock)
- [x] e2e `@checkout`: the merchant endpoint fails twice and then succeeds; the IPN is delivered once with a valid signature (green at the milestone gate, below)
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (green at the milestone gate, below)

## Milestone gate (2026-11-05, integration branch `spec/bits-mfs-middleware`)

- `make verify` (all 13 modules, at 9b73ac2 after the review fixes; later gate fixes verified per module): `==> verify passed: 13 module(s)`, exit 0
- Fresh stack at fcd51ef (`make down DOWN_ARGS=-v && make up`):
  - `make smoke`: `smoke: 46 passed, 0 failed`
  - `make edge-test`: `edge-test: 42 passed, 0 failed`
  - `make e2e` (full suite): `110 scenarios, 0 failed, 0 skipped`, `BUILD SUCCESSFUL in 21m 10s`
- Final re-run after the masking fixes, fresh stack at bfd889b: `edge-test: 43 passed, 0 failed`; full `make e2e` 110 scenarios, 0 failed (`BUILD SUCCESSFUL in 21m 32s`). No full `make verify` after 9b73ac2 (user decision): the later fixes ran their targeted tests.
- Gate fixes and user decisions: see the merge commits after 70f7add and memory `bits-m3-decisions`.
