# 26: USSD engine over HTTP transport

**What to build:** A customer on a feature phone can send P2P through USSD (§9.2). `mw-ussd` has a menu engine where menus are data: a versioned JSON tree in `ussd.menu` with node types MENU, INPUT (validators: msisdn, amount, PIN, numeric, regex), CONFIRM, ACTION, DYNAMIC and END, with bn/en text. Sessions are kept in Valkey (`ussd:{mno}:{sessionId}`, TTL = MNO timeout + 30 s). Behind the transport SPI, `HttpUssdTransport` (MNO POSTs each step to `/ussd/{mno}`) is wired through APISIX. A USSD gateway simulator in HTTP mode is added. The PIN is RSA-OAEP encrypted with the core public key on receipt and never stored. The idempotency key is derived as in §7.3.1. The 8 s sync budget ends the menu with "being processed". Spec WP18 (part) and WP19 (part).

**Blocked by:**

- 10: E2E harness + P2P scenarios
- 15: Simulators module + OCS simulator

**Status:** done

- [x] Menu-walk test: P2P from dial to confirmation, in bn and en
- [x] The Valkey session never holds the PIN (asserted by inspecting the session)
- [x] Validators reject bad MSISDN or amount and re-prompt
- [x] A screen longer than the MNO max length is truncated or paged per config
- [x] e2e `@ussd`: P2P over the HTTP sim completes and balances move (green at the milestone gate, below)
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (green at the milestone gate, below)

## Milestone gate (2026-11-05, integration branch `spec/upay-mfs-middleware`)

- `make verify` (all 13 modules, at 9b73ac2 after the review fixes; later gate fixes verified per module): `==> verify passed: 13 module(s)`, exit 0
- Fresh stack at fcd51ef (`make down DOWN_ARGS=-v && make up`):
  - `make smoke`: `smoke: 46 passed, 0 failed`
  - `make edge-test`: `edge-test: 42 passed, 0 failed`
  - `make e2e` (full suite): `110 scenarios, 0 failed, 0 skipped`, `BUILD SUCCESSFUL in 21m 10s`
- Final re-run after the masking fixes, fresh stack at bfd889b: `edge-test: 43 passed, 0 failed`; full `make e2e` 110 scenarios, 0 failed (`BUILD SUCCESSFUL in 21m 32s`). No full `make verify` after 9b73ac2 (user decision): the later fixes ran their targeted tests.
- Gate fixes and user decisions: see the merge commits after 70f7add and memory `upay-m3-decisions`.
