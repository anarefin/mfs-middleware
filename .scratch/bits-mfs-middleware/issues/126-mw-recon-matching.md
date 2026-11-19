# 126: mw-recon: statement and settlement ingest, matching and classification (FRS UC-22, REC-01..04, REC-08)

**Supersedes 88.** Ticket 88 (`88-mw-recon-matching.md`) is not built; this ticket keeps its principles and scope and
adds the FRS items: MT940/942 from the trust bank, tolerance rules and the FRS class names.

**What to build:** A new service `mw-recon` (scaffolded with `scripts/new-service.sh mw-recon`) that:
1. pulls the trust bank's daily MT940 and intraday MT942 from SFTP and parses them with Prowide Core;
2. consumes the partner settlement lines the adapters already publish on `mw.partner.recon.v1` (ticket 86), plus the
   lines of the new adapters (card, remit, atm, interop, merchant);
3. matches every line against its own projections of the middleware transactions and the core ledger, and classifies
   the result.

**Principles (kept from 88):**
- The core is the system of record for money. mw-recon **reports; it never posts money and never moves a transaction
  state**. Corrections go through the normal reversal or manual-credit flows with maker-checker (ticket 127 raises
  them as change requests in mw-admin-api).
- The saga resolves UNKNOWN in real time through STATUS; the settlement file is the end-of-day backstop, not a
  second writer.
- mw-recon never reads another service's schema: it builds its own projections from events.
- No Camel (not an adapter): `client/SftpClient` (sshj) + Resilience4j for the MT940 pull; partner files stay with the
  adapters (86).

**Scope:**
- **Schema `recon`** (Flyway): `source_file` (source, filename, sha256, size, received at, status; unique
  filename+sha256: REC-08), `statement_line` (MT940/942: value date, amount, D/C mark, reference, supplementary
  details, file id, line no), `partner_line` (as 88), `txn_view` (projection of `mw.txn.status.v1`), `ledger_view`
  (projection of the core ledger events, ticket 95), `match_result`, `recon_exception` (class, owner, refs, amounts,
  state OPEN / ACTIONED / RESOLVED), `recon_run`.
- **Ingest:** `scheduler/TrustStatementPullJob` (ShedLock; MT940 daily after cut-off, MT942 every N minutes, config);
  Spring Kafka consumers for `mw.partner.recon.v1`, `mw.txn.status.v1`, the core ledger events; retry/DLQ per D27.
- **Matching rules** (per source, config): key `partnerId` + `partnerRef` (fallback `reference`), date window
  (± N days), amount tolerance (absolute poisha, default 0); statement lines match on the core settlement-wallet
  postings. **Classes (FRS REC-04):** `MATCHED`, `MISSING_IN_PARTNER` (Upay COMPLETED, not on the file after
  cut-off), `MISSING_IN_UPAY` (on the file, unknown to Upay), `AMOUNT_MISMATCH`, `STATUS_MISMATCH` (e.g. Upay
  REVERSED, partner SUCCESS). A core-ledger gap (Upay COMPLETED, no posting) is a `STATUS_MISMATCH` with
  `detail=LEDGER_MISSING`.
- **Output:** `mw.recon.result.v1` per run (counts per class + exceptions; key `partnerId`), contract under
  `contracts/events/mw-recon/`, owner in `contracts/OWNERS.yml`; replace the `mw.partner.recon.v1` entry in
  `contracts/EXTERNAL.yml` with mw-recon's `contracts.yml`.
- **Reprocess:** a file already in `source_file` is skipped; `POST /internal/v1/recon/files/{id}/reprocess` (operator
  action through mw-admin-api, audited) re-runs it.
- **Compose:** `mw-recon` block (profile `core`) with a memory limit; `:composeCheck` within 12 GB.
- **Docs:** service `CLAUDE.md`, spec 13 amendment pointer (from ticket 89), README, runbook, traceability; DEPENDENCIES
  row of 88 marked `superseded by 126,127`.

**FRS IDs covered:** UC-22 (recon part), REC-01, REC-02 (consumer side), REC-03, REC-04, REC-08, EXT-06.

**Blocked by:** 123, 95

**Status:** open

- [ ] `Mt940ParserTest`: the generator output of ticket 123 and a hand-written sample parse to statement lines; MT942 too
- [ ] `MatchingServiceTest`: one case per class incl. tolerance edge (exactly at, one poisha over) and date window
- [ ] `ReconMatchingIT` (Testcontainers): a file with matched, missing-in-partner, missing-in-Upay, amount and status mismatch lines → expected exceptions and one `mw.recon.result.v1`
- [ ] `FileRegisterIT`: the same file twice is processed once; reprocess re-runs it and is audited
- [ ] A redelivered recon line or event creates no duplicate exception (inbox)
- [ ] No producer of command topics and no core write API in `contracts.yml` (mw-recon never moves money or state)
- [ ] `./gradlew :mw-recon:build :contractsLint :composeCheck :checkIsolation :scriptTests` green with Docker
- [ ] API suite folder `60-recon-matching`: one mock business day with injected mismatches gives the expected classes
