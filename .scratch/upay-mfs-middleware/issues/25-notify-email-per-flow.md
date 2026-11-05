# 25: Notify email + per-flow notification checks

**What to build:** Notifications cover email as well as SMS, and every flow built so far sends the right messages. `mw-notify` sends email through SMTP to GreenMail (HTML + text templates). Rules and templates are seeded for P2P, CASH_IN, CASH_OUT, TOPUP, BILL_PAY and ADD_MONEY_BANK (success, failure, processing). Spec WP17 (rest).

**Blocked by:**

- 24: Notify SMS over SMPP

**Status:** done

- [x] Integration test: email reaches GreenMail with rendered subject and body (`EmailFlowIT`, in-process GreenMail; per-flow seeds in `FlowNotificationsIT`)
- [x] e2e: for each flow above, the expected SMS (and email where configured) is asserted (green at the milestone gate, below)
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (green at the milestone gate, below)

## Milestone gate (2026-11-05, integration branch `spec/upay-mfs-middleware`)

- `make verify` (all 13 modules, at 9b73ac2 after the review fixes; later gate fixes verified per module): `==> verify passed: 13 module(s)`, exit 0
- Fresh stack at fcd51ef (`make down DOWN_ARGS=-v && make up`):
  - `make smoke`: `smoke: 46 passed, 0 failed`
  - `make edge-test`: `edge-test: 42 passed, 0 failed`
  - `make e2e` (full suite): `110 scenarios, 0 failed, 0 skipped`, `BUILD SUCCESSFUL in 21m 10s`
- Gate fixes and user decisions: see the merge commits after 70f7add and memory `upay-m3-decisions`.
