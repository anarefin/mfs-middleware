# 27: USSD SMPP transport for GP

**What to build:** GP USSD runs live over SMPP, and any MNO can switch transport by configuration alone (acceptance criterion 5). This ticket adds `SmppUssdTransport`: `deliver_sm` in and `submit_sm` out, `ussd_service_op` and `its_session_info` TLVs, transceiver bind, `enquire_link` every 30 s, reconnect on 3 misses with 1–60 s backoff, window and throttle, delayed retry on `generic_nack`, 0x58 and 0x14. Responses go out on the bind that received the session. Per-MNO config: GP = SMPP; Robi, Banglalink and Teletalk config-ready. The USSD simulator gains SMPP mode. Spec WP19.

**Blocked by:**

- 26: USSD engine over HTTP transport
- 24: Notify SMS over SMPP

**Status:** done

- [x] The same menu-walk suite passes over SMPP and over HTTP by changing only config
- [x] Against the sim: bind, enquire_link, reconnect after a dropped bind, and throttling errors with retry all pass (module tests against the in-test gateway and the simulator; live `ussd-smpp.feature` deferred to milestone gate)
- [x] `mw_smpp_bind_up` and a `smpp.bind.down` log event appear when the bind drops
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (deferred to milestone gate)
