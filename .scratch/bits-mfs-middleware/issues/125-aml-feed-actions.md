# 125: AML feed, response handling and real-time pre-check (FRS UC-21, EXT-05, CMP-01, CMP-02, CMP-03, T11)

**What to build:** The AML part of `mw-compliance` (ticket 124): an hourly transaction file to the AML vendor's SFTP
folder, the reader of the vendor's response files that applies ALERT / HOLD / BLOCK through the core and `mw-auth`,
and optional real-time pre-check rules the orchestrator calls before funds move.

**Principles:**
- Compliance never blocks a payment by failing: the file feed runs off events; only the opt-in real-time pre-check sits
  in the payment path, with a short timeout and a configurable fail-open / fail-closed per rule set (default
  fail-open + alert, decided in the ticket ADR row).
- Every applied action is audited (`mw.audit.event.v1`, actor `SYSTEM:aml`, the response file and line) and visible in
  the console (screen in ticket 134). Releasing a HOLD or BLOCK needs maker-checker (ADM-12, ticket 134).
- A response file is processed once (filename + sha256 register); a re-run needs an explicit operator action.
- No Camel: `client/SftpClient` (sshj) with Resilience4j retry; paths and credentials from Vault.

**Scope:**
- **File format** `contracts/files/aml/` (owner mw-compliance in `contracts/OWNERS.yml`; finalises the draft of 123):
  pipe-delimited transaction file with header, detail rows (txnId, type, channel, masked/tokenised parties, amount,
  time Asia/Dhaka) and a control/trailer record (row count, total amount, sha256); response file lines
  `ALERT|HOLD|BLOCK, txnId or walletId, rule, reference`.
- **Feed:** a projection `compliance.aml_txn` built from `mw.txn.status.v1` terminal states (inbox dedup);
  `scheduler/AmlFeedJob` (ShedLock, interval config, default hourly) writes and uploads the file, marks rows sent.
- **Responses:** `scheduler/AmlResponseJob` reads the outbox folder; HOLD → core REST hold on the txn funds (ticket 94
  hold API, reason `AML`); BLOCK → core REST wallet status LOCKED **and** `auth.wallet-lock.v1` `LOCK` (sessions
  revoked); ALERT → logged + event only. Customer notified for HOLD/BLOCK (mw-notify template, bn/en).
- **Real-time pre-check (CMP-03):** `POST /internal/v1/aml/pre-check` with rules velocity (count/amount per window in
  Redis), single-amount threshold, watch-list MSISDN; returns ALLOW or STOP with `MW-CMP-001` (new namespace documented
  in the root `CLAUDE.md` error list and `docs/frs-traceability.md` ↔ FRS `CMP-001`). Orchestrator calls it in the
  validate step when `mw.orchestrator.aml.pre-check.enabled`, off the listener thread.
- **Events:** `mw.compliance.action.v1` (HOLD/BLOCK/ALERT applied) under `contracts/events/mw-compliance/`; consumers
  mw-admin-api (holds view) and mw-audit-dw.
- **Docs:** `services/mw-compliance/CLAUDE.md`, runbook (reprocess a file), traceability rows.

**FRS IDs covered:** UC-21, EXT-05, CMP-01, CMP-02, CMP-03, PLT-034 (AML feed async), FL-14 (AML feed), T11.

**Blocked by:** 124, 123, 96

**Status:** open

- [ ] `AmlFileWriterTest`: header, rows and trailer totals; parties tokenised/masked; sample validates against `contracts/files/aml/`
- [ ] `AmlResponseParserTest`: each line kind; a malformed line goes to an error report, the rest still apply
- [ ] `AmlFeedIT` (Testcontainers SFTP + Kafka + PG): terminal txns → one hourly file; a rerun of the same window uploads nothing new
- [ ] `AmlResponseIT`: HOLD freezes funds on core-mock, BLOCK locks the wallet and revokes sessions (event on `auth.wallet-lock.v1`), the same file twice applies once
- [ ] `AmlPreCheckTest`: velocity, threshold and watch-list rules; timeout behaves per fail-open/closed config
- [ ] T11 for P2P, cash-out and top-up: AML HOLD response → funds frozen, customer SMS, action visible via admin-api — API suite folder `59-aml`
- [ ] `./gradlew :mw-compliance:build :mw-orchestrator:build :contractsLint :composeCheck` green with Docker
