# 26: USSD engine over HTTP transport

**What to build:** A customer on a feature phone can send P2P through USSD (§9.2). `mw-ussd` has a menu engine where menus are data: a versioned JSON tree in `ussd.menu` with node types MENU, INPUT (validators: msisdn, amount, PIN, numeric, regex), CONFIRM, ACTION, DYNAMIC and END, with bn/en text. Sessions are kept in Valkey (`ussd:{mno}:{sessionId}`, TTL = MNO timeout + 30 s). Behind the transport SPI, `HttpUssdTransport` (MNO POSTs each step to `/ussd/{mno}`) is wired through APISIX. A USSD gateway simulator in HTTP mode is added. The PIN is RSA-OAEP encrypted with the core public key on receipt and never stored. The idempotency key is derived as in §7.3.1. The 8 s sync budget ends the menu with "being processed". Spec WP18 (part) and WP19 (part).

**Blocked by:**

- 10: E2E harness + P2P scenarios
- 15: Simulators module + OCS simulator

**Status:** ready-for-agent

- [ ] Menu-walk test: P2P from dial to confirmation, in bn and en
- [ ] The Valkey session never holds the PIN (asserted by inspecting the session)
- [ ] Validators reject bad MSISDN or amount and re-prompt
- [ ] A screen longer than the MNO max length is truncated or paged per config
- [ ] e2e `@ussd`: P2P over the HTTP sim completes and balances move
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
