# 15: Simulators module + OCS simulator

**What to build:** `mw-simulators` hosts partner simulators, built to a generic contract per partner type (§22.2). This ticket delivers the module and the shared scenario mechanics: magic values (e.g. amount ending `.13` → timeout, MSISDN suffix `999` → business failure), a control API (`POST /sim/{partner}/scenario` for latency, error rate, outage, duplicate/late callback), request recording for assertions, and callback replay on demand. The first simulator is an OCS for prepaid top-up with a status-inquiry endpoint. It runs in the MVP Compose project (test only). Spec WP12 (part).

**Blocked by:**

- 06: Template part 2b: outbox, inbox, conformance suite

**Status:** ready-for-agent

- [ ] Each OCS scenario (success, business failure, timeout, 5xx-after-send, unknown code, malformed) can be reached by a test
- [ ] Status inquiry returns the real outcome of an earlier top-up, including one that timed out
- [ ] Recorded requests can be queried and reset through the control API
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
