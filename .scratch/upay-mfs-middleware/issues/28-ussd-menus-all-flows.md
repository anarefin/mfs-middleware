# 28: USSD menus for all MVP flows

**What to build:** USSD users can reach every MVP flow that suits the channel. Menus are added for customer CASH_OUT (to agent), TOPUP (own and other number), and BILL_PAY with a DYNAMIC biller list and bill fetch. Spec WP18 (rest).

**Blocked by:**

- 26: USSD engine over HTTP transport
- 11: Agent CASH_IN / CASH_OUT end to end
- 17: TOPUP end to end (hold → partner → commit / release)
- 21: BILL_PAY sync path

**Status:** done

- [x] A menu-walk test for every new path, in bn and en
- [x] e2e `@ussd`: top-up and bill pay complete over USSD (green at the milestone gate, below)
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (green at the milestone gate, below)

## Milestone gate (2026-11-05, integration branch `spec/upay-mfs-middleware`)

- `make verify` (all 13 modules, at 9b73ac2 after the review fixes; later gate fixes verified per module): `==> verify passed: 13 module(s)`, exit 0
- Fresh stack at fcd51ef (`make down DOWN_ARGS=-v && make up`):
  - `make smoke`: `smoke: 46 passed, 0 failed`
  - `make edge-test`: `edge-test: 42 passed, 0 failed`
  - `make e2e` (full suite): `110 scenarios, 0 failed, 0 skipped`, `BUILD SUCCESSFUL in 21m 10s`
- Final re-run after the masking fixes, fresh stack at bfd889b: `edge-test: 43 passed, 0 failed`; full `make e2e` 110 scenarios, 0 failed (`BUILD SUCCESSFUL in 21m 32s`). No full `make verify` after 9b73ac2 (user decision): the later fixes ran their targeted tests.
- Gate fixes and user decisions: see the merge commits after 70f7add and memory `upay-m3-decisions`.
