# 116 split (2026-11-20, implementer option 1, auto-split rule)

- **116a-core-partial-reversal-list** (done, d42c5172, 2bc20979): core-mock partial reversal (V15, 422 MW-CORE-REFUND_EXCEEDS,
  advisory lock per original, concurrent IT), checkout IPN 24 h schedule (8 attempts), merchant payment list
  `GET /checkout/v1/payments` (own sessions, 31-day window) + APISIX route.
- **116b-orchestrator-refund-flow** (116a): REVERSAL as its own txn record; WEB_CHECKOUT refunds only from the owning merchant;
  partial amount through the core client; IT.
- **116c-checkout-refund-api** (116b): `POST`/`GET /checkout/v1/payments/{id}/refunds` (HMAC, Idempotency-Key, 409, 403), signed
  refund IPN, contract + APISIX routes, `CheckoutRefundIT`, simulators IPN record, test-console refund button, Newman folder
  (next free number), TRACEABILITY rows; seeder allowed-routes.
