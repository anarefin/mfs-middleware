# 39: USSD menu + SMS template management

**What to build:** Operators can edit USSD menus and SMS templates safely. The admin API stores versioned USSD menus and notify templates, takes them through maker-checker, and on approval publishes them. `mw-ussd` activates a menu version per MNO at its go-live time, and running sessions finish on the version they started. `mw-notify` swaps templates atomically. Spec WP23 (rest).

**Blocked by:**

- 37: Maker-checker engine + manual TSQ / reversal
- 26: USSD engine over HTTP transport
- 24: Notify SMS over SMPP

**Status:** ready-for-agent

- [ ] e2e: an approved menu text change shows up on the next USSD session but not in an ongoing one
- [ ] e2e: an approved SMS template change is used for the next notification
- [ ] An invalid menu tree (dangling node, missing bn/en text) is rejected at maker time
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
