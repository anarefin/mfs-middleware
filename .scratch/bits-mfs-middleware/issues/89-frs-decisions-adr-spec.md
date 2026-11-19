# 89: FRS decisions: ADRs, spec amendments, FRS traceability map (FRS 1.4, 2.2, 6.2, 10.1)

**What to build:** Record the FRS planning decisions of 2026-11-19 (plan `~/.claude/plans/we-have-to-implement-harmonic-puzzle.md`)
before any code moves: five new ADRs, amendments to `spec/bits-mfs-middleware-spec.md`, and a traceability map from
every FRS name / ID in `spec/FRS-Upay-Middleware.md` to the repo's name, code or ticket. Docs and repo checks only.

**Principles:**
- The repo keeps its own names (txn types, states, channels, `MW-*` codes, UUIDv7, poisha); the FRS is mapped, not copied.
- Every decision row added to the spec gets an ADR (`scripts/test/test_repo_docs.sh` enforces it).

**Scope:**
- `docs/adr/D29-redis.md` (Valkey -> Redis; licence note: pin a BSD Redis 7.2.x image or accept Redis 8 AGPL; the image tag is chosen here).
- `docs/adr/D30-hashicorp-vault.md` (OpenBao -> HashiCorp Vault, BSL 1.1 accepted for internal use; same KV/PKI/transit API paths).
- `docs/adr/D31-core-wallet-rest.md` (amends D5: orchestrator -> core over REST, WAL-01, 03..13; WAL-02 replaced by the
  mw-auth assertion header (D20/D22 stay); core keeps publishing wallet-state, hold-expired and ledger events on Kafka;
  REST calls never on Kafka listener threads; timeout -> UNKNOWN -> WAL-13 inquiry).
- `docs/adr/D32-service-per-party.md` (new services `mw-adapter-card|remit|atm|interop|merchant`, `mw-compliance`,
  `mw-recon`, `mw-data-export`; Camel only in `mw-adapter-*`; non-adapter services use `client/` (sshj, RestClient);
  `MW_RAM_BUDGET_MIB` default in `scripts/compose-check.sh` 8192 -> 12288; `minimal` profile stays the laptop demo).
- `docs/adr/D33-unified-mock-control.md` (FRS 10.1 API `/mock-control/{party}/scenarios|journal`, `/mock-control/reset`;
  magic values stay default triggers; `/sim/*` aliases for one release; engine copied into mw-simulators and mw-core-mock).
- Record also as decisions (in D31/D32 or the spec table): routing (FRS M07) is a module of mw-orchestrator; no schema
  registry (JSON Schema in Git, deviation from DAT-01 noted); coverage reported, not gated (D19); ticket 88 superseded by 126/127.
- `spec/bits-mfs-middleware-spec.md`: decision rows D29–D33 in 0 "Decisions confirmed", amend 3 (stack), 4.1 (services), 6.3
  (core contract over REST), 22.2 (mock control); an "FRS scope" note above the MVP section.
- `docs/frs-traceability.md`: tables per FRS section: FRS ID -> status (done / ticket NN / deviation + reason) and a
  name map (SEND_MONEY=P2P, PAYMENT=MERCHANT_PAY, REMITTANCE_IN=REMIT_IN, QR_PAY=QR_PAY_INTEROP, DISBURSEMENT=BULK_DISBURSE;
  APP_CUSTOMER=CUSTOMER_APP ...; USSD_GP.. = `USSD` + `metadata.mno`; AUTHORISED=VALIDATING, DEBIT_HELD=HOLDING,
  EXTERNAL_PENDING=PARTNER_PENDING; VAL-=MW-VAL-, AUTH-=MW-AUTH-, IDM-001=MW-IDEM-001, LIM-*=MW-CORE-<reason>,
  EXT-400=MW-PTR-<partner>-*, EXT-503=MW-SYS-PARTNER_CIRCUIT_OPEN/UNAVAILABLE, EXT-504=MW-TMO-*, SYS-500=MW-SYS-*;
  ULID=UUIDv7, Decimal(18,2)=minor units).
- Record two decisions taken in later tickets so ADRs exist up front: the new error namespace `MW-CMP-*` (compliance,
  ticket 125) and the AML real-time pre-check failing open with an alert (ticket 125). IDs already met today
  (e.g. PLT-001/005/007/010/020/033/035, CH-USSD-02/09, CH-WEB-01/02/05, NOT-03/04, ADM-09) are marked `done` with evidence.
- Root `CLAUDE.md`, `README.md`, `docs/adr/README.md`: service list, Redis/Vault wording, link to the traceability doc.

**FRS IDs covered:** 1.4 (stack), 2.2/2.3/3.7 (by mapping), 10.4 #5 (documentation of deviations)

**Blocked by:** —

**Status:** open

- [ ] `scripts/test/test_repo_docs.sh` extended first: fails when a D29–D33 row has no ADR or `docs/frs-traceability.md` is missing; then green
- [ ] Every FRS requirement ID (PLT, CH-*, FL, CON, EXT, MCK, MCT, WAL, NOT, REC, CMP, AUD, DAT, ADM, T1–T12) appears exactly once in `docs/frs-traceability.md` with a status (a grep script in `scripts/test/` checks it against `spec/FRS-Upay-Middleware.md`)
- [ ] `./gradlew :scriptTests` green
