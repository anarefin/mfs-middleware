# D17: A dev/test-only test console for manual testing

Status: Accepted, 2026-11-07.

## Context

The middleware has no customer-facing UI: the mobile app, the USSD handsets, the merchants' web
shops, the banks, MNOs and billers are all outside it, and on the laptop stack the partners and the
core are simulators. Every feature could be checked only by the Newman suite (`make api-test`) or by
hand-written API calls, which need the device signature (EC P-256), RSA-OAEP PIN encryption, Keycloak
tokens, hmac-auth and the test-only key. Testers without that knowledge could not check a flow by
hand, see each step, or switch a partner to fail. The ops console covers only the operations side.

A browser page cannot call the APIs itself: APISIX grants no CORS, the test-only routes need
`MW_E2E_API_KEY`, and the checkout merchant's hmac-auth needs its secret and a `Date` header that
`fetch` may not set.

## Decision

- A separate UI module, `ui/mw-test-console` (Next.js, the admin console's stack), served by APISIX
  at `/tester`. It plays the app (customer, agent, merchant), a USSD handset (robi over HTTP, gp
  over SMPP through the USSD gateway simulator), the partner simulators and core-mock faults, the
  checkout shop and buyer, the org portal, and the SMS / email inboxes. Each action shows every API
  call it made, the final status, the core balances before and after, and the SMS received.
- Every call goes through APISIX, server-side (server actions), with the stack's generated test
  credentials from `deploy/.env`; no token, key or secret reaches the browser. The client
  cryptography is a port of `tests/api/lib.js` on Node's crypto.
- Dev/test only: compose profile `tester` (outside the core profile and its memory budget), and its
  APISIX route is a test-only route (`deploy/apisix/routes-test/test-console.json`, private source
  addresses), present only while `apisix-init` ran with `MW_E2E_ROUTES=1` (`make tester`); a plain
  `make up` removes it. It has no sign-in.
- Ops features are not repeated: they stay in the admin console; the test console links each
  transaction to it. `docs/testing-guide.html` walks through every feature with both.

## Consequences

- A tester checks every MVP flow, its failure paths (timeouts, TSQ, UNKNOWN, MANUAL_REVIEW,
  callbacks) and the ops actions from a browser, with no API tooling.
- One more UI module to keep working: its own `pnpm test` (no stack) and `make tester-e2e`
  (Playwright against the running stack). It follows API changes only through the contracts it
  calls, like any client.
- It impersonates customers and holds test credentials: it must never be started on a shared or
  production host. The profile, the test-only route and the README / runbook notes say so.
- Binding a device in the console replaces that user's device from an API suite run (one active
  device per wallet); the suite binds again on its next run.
