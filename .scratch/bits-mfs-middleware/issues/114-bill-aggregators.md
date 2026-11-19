# 114: Four bill aggregators (SSLCommerz, eKpay, agg3, agg4) and stateful utility bills (FRS UC-09, UC-10, EXT-09, EXT-10)

**What to build:** Bring the four "other biller" aggregators live as route-as-config partners in `mw-adapter-billpay`
(ticket 82 engine). Add the configuration only: no new Java per partner (CON-02). Each aggregator gets its own
payload style and auth. Give the existing utility aggregator (`billagg`) seeded, stateful bills, so a paid bill stays
paid, and add REVERSE (ticket 97). Failover between aggregators comes from the catalogue's fallback (113) and the
routing module (98).

**Scope:**
- Route records (`deploy/partner-routes/` + admin-api dev seeds) for `sslcommerz` (REST JSON, api-key + HMAC),
  `ekpay` (REST JSON, oauth2-cc), `agg3` (SOAP via the `mw-soap-call` Kamelet, WS-Security), and `agg4` (REST
  form-encoded or XML, basic). Each defines INQUIRY, PAY, STATUS_QUERY, REVERSE and an inbound payment-status
  CALLBACK, with its own `errorMap`.
- `services/mw-simulators`: one `BillAggregatorMockController` with four profiles (`/sim/agg/{profile}/…` plus
  `/mock-control/{profile}`). Seeded bills come from the versioned file `seeds/bills/<profile>.csv` (MCK-08). Bills
  are stateful (unpaid → paid → reversed) and survive a scenario reset only through `/mock-control/reset`.
  Callbacks can be ONCE, DUPLICATE, LATE or OUT_OF_ORDER (MCK-06).
- `billagg`: move from bills computed from the bill number to seeded bills per biller, and add a reverse operation.
- Failover: when the primary aggregator's circuit is open or the partner is DOWN, routing picks the catalogue
  fallback. Only INQUIRY is retried on the fallback; a PAY already sent is never re-sent elsewhere (PLT-031).
- Docs: `services/mw-adapter-billpay/CLAUDE.md` partner list, simulator trigger table per profile.

**FRS IDs covered:** UC-09, UC-10, EXT-09, EXT-10, CON-02, MCK-02, MCK-06, MCK-08, PLT-032 (failover), T8

**Blocked by:** 113, 97, 102

**Status:** open

- [ ] Camel route tests for each of the four records (mapping, auth header or SOAP header, error mapping)
- [ ] `NewBillerRouteIT`-style test: a fifth aggregator added by config alone works (no code change)
- [ ] A paid bill fetched again shows paid; a second PAY on it is refused by the mock and mapped to `MW-PTR-*`
- [ ] T1–T12 for BILL_PAY against `sslcommerz` and `agg3` (SOAP), including T7 (a duplicate callback is processed once) and T8 (fallback aggregator used)
- [ ] Reverse of a completed bill payment is idempotent
- [ ] `./gradlew :mw-adapter-billpay:build :mw-simulators:build :mw-admin-api:build :contractsLint` green with Docker
- [ ] API suite folder `48-bill-aggregators` passes; `06-billpay`, `07-billpay-callback` still pass
