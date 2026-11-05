# 24: Notify SMS over SMPP

**What to build:** Customers get SMS for their transactions (§10). An SMSC simulator (SMPP 3.4 server, DLR generation) is added to `mw-simulators`. `mw-notify` consumes `mw.notify.request.v1` and builds requests from rules on `mw.txn.status.v1` (rules in `notify.rule`). It renders bn/en Mustache-style templates from `notify.template` with masked params, and sends over SMPP: GSM-7 for English, UCS-2 for Bangla, long SMS by UDH. It tracks DLRs to DELIVERED, UNDELIVERABLE or EXPIRED and publishes `mw.notify.status.v1`. `messageId` is the idempotency key, OTP > TXN > INFO > PROMO priority is honoured, and expired messages are not sent. Spec WP17 (part).

**Blocked by:**

- 10: E2E harness + P2P scenarios
- 15: Simulators module + OCS simulator

**Status:** done

- [x] Unit tests: template rendering bn/en, masking of params, encoding choice, segment count
- [x] Integration test against the SMSC sim: submit, DLR → DELIVERED, transient error → retry 2/10/30 s (mw-notify `SmscFlowIT` over real SMPP to its own in-test jSMPP SMSC, as module isolation forbids starting mw-simulators; the 2/10/30 s schedule pinned in `NotificationFlowIT`; the mw-simulators SMSC itself in `SmscSimulatorIT`)
- [x] A duplicate `messageId` is dropped; an expired message is recorded as EXPIRED
- [x] e2e `@p2p @notify`: sender and receiver get the right SMS text (green at the milestone gate, below)
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (green at the milestone gate, below)

## Milestone gate (2026-11-05, integration branch `spec/upay-mfs-middleware`)

- `make verify` (all 13 modules, at 9b73ac2 after the review fixes; later gate fixes verified per module): `==> verify passed: 13 module(s)`, exit 0
- Fresh stack at fcd51ef (`make down DOWN_ARGS=-v && make up`):
  - `make smoke`: `smoke: 46 passed, 0 failed`
  - `make edge-test`: `edge-test: 42 passed, 0 failed`
  - `make e2e` (full suite): `110 scenarios, 0 failed, 0 skipped`, `BUILD SUCCESSFUL in 21m 10s`
- Gate fixes and user decisions: see the merge commits after 70f7add and memory `upay-m3-decisions`.
