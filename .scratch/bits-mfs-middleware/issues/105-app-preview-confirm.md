# 105: App two-step financial APIs: /preview and /confirm (FRS CH-APP-05, PLT-026)

**What to build:** Today the app has one `POST /app/v1/transactions`
(`services/mw-app-gateway/.../controller/TransactionController.java`); fees are computed by the core inside
ValidateTransaction and the customer never sees them before entering the PIN. Add the FRS two-step: preview returns
fee, VAT, commission, counterparty name and the limits check; confirm carries the PIN ciphertext and Idempotency-Key.

**Principles:**
- Preview moves no money and needs no PIN; confirm is the only financial call.
- Confirm binds to its preview: a `previewId` (signed, 5 min TTL in Redis) holding the canonical txn hash, so a changed
  amount or counterparty between preview and confirm is refused (`MW-VAL-0xx PREVIEW_MISMATCH`).
- `POST /app/v1/transactions` stays as v1 for one release (PLT-051) and internally runs preview + confirm.

**Scope:**
- `contracts/api/mw-app-gateway/app.v1.openapi.yaml`: `POST /app/v1/{group}/preview` and `/confirm` for groups
  send-money, cash-in, cash-out, payment, bill-pay, top-up, add-money (more groups join in their own tickets).
- `mw-app-gateway service/PreviewService`: calls the core REST `POST /charges/calculate` (WAL-04) and
  `POST /limits/check` (WAL-05) and `GET /accounts/{msisdn}` (WAL-01, masked counterparty name) through an internal
  read API on the orchestrator or directly to core (decide in the ticket; reads only, no saga). Top-up preview resolves
  the operator through the routing module (ticket 98).
- Confirm: obtains the mw-auth assertion (unchanged, D22) over the preview's txn hash and publishes `mw.txn.request.v1`
  as today (sync wait, 202 fallback).
- Errors: limit fail in preview returns the core reason as `MW-CORE-*` with bn/en text.
- Test console `ui/mw-test-console` app pages show the preview step; Newman folders 01, 03, 05, 06, 08 move to the
  new endpoints (old endpoint kept in one request per folder).

**FRS IDs covered:** CH-APP-05, PLT-026, 5.2 step 1–2

**Blocked by:** 95

**Status:** open

- [ ] `PreviewServiceTest`: fee/VAT/commission from the core reply; masked counterparty; previewId hash covers the
      canonical fields
- [ ] `PreviewConfirmControllerTest` (`@WebMvcTest`): confirm without previewId 400; changed amount → PREVIEW_MISMATCH;
      expired preview refused
- [ ] `PreviewConfirmIT`: preview → confirm completes P2P with the previewed fee debited; preview alone moves nothing
- [ ] `./gradlew :mw-app-gateway:build :contractsLint` green with Docker; `pnpm test` in `ui/mw-test-console`
- [ ] With the stack up: new API folder `41-preview-confirm`; folders 01, 03, 05, 06, 08 green; `scripts/tester-e2e.sh @app`
