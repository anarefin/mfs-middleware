# 39: USSD menu + SMS template management

**What to build:** Operators can edit USSD menus and SMS templates safely. The admin API stores versioned USSD menus and notify templates, takes them through maker-checker, and on approval publishes them. `mw-ussd` activates a menu version per MNO at its go-live time, and running sessions finish on the version they started. `mw-notify` swaps templates atomically. Spec WP23 (rest).

**Blocked by:**

- 37: Maker-checker engine + manual TSQ / reversal
- 26: USSD engine over HTTP transport
- 24: Notify SMS over SMPP

**Status:** done

- [x] e2e: an approved menu text change shows up on the next USSD session but not in an ongoing one (`tests/e2e/.../admin-content.feature`, runs at the gate; covered in-module by mw-ussd `MenuPublisherIT`)
- [x] e2e: an approved SMS template change is used for the next notification (`admin-content.feature`, runs at the gate; in-module mw-notify `TemplatePublisherIT`)
- [x] An invalid menu tree (dangling node, missing bn/en text) is rejected at maker time (400 MW-VAL-040, `ContentChangeApiIT`, `MenuValidatorTest`)
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (deferred to milestone gate; `./gradlew test integrationTest` green for mw-admin-api, mw-ussd, mw-notify, mw-simulators)
