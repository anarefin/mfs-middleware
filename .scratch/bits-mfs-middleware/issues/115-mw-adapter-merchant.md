# 115: mw-adapter-merchant: merchant system SOAP (FRS UC-11, EXT-11)

**What to build:** A new adapter service `mw-adapter-merchant` (decision 5, one service per party). It talks
to merchant systems over SOAP:
- payment notify after an in-store MERCHANT_PAY;
- merchant validation before pay (merchant exists, active, till id valid);
- refund.

Today `MerchantPayFlow` is core-only and the only merchant integration is checkout's REST IPN.

**Principles:**
- Camel is allowed here (`mw-adapter-*`, D1/D28). The `partner/` engine is copied from `mw-adapter-billpay`
  unchanged apart from the package (`mfs.mw.adapter.merchant.partner`), so the three copies (now four) stay
  identical.
- The merchant notify is after-commit and best effort with retries. A failed notify never reverses the payment; it
  goes to a retry topic and then the DLQ with console visibility.

**Scope:**
- `scripts/new-service.sh mw-adapter-merchant`, then copy `partner/` and the Kamelets from billpay, plus
  `messaging/PartnerAuditPublisher`.
- Family `merchant` in the partner engine:
  - The `partner_route.family` CHECK in mw-admin-api gets the new value (Flyway, next free version: check for
    duplicate `V<n>__`).
  - Topic `mw.partner.request.merchant.v1` goes in `deploy/kafka/topics.txt`.
  - The orchestrator-owned `mw.partner.request.v1` contract gains `family=merchant` (an orchestrator contract
    change in this ticket's scope).
- Operations: VALIDATE (INQUIRY), NOTIFY (PAY-like, after COMMIT), REFUND (REVERSE).
- Route record `merchant-soap` (`mw-soap-call`, XSLT mappings, WS-Security).
- Orchestrator `MerchantPayFlow`: an optional validate step before the hold (when the merchant has
  `externalSystem=true`) and a notify step after commit.
- `services/mw-simulators`: `MerchantSoapMock` (SOAP endpoints validate, notify, refund; seeded merchants; stateful
  refunds) on `/mock-control/merchant-soap`.
- `deploy/docker-compose.yml`: a `mw-adapter-merchant` block (profile `core`) with a memory limit. `:composeCheck`
  stays within the 12 GB budget set by ticket 89.
- Docs: service `CLAUDE.md`, `contracts.yml`, README, `docs/concepts/camel-partner-routes.md` mention.

**FRS IDs covered:** UC-11, EXT-11, MCK-01 (SOAP), CON-02

**Blocked by:** 97, 102

**Status:** open

- [ ] Camel route tests: validate, notify and refund mappings, and the SOAP fault mapped to `MW-PTR-merchant-*`
- [ ] `MerchantPayNotifyIT`: completed pay → notify received by the mock; a mock 5XX is retried, then DLQ; the txn stays COMPLETED
- [ ] An invalid merchant fails at validate, before any hold (T9-like: FAILED, no external pay)
- [ ] T1–T12 for MERCHANT_PAY with an external merchant system (T3/T4 apply to validate inquiry only; document the not-applicable rows)
- [ ] `./gradlew :mw-adapter-merchant:build :mw-orchestrator:build :mw-simulators:build :mw-admin-api:build :contractsLint :composeCheck :checkIsolation` green with Docker
- [ ] API suite folder `49-merchant-soap` passes; `04-merchant` still passes
