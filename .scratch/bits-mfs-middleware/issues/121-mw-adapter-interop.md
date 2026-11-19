# 121: mw-adapter-interop: NPSB fund transfer over ISO 20022 (FRS EXT-13, UC-19, FL-12 off-us)

**What to build:** A new adapter `mw-adapter-interop` (scaffolded with `scripts/new-service.sh mw-adapter-interop`) for
the NPSB switch (also the off-us leg of BanglaQR, ticket 122): outbound fund transfer (`NPSB_OUT`), inbound credit
(`NPSB_IN`), status inquiry and return, all as ISO 20022 XML. It also adds the orchestrator flows for both txn types
and a stateful NPSB switch mock in mw-simulators.

**Principles:**
- Camel is allowed here (it is a `mw-adapter-*`, D1/D28). The partner engine `partner/` is **copied** from
  mw-adapter-billpay (with the REVERSE op, health and payload store of ticket 97), not shared. The three existing copies
  plus this one stay identical apart from the package.
- ISO 20022 messages are built and parsed with Prowide ISO 20022 (`pw-iso20022`), never by string templates. Every
  outbound message is signed (XML-DSig) through `mw-crypto` (`POST /internal/v1/...` sign operation, ticket 65), and
  inbound messages are verified the same way. No key material in the adapter.
- `NPSB_OUT` follows the "wallet debit, then external call" template (FRS 5.2). `NPSB_IN` follows the inbound-credit
  template (FRS 5.3): dedupe by the switch's `TxId` / `EndToEndId` (PLT-014), validate the beneficiary, credit through
  core REST `POST /credits` (ticket 95), and if the credit fails after acceptance go to MANUAL_REVIEW, never drop.
- pacs.002 `ACSP`/`ACSC` = success, `RJCT` = definite failure, no reply / `PDNG` = UNKNOWN then pacs.028 inquiry.

**Scope:**
- **Module** `services/mw-adapter-interop/` (package `mfs.mw.adapter.interop`): `partner/` engine copy; family
  `interop` with operations PAY (pacs.008), STATUS_QUERY (pacs.028 → pacs.002), REVERSE (camt.056 → camt.029 /
  pacs.004 return), INBOUND (pacs.008 in → pacs.002 out). A `mapping/` package for the Prowide ⇄ canonical mapping
  (JSON/XML convention of `partner/mapping`), with an XSD validation step on every message (XSDs under
  `src/main/resources/iso20022/`).
- **Kafka:** consumes `mw.partner.request.interop.v1` (new topic in `deploy/kafka/topics.txt` with retry/DLQ),
  replies on `mw.partner.reply.v1`, inbound on `mw.partner.inbound.v1` (kind `NPSB_IN`, add the kind to the owner's
  schema through mw-orchestrator's contract change in this ticket, `contracts/OWNERS.yml` unchanged).
- **Inbound endpoint:** `POST /partner/v1/npsb/inbound` behind an APISIX route with mTLS client cert (ticket 100) and
  an IP allow-list; signature verified in the adapter.
- **Orchestrator:** `NpsbOutFlow` and `NpsbInFlow` in `services/mw-orchestrator/.../service/saga/` registered in
  `FlowRegistry`; routing rule rows for `NPSB_OUT` by destination participant (ticket 98).
- **App gateway:** `POST /app/v1/npsb-transfers/preview|confirm` (preview/confirm pattern of ticket 105), beneficiary
  account lookup against the participant list.
- **Mock** in mw-simulators on the `/mock-control` engine (ticket 102): party `npsb`, participant list (≥ 10 banks and
  MFS providers, versioned seed `seed/npsb/participants.json`), stateful transfers (a returned transfer cannot be
  returned twice, pacs.028 returns the stored state), async pacs.002 with configurable delay, duplicate and
  out-of-order callbacks; the mock can also push a pacs.008 inbound on demand (`POST /mock-control/npsb/inbound`).
  Data triggers documented in `services/mw-simulators/CLAUDE.md`.
- **Compose:** `mw-adapter-interop` block (profile `core`) with a memory limit; `:composeCheck` within 12 GB.
- **Docs:** service `CLAUDE.md`, `contracts.yml`, README service list, `docs/concepts/` page for ISO 20022 and the
  traceability rows in `docs/frs-traceability.md`.

**FRS IDs covered:** EXT-13 (NPSB part), UC-19, FL-12 (off-us leg, used by 122), PLT-014, PLT-023/025 for NPSB,
MCK-01..07 for the NPSB mock.

**Blocked by:** 92, 97, 102

**Status:** open

- [ ] `Pacs008MappingTest` / `Pacs002MappingTest` / `Camt056MappingTest`: canonical → XML validates against the XSD, and back, round trip lossless
- [ ] `XmlSignatureIT`: outbound message signed through a stubbed mw-crypto; a tampered inbound message is rejected with `MW-PTR-npsb-*`
- [ ] `NpsbOutRouteIT` (Camel route test against the mock): ACSC → success, RJCT → mapped failure, no reply → UNKNOWN then pacs.028 resolves
- [ ] `NpsbInboundIT`: a duplicate pacs.008 (same EndToEndId) credits once; a credit failure after acceptance lands in MANUAL_REVIEW
- [ ] Orchestrator flow unit tests cover every transition of `NpsbOutFlow` / `NpsbInFlow`
- [ ] T1–T12 for `NPSB_OUT` and T1, T6, T7, T9, T10 for `NPSB_IN` in API suite folder `55-npsb`
- [ ] `./gradlew :mw-adapter-interop:build :mw-orchestrator:build :mw-app-gateway:build :mw-simulators:build :contractsLint :composeCheck :checkIsolation :scriptTests` green with Docker
