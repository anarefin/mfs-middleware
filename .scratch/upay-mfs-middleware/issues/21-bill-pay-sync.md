# 21: BILL_PAY sync path

**What to build:** A customer can fetch a bill and pay it. The aggregator simulator is added to `mw-simulators` (bill fetch, pay, status). `mw-adapter-billpay` is created from the template with the adapter framework copied from telco, consuming `mw.partner.request.billpay.v1`. The orchestrator adds `BillPayFlow` (hold → pay → commit/release, TSQ on unknown). The app gateway exposes `GET /billers` and `POST /billers/{code}/fetch-bill`, and accepts BILL_PAY. Spec WP12 (part) and WP15 (part).

**Blocked by:**

- 20: Adapter framework inside telco (prefactor)

**Status:** done

- [x] Each aggregator sim scenario can be reached by a test
- [x] Fetch-bill returns the amount due from the sim, and an unknown bill number gives a mapped `MW-PTR-*` error
- [x] e2e `@billpay`: success, biller failure (hold released), timeout → TSQ → commit
- [x] The billpay module passes `check-isolation`: the framework is a copy, not a dependency
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
