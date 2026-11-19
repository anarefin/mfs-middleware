# 116: mw-checkout: refunds (full and partial), list by date, 24 h IPN (FRS CH-WEB-03, CH-WEB-04, FL-06, UC-12)

**What to build:** Merchant refund APIs on web checkout: full and partial. The refunded total can't exceed the
original amount (FL-06), and money returns to the original wallet. Refunds run through a new partial-reversal
capability of the REST core (ticket 93/95). Also add a merchant transaction list by date, and stretch the IPN retry
schedule to cover 24 h. Today it covers about 8.6 h.

**Principles:**
- A refund is its own transaction (`txnType=REVERSAL`, `attributes.originalTxnId`, `attributes.refundOf`) with its
  own idempotency key, never an in-place change of the original.
- The orchestrator's reversal is no longer only `SYSTEM`/full: a merchant-channel partial reversal of a COMPLETED
  MERCHANT_PAY is allowed, while the admin full reversal keeps its maker-checker.

**Scope:**
- Core-mock (REST, ticket 93): `POST /reversals` accepts `amount` (partial). It keeps a refunded total per original
  txnId and rejects an amount over the remainder (`MW-CORE-REFUND_EXCEEDS`). It is idempotent by refund txnId.
- Orchestrator: a `RefundFlow` (or a REVERSAL flow variant) that is authorised for channel `WEB_CHECKOUT` from the
  owning merchant only.
- `mw-checkout`:
  - `POST /checkout/v1/payments/{paymentId}/refunds` (HMAC, Idempotency-Key, amount optional = full);
  - `GET /checkout/v1/payments/{paymentId}/refunds`;
  - `GET /checkout/v1/payments?from=&to=&status=` (paged, merchant-scoped);
  - a signed refund IPN.
  Update `contracts/api/mw-checkout/checkout.v1.openapi.yaml` (owned) and the APISIX routes.
- `IpnRetrySchedule`: default 1m, 5m, 30m, 2h, 6h, 12h, 24h (config). The short dev timers stay in `dev.env.defaults`.
- `services/mw-simulators` `MerchantIpnController`: records refund IPNs.
- Test console checkout shop: a refund button.
- Docs: `services/mw-checkout/CLAUDE.md`, README knobs.

**FRS IDs covered:** CH-WEB-03, CH-WEB-04, FL-06, UC-12, WAL-11 (partial)

**Blocked by:** 95, 96

**Status:** open

- [ ] Unit: refund remainder arithmetic; the IPN schedule covers ≥ 24 h
- [ ] `CheckoutRefundIT`: two partial refunds within the original amount succeed, a third over it is refused; balances in core-mock are correct (payer +, merchant −, fee rule as configured)
- [ ] T6 on refund: the same Idempotency-Key twice gives one refund; a different body gives 409 `MW-IDEM-001`
- [ ] T1, T2, T9 and T10 for the refund flow (the external-partner rows T3/T4/T5/T7/T8 are not applicable: note this in TRACEABILITY)
- [ ] Another merchant can't refund or list the payment (403)
- [ ] `./gradlew :mw-checkout:build :mw-orchestrator:build :mw-core-mock:build :mw-simulators:build :contractsLint` green with Docker
- [ ] API suite folder `50-checkout-refund` passes; `09-checkout` still passes
