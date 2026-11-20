# 121 split (2026-11-20, auto-split rule; 1 h cap for this run, like 118/119/120)

Ticket 121 is a new adapter + an ISO 20022 wire with XML-DSig + two orchestrator flows + an app-gateway API +
a stateful mock + Newman: far more than one implementer slot. Split in three (the caller's 121a/121b
split, with the ISO 20022 wire pulled out of "adapter module" so 121a ships without a throwaway JSON wire):

- **121a-interop-adapter** (95,97,102) DONE on `ticket/121-mw-adapter-interop`: new `services/mw-adapter-interop`
  (framework copy from mw-adapter-card, package `mfs.mw.adapter.interop`), family `interop`, request check
  (PAY / STATUS_QUERY / REVERSE; NPSB_OUT with `attributes.participantId`, QR_PAY_INTEROP to a MERCHANT for
  122), topic `mw.partner.request.interop.v1`, compose block (8102, 384 MiB), provision (role + schema only, no
  partner secret: signing is mw-crypto's), admin-api family CHECK `V21__partner_family_interop.sql`, family
  `interop` in route.status / recon / config.changed / admin openapi enums, NPSB request/reply samples,
  engine-copies test, module CLAUDE.md; mw-simulators party `npsb` with the versioned participant seed
  `seed/v1/npsb/participants.json` (12) and `GET /npsb/v1/participants`. No dev seed partner yet.
- **121b-npsb-iso20022-wire** (121a, 65): Prowide `pw-iso20022` (latest stable) + XSDs under
  `src/main/resources/iso20022/`, `mapping/` (canonical <-> pacs.008 / pacs.002 / pacs.028 / camt.056 /
  camt.029 / pacs.004; `Pacs008MappingTest`, `Pacs002MappingTest`, `Camt056MappingTest`), XML-DSig sign and
  verify through mw-crypto (`XmlSignatureIT`; mw-crypto may need a sign-XML key purpose: check ticket 65's API
  first, else an open item), the `npsb` dev seed partner (`NpsbOutRouteIT`: ACSC / RJCT / no reply -> UNKNOWN
  -> pacs.028), inbound `POST /partner/v1/npsb/inbound` + APISIX mTLS route with IP allow-list, kind `NPSB_IN`
  added to `mw.partner.inbound.v1` (orchestrator-owned contract, as the ticket says), `NpsbInboundIT`
  (dedupe on EndToEndId); stateful NPSB mock (transfers, async pacs.002 delay, duplicate / out-of-order
  callbacks, return-once, `POST /mock-control/npsb/inbound`), its data triggers in mw-simulators CLAUDE.md.
- **121c-npsb-flows-channels** (121b, 92, 98, 105): mw-orchestrator `NpsbOutFlow` / `NpsbInFlow` in
  `service/saga/` + `FlowRegistry` (credit failure after acceptance -> MANUAL_REVIEW), routing rows for
  NPSB_OUT by destination participant, mw-app-gateway `/app/v1/npsb-transfers/preview|confirm` with the
  participant lookup, Newman folder **57-npsb** (T1-T12 NPSB_OUT, T1/T6/T7/T9/T10 NPSB_IN; the ticket's
  `55-npsb` is taken), `docs/concepts/` ISO 20022 page, `docs/frs-traceability.md` rows (EXT-13, UC-19,
  FL-12, PLT-014, PLT-023/025, MCK-01..07 for NPSB).

Ticket 122 (BanglaQR) builds on 121b (wire) and 121c (flows).

## 121b2 (2026-11-20, ticket/121b2-npsb-finish)

- Inbound kind: `mw.partner.inbound.v1` already had kind `NPSB_CREDIT` (new inbound money from the switch); the
  adapter publishes kind `NPSB_CREDIT` with txnType `NPSB_IN` (sample `interop/valid-npsb-in.json`) instead of
  adding a second kind `NPSB_IN` with the same meaning. 121c's NpsbInFlow keys on txnType `NPSB_IN`.
- Dev switch key pair: provision `npsb_switch_key` (secret/mw-simulators `sim.npsb.signing-key`) and
  `npsb_switch_certificate` (secret/mw-adapter-interop `npsb-certificate-pem`); the mock signs every document it
  sends, so the stack verifies the switch end to end.
- Still open (not 121c): edge mTLS for the switch on route `partner-npsb-inbound` (APISIX client-certificate
  check, with ticket 100c's MTLS clients).
