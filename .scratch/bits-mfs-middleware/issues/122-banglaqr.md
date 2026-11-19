# 122: BanglaQR: EMVCo merchant-presented QR, on-us and off-us payment (FRS EXT-13, UC-18, FL-12)

**What to build:** BanglaQR support end to end: parse and generate EMVCo merchant-presented QR payloads, and a
`QR_PAY_INTEROP` flow (repo name for FRS `QR_PAY`, mapping in `docs/frs-traceability.md`) that routes an **on-us** QR
(an Upay merchant) to the internal `MERCHANT_PAY` path and an **off-us** QR (another participant's merchant) to the
NPSB switch through `mw-adapter-interop` (ticket 121).

**Principles:**
- The QR payload is untrusted input: CRC16-CCITT checked first, every TLV length bounded, unknown tags kept but never
  executed. A bad payload fails with `MW-VAL-*` before any money moves.
- On-us vs off-us is decided by the acquirer id in the merchant account information template (tag 26–51) against the
  routing rules (ticket 98), never by the channel.
- Merchant-presented QR only (FRS scope). Static QR has no amount (customer enters it); dynamic QR carries tag 54 and
  the amount is not editable.

**Scope:**
- **mw-app-gateway** `util/EmvQrCodec` (parse + generate, CRC), `dto/QrPayload`; endpoints
  `POST /app/v1/qr/decode` (returns merchant name, city, amount if dynamic, on-us/off-us) and
  `POST /app/v1/qr-payments/preview|confirm` (pattern of ticket 105); merchant app `POST /app/v1/merchant/qr`
  generates a static or dynamic QR for the logged-in merchant.
- **Orchestrator:** `QrPayFlow`: on-us reuses the `MerchantPayFlow` steps (core REST transfer); off-us is the
  `NPSB_OUT` step chain of ticket 121 with `metadata.qr` (merchant PAN, MCC, terminal label) carried into pacs.008.
  Registered in `FlowRegistry`; the type moves out of the "unsupported" list (`MW-VAL-010`).
- **Contracts:** `qr` metadata object in `mw.txn.request.v1` (owner mw-orchestrator), app-gateway OpenAPI paths.
- **Mock:** mw-simulators `npsb` party gets a merchant registry of off-us merchants (seed `seed/npsb/merchants.json`)
  so off-us payments settle; test console page to render a seeded QR as an image and "scan" it.
- **Docs:** app-gateway `CLAUDE.md`, the QR trigger values in `services/mw-simulators/CLAUDE.md`,
  `docs/testing-guide.html` QR section, traceability rows.

**FRS IDs covered:** UC-18, FL-12, EXT-13 (BanglaQR part), CH-APP API group `qr`.

**Blocked by:** 121

**Status:** open

- [ ] `EmvQrCodecTest`: the published EMVCo/BanglaQR sample payloads parse; generate → parse round trip; bad CRC, overlong TLV and missing mandatory tags rejected
- [ ] `EmvQrCodecFuzzTest` (property-based, jqwik or plain random): no exception other than the validation exception for any input up to 512 chars
- [ ] `QrPayFlowTest`: on-us QR takes the core transfer path; off-us QR takes the NPSB path; dynamic QR amount cannot be changed
- [ ] `QrPaymentControllerTest` (`@WebMvcTest`): decode and preview/confirm validation errors use the error envelope
- [ ] T1–T12 for on-us and off-us `QR_PAY_INTEROP` in API suite folder `56-banglaqr`
- [ ] `./gradlew :mw-app-gateway:build :mw-orchestrator:build :mw-simulators:build :contractsLint` green with Docker
- [ ] `scripts/tester-e2e.sh @qr` (new spec) green on the running stack
