# 123: File mocks: AML responder, MT940/942 generator, DW drop validator, file faults (FRS EXT-05, EXT-06, EXT-17, MCT-05)

**What to build:** The file-based partner mocks every back-office ticket (124–128) tests against, built in
`mw-simulators` on the `/mock-control` engine of ticket 102, using the existing `partner-sftp` container in
`deploy/docker-compose.yml`:
1. **AML mock (`aml`)**: watches the AML inbox folder, reads each transaction file the middleware uploads, applies
   rule-based alerts, writes a response file (ALERT / HOLD / BLOCK lines) to the outbox folder.
2. **Trust bank (`trustbank`)**: builds a daily MT940 and intraday MT942 from the mw-core-mock ledger (core REST
   statements, ticket 94) for the trust account, with optional injected mismatches.
3. **Data Warehouse drop (`dw`)**: validates every batch file and its control file that `mw-data-export` drops
   (row count, checksum, schema), and exposes the verdict in its journal.

**Principles:**
- Files are produced in the agreed formats only (MT940/942 built with Prowide Core `MT940`/`MT942`, never by string
  concat), so the consumers' parsers are exercised for real (MCK-01).
- Deterministic: rules and mismatches come from data triggers and scenarios, documented per mock (MCK-03); the same
  seed and date give the same file byte for byte.
- mw-simulators reads the ledger only through the core-mock REST API, never its schema.

**Scope:**
- **mw-simulators** `client/SftpClient` (sshj), `scheduler/` jobs guarded by ShedLock: `AmlResponderJob` (poll),
  `TrustStatementJob` (daily cut-off + intraday every N minutes, config), `DwDropValidatorJob`.
- **AML rules** (config + seed `seed/aml/rules.json`): amount over threshold → ALERT; MSISDN on a seeded watch-list →
  HOLD; MSISDN ending `6666` → BLOCK; file format and trailer record as defined in ticket 125 (the mock owns no
  contract, it follows the file spec in `contracts/files/aml/` written there — put a draft here and let 125 finalise).
- **MT940 mismatches** (scenario, MCT-05): drop a line (MISSING_IN_PARTNER), add a line (MISSING_IN_UPAY), change an
  amount (AMOUNT_MISMATCH), flip a debit/credit mark.
- **File faults** (MCT-05, every file mock): `MISSING_FILE`, `LATE_FILE(ms)`, `DUPLICATE_FILE`, `CORRUPT_FILE`,
  `INJECTED_MISMATCH(kind,count)`, scoped by date or next N files; journal lists every file read or written with
  checksum.
- **Compose:** `partner-sftp` folders `aml/in`, `aml/out`, `trustbank/out`, `dw/in` created by its init; no new
  container.
- **Test console:** a "Files" page listing the journal and buttons to trigger a statement / AML response now.
- **Docs:** `services/mw-simulators/CLAUDE.md` (triggers, folders, scenarios), `docs/testing-guide.html`.

**FRS IDs covered:** EXT-05, EXT-06, EXT-17 (mock side), MCT-05, MCK-03/06/07/08 for file mocks.

**Blocked by:** 102, 94

**Status:** open

- [ ] `AmlResponderTest`: a sample transaction file gives the expected ALERT/HOLD/BLOCK lines and trailer totals
- [ ] `TrustStatementGeneratorTest`: ledger fixture → MT940 that Prowide parses back with matching opening/closing balance and line count; same input → same bytes
- [ ] `FileFaultScenarioTest`: each MCT-05 fault produces the documented effect, scoped to the next N files only
- [ ] `SftpMocksIT` (Testcontainers SFTP): upload → response file appears; statement job writes MT940/942; DW validator flags a wrong row count
- [ ] `./gradlew :mw-simulators:build :composeCheck :scriptTests` green with Docker
- [ ] API suite folder `57-file-mocks`: trigger a statement and an AML response through `/mock-control` and read them back from the journal
