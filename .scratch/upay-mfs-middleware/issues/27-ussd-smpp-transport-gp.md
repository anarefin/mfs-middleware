# 27: USSD SMPP transport for GP

**What to build:** GP USSD runs live over SMPP, and any MNO can switch transport by configuration alone (acceptance criterion 5). This ticket adds `SmppUssdTransport`: `deliver_sm` in and `submit_sm` out, `ussd_service_op` and `its_session_info` TLVs, transceiver bind, `enquire_link` every 30 s, reconnect on 3 misses with 1–60 s backoff, window and throttle, delayed retry on `generic_nack`, 0x58 and 0x14. Responses go out on the bind that received the session. Per-MNO config: GP = SMPP; Robi, Banglalink and Teletalk config-ready. The USSD simulator gains SMPP mode. Spec WP19.

**Blocked by:**

- 26: USSD engine over HTTP transport
- 24: Notify SMS over SMPP

**Status:** done

- [x] The same menu-walk suite passes over SMPP and over HTTP by changing only config
- [x] Against the sim: bind, enquire_link, reconnect after a dropped bind, and throttling errors with retry all pass (module tests against the in-test gateway and the simulator; live `ussd-smpp.feature` green at the milestone gate)
- [x] `mw_smpp_bind_up` and a `smpp.bind.down` log event appear when the bind drops
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (green at the milestone gate, below)

## Milestone gate (2026-11-05, integration branch `spec/upay-mfs-middleware`)

- `make verify` (all 13 modules, at 9b73ac2 after the review fixes; later gate fixes verified per module): `==> verify passed: 13 module(s)`, exit 0
- Fresh stack at fcd51ef (`make down DOWN_ARGS=-v && make up`):
  - `make smoke`: `smoke: 46 passed, 0 failed`
  - `make edge-test`: `edge-test: 42 passed, 0 failed`
  - `make e2e` (full suite): `110 scenarios, 0 failed, 0 skipped`, `BUILD SUCCESSFUL in 21m 10s`
- Gate fixes and user decisions: see the merge commits after 70f7add and memory `upay-m3-decisions`.
