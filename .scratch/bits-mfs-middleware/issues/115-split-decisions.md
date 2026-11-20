# 115 split (2026-11-20, implementer recommendation, auto-split rule)

- **115a-merchant-adapter** (blocked by 97,102): finish `services/mw-adapter-merchant` (copied from mw-adapter-bank, WIP 9c7f6629
  on `ticket/115-mw-adapter-merchant`): merchant-soap seed (SOAP route, XSLT, WS-Security), `MerchantSoapRouteIT` (WireMock, like
  `BillaggSoapRouteIT`), fix the failing tests, Kafka topic, mw-admin-api Flyway family CHECK, compose block, provision (role,
  schema, Vault ws-password), `scripts/test/test_partner_engine_copies.sh`, module CLAUDE.md. Green: `:mw-adapter-merchant:build`,
  `:mw-admin-api:build`, `:contractsLint`, `:composeCheck`, `:checkIsolation`, `:scriptTests`.
- **115b-merchant-pay-flow** (blocked by 115a,113): orchestrator `MerchantPayFlow` validate + notify (retry then DLQ),
  `#merchant` in orchestrator contracts.yml, `MerchantSoapMock` in mw-simulators, `MerchantPayNotifyIT`, T1–T12 rows, Newman
  folder `49-merchant-soap`, docs (README, concepts, frs-traceability).
- Contract note: `merchant` added to the family enums of billpay-owned `mw.partner.route.status.v1` and `mw.partner.recon.v1`
  (needed for the module's own events; additive enum value).
