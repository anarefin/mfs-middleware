# 118: mw-adapter-card: CyberSource add money from card (FRS UC-13, EXT-14, FL-07)

**What to build:** A new adapter service `mw-adapter-card` for CyberSource-style card acquiring. Card data never
touches Upay (FL-07): the customer enters the card on the mock CyberSource hosted page, and the middleware sees only
a token and the 3-D Secure result. It covers the flow `ADD_MONEY_CARD` (credit the wallet from the CyberSource
settlement wallet) and refund.

**Principles:**
- PCI scope stays out: no PAN, CVV or expiry in any DTO, log or table. A test fails if a `pan`/`cardNumber` field
  appears in any contract.
- The wallet is credited only after authorise + capture succeeds. A 3DS failure or a decline means FAILED with no
  money movement. A capture timeout goes to STATUS (UNKNOWN), never to an automatic credit.

**Scope:**
- `scripts/new-service.sh mw-adapter-card`, then copy the `partner/` engine from billpay (package
  `mfs.mw.adapter.card.partner`). Family `card`: CHECK value, topic `mw.partner.request.card.v1`, and the contract
  `family` value.
- Operations: CREATE_SESSION (payment session / Flex token), AUTHORISE_CAPTURE, STATUS_QUERY, REFUND (REVERSE),
  plus the inbound 3DS/webhook CALLBACK (HMAC-signed, nonce plus a partner-ref dedup per PLT-014).
- The route record `cybersource` uses jws or hmac auth (ticket 87 Kamelets).
- Orchestrator `AddMoneyCardFlow`: session → (customer on the hosted page) → callback → authorise+capture → core
  `POST /credits` from the CyberSource settlement wallet (ticket 94). Refund reverses the credit, then sends REFUND
  to the partner.
- App API: `POST /app/v1/add-money/card/sessions` returns the hosted page URL; `GET` returns the status.
- `services/mw-simulators`: `CyberSourceMock`:
  - a hosted page (a simple HTML form);
  - test cards: success `4111…1111`, decline, 3DS fail, timeout;
  - a webhook back to the middleware with configurable delay or duplication;
  - it runs on `/mock-control/cybersource`.
- Compose block (profile `core`) with a memory limit; 12 GB budget.
- Docs: service `CLAUDE.md` (PCI rules), `contracts.yml`, README, test console page for the hosted page.

**FRS IDs covered:** UC-13, EXT-14, FL-07, PLT-014 (CyberSource callbacks), MCK-06

**Blocked by:** 95, 97, 102

**Status:** open

- [ ] Unit/contract test: no card-data field in any `contracts/` schema or DTO (fails on `pan|cardNumber|cvv`)
- [ ] Camel route tests: session, authorise+capture, refund mappings, webhook signature check
- [ ] `AddMoneyCardIT`: success card credits the wallet; a decline or 3DS fail gives FAILED with balances unchanged
- [ ] T1–T12 for ADD_MONEY_CARD (T3/T4 on capture timeout; T7 duplicate webhook processed once)
- [ ] `./gradlew :mw-adapter-card:build :mw-orchestrator:build :mw-app-gateway:build :mw-simulators:build :mw-admin-api:build :contractsLint :composeCheck :checkIsolation` green with Docker
- [ ] API suite folder `52-add-money-card` passes
