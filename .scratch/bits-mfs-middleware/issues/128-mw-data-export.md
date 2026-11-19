# 128: mw-data-export: Data Warehouse stream, daily batch, PII policy, retention and archival (FRS EXT-17, DAT-01..04)

**What to build:** A new service `mw-data-export` (scaffolded with `scripts/new-service.sh mw-data-export`) that feeds
the Data Warehouse in two ways: DW-facing Kafka topics derived from the internal events, and a daily SFTP batch of
files with a control file. It applies a configurable PII field policy to both, and it runs the retention and archival
rules per data type.

**Principles:**
- The DW contract is separate from the internal contracts: internal topics can change without breaking the DW.
  DW topics carry the FRS names (`txn.created`, `txn.state-changed`, `txn.completed`, `notification.status`,
  `recon.exception`, `kyc.completed`, `audit.operator-action`) with a `dw.` prefix and `.v1` suffix.
- Schemas are JSON Schema in Git (`contracts/events/mw-data-export/`); no schema registry (deviation recorded in
  ticket 89's ADR and `docs/frs-traceability.md`).
- PII never leaves unmasked: every field is tagged `CLEAR`, `MASK` or `TOKENISE` in the policy; an untagged field fails
  the export (deny by default). Tokenisation goes through `mw-crypto` `TOKENISE_*` (already granted to mw-audit-dw;
  add the grant for this service), so the same MSISDN gives the same token.
- No Camel: `client/SftpClient` (sshj) + Resilience4j.

**Scope:**
- **Stream:** Spring Kafka consumers of `mw.txn.status.v1`, `mw.notify.status.v1`, `mw.recon.exception.v1`,
  `mw.kyc.completed.v1`, `mw.audit.event.v1` (operator actions only) → DW topics (added to `deploy/kafka/topics.txt`)
  through the outbox; inbox dedup.
- **Batch:** `scheduler/DwDailyExportJob` (ShedLock, after midnight Asia/Dhaka): files `transactions_<date>.csv`,
  `wallet_snapshot_<date>.csv` (core REST read, ticket 94), `notifications_<date>.csv`, `recon_<date>.csv` plus
  `control_<date>.json` (row count and sha256 per file) uploaded to the `dw/in` folder; validated by the DW mock of
  ticket 123. A day is exported once (register), re-export by an audited operator call.
- **PII policy:** `config/PiiPolicyProperties` (YAML per topic/file, field → CLEAR|MASK|TOKENISE), applied by one
  `service/PiiPolicy` used by both paths.
- **Retention and archival (DAT-04):** policy per data type (e.g. online 90 days, archive 10 years); an archival job
  moves this service's own aged rows to compressed archive files on a configured volume (`deploy` volume
  `dw-archive`) with an index table so an archived day can be fetched on demand
  (`GET /internal/v1/archive/{type}/{date}`). Other services keep their own `RetentionService`; their policies are made
  configurable per data type in the same style (documented, one property per type) — only mw-audit-dw and
  mw-orchestrator in this ticket.
- **Compose:** `mw-data-export` block (profile `core`) with a memory limit; `:composeCheck` within 12 GB.
- **Docs:** service `CLAUDE.md`, `contracts.yml`, README, runbook (re-export a day, fetch an archive), traceability.

**FRS IDs covered:** EXT-17, DAT-01, DAT-02, DAT-03, DAT-04, FL-14 (DW event), UC-22 (DW part).

**Blocked by:** 123

**Status:** open

- [ ] `PiiPolicyTest`: CLEAR / MASK / TOKENISE per field; an untagged field fails the export
- [ ] `DwStreamIT` (Testcontainers): a status event produces one `dw.txn.state-changed.v1` and, on COMPLETED, one `dw.txn.completed.v1`, with no clear MSISDN
- [ ] `DwDailyExportIT` (Testcontainers SFTP): the day's files and control file are uploaded; row counts and checksums match; a second run uploads nothing
- [ ] `ArchivalIT`: rows older than the policy move to the archive and are fetchable on demand
- [ ] Produced contracts tested by `ProducedContractsTest` (every DW topic has a fixture)
- [ ] `./gradlew :mw-data-export:build :mw-audit-dw:build :mw-orchestrator:build :contractsLint :composeCheck :checkIsolation` green with Docker
- [ ] API suite folder `62-dw-export`: trigger an export for today and read the DW mock's validation verdict from `/mock-control/dw/journal`
