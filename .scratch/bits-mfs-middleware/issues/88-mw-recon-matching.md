# 88: mw-recon: three-way matching of partner settlement lines (spec 13)

**What to build:** A new `mw-recon` service (scaffolded with `scripts/new-service.sh mw-recon`) that consumes
`mw.partner.recon.v1` (ticket 86) and matches each partner line against the middleware's own view of the transaction and
the core's ledger events. User decision 2026-11-18: adapters own the SFTP ingest and parsing (86); mw-recon does
**matching only** (spec 13 is amended in 86 accordingly). Written in run 77–87, **not built in that run**.

**Principles (MFS best practice, spec 13):**
- The core is the system of record for money. mw-recon **reports; it never posts money and never moves a transaction state**.
  Corrections go through the normal reversal or manual-credit flows with maker-checker.
- The saga resolves UNKNOWN in real time through STATUS; the settlement file is the end-of-day backstop, not a second writer.
- mw-recon never reads another service's schema: it builds its own projections from events.

**Scope:**
- **Schema `recon`** (Flyway): `partner_line` (raw normalized line, `partnerId`, file id, line no, `partnerRef`, `reference`,
  amount, currency, partner status, value date), `txn_view` (projection of `mw.txn.status.v1`: `txnId`, reference,
  `partnerRef`, amount, state, timestamps), `ledger_view` (projection of whatever core ledger/posting events core-mock
  already publishes on Kafka), `recon_exception` (type, owner, `txnId`/line ref, amounts, state OPEN/RESOLVED).
- **Consumers (Spring Kafka, template from 78):** `mw.partner.recon.v1`, `mw.txn.status.v1`, the core ledger events. Retry/DLQ as D25.
- **Matching rules** (per partner, config): key = `partnerId` + `partnerRef` (fallback `reference`); compare amount and
  final status. Outcomes:
  - MATCHED: partner SUCCESS, mw COMPLETED, ledger posted, same amount.
  - `PARTNER_ONLY`: on the file, unknown to mw (owner: ops).
  - `MW_ONLY`: mw COMPLETED, missing from the file after the partner's cut-off (owner: partner ops).
  - `STATUS_MISMATCH`: e.g. partner SUCCESS vs mw FAILED/REVERSED/UNKNOWN/MANUAL_REVIEW (owner: ops; candidate for a
    manual-credit or reversal request through mw-admin-api maker-checker, never automatic).
  - `AMOUNT_MISMATCH`, `LEDGER_MISSING` (mw COMPLETED, no core posting).
- **Output:** `mw.recon.result.v1` (spec 571, key `partnerId`) per file run: counts per outcome plus the exceptions. Contract
  under `contracts/events/mw-recon/`, owner in `contracts/OWNERS.yml`, consumers mw-admin-api / mw-audit-dw or
  `contracts/EXTERNAL.yml` where no consumer is built. Replace the 86 `EXTERNAL.yml` entry for `mw.partner.recon.v1` with mw-recon's `contracts.yml`.
- **Read API:** `GET /internal/v1/recon/runs`, `/runs/{id}/exceptions` (internal JWT as other internal APIs) for the admin console.
- **Compose:** a `mw-recon` block (profile `core`) with a memory limit; `./gradlew :composeCheck` stays within 8 GB.
- **Docs:** service `CLAUDE.md`, an ADR if a new decision row is added, `README.md` / runbook entry.

**Blocked by:** 86

**Status:** open

- [ ] `ReconMatchingIT` (Testcontainers): a file with matched, partner-only, mw-only, status-mismatch and amount-mismatch lines gives the expected exceptions and one `mw.recon.result.v1`
- [ ] A redelivered recon line or a re-sent file creates no duplicate exception (inbox)
- [ ] No txn state change and no money movement is produced by mw-recon (no producer of command topics in `contracts.yml`)
- [ ] `./gradlew :mw-recon:build :contractsLint :composeCheck :checkIsolation :scriptTests` green with Docker
- [ ] With the stack up: an API suite folder for the recon report passes
