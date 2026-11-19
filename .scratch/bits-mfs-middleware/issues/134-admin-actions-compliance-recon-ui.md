# 134: admin console: extra manual actions, recon workbench, AML holds, notification log and DLQ views (FRS ADM-10, ADM-11, ADM-12, NOT-06, NOT-08)

**What to build:** The operator screens for the back-office services built in M12, and the two missing manual
actions:
- resend a notification;
- release an AML hold.

ADM-10 already has INQUIRE, FORCE_COMMIT/RELEASE, REVERSE, wallet unlock and PIN reset (ticket 37, `TransactionController`).

**Principles:**
- Every action carries a reason and goes through the existing `ChangeRequestService` with maker ≠ checker.
- mw-recon and mw-compliance never move money themselves. An approved recon action (force complete, reverse, raise
  dispute) runs through the normal `mw.txn.action.v1` flows (ticket 37 pattern). An AML release runs through the core
  REST API and `auth.wallet-lock.v1`, applied by mw-compliance.
- KYC and AML data is shown unmasked only to `compliance-officer`. Every unmask is audited (the `RevealAudit` pattern).

**Scope:**
- **mw-admin-api**
  - New `resourceType` values with handlers:
    - `notification-resend` (resend by notification id; never for OTP);
    - `aml-release` (txn hold or wallet block);
    - `recon-action` (`FORCE_COMPLETE` | `REVERSE` | `RAISE_DISPUTE` on a recon exception).
  - Read proxies over internal APIs (internal JWT), with no schema access:
    - mw-recon `/internal/v1/recon/runs`, `/runs/{id}/exceptions`, reports download (ticket 127);
    - mw-compliance holds and blocks (ticket 125);
    - mw-notify log search (ticket 111) and failed/DLQ messages (NOT-06).
- **mw-admin-ui**
  - `/recon`: runs, an exceptions queue grouped by class (MATCHED, MISSING_IN_PARTNER, MISSING_IN_UPAY,
    AMOUNT_MISMATCH, STATUS_MISMATCH) with suggested action, and report downloads (CSV/XLSX/PDF).
  - `/compliance`: AML holds and blocks, with release under approval.
  - `/notifications`: log search by MSISDN, txnId, channel, status and date, OTP bodies masked; a DLQ tab with resend.
  - Transaction detail gets "Resend notification" and "Release hold" actions.
- **Roles:**
  - `recon-maker` / `recon-checker`: recon actions;
  - `compliance-officer`: AML release, proposing; a second `compliance-officer` or `ops-checker` approves (config);
  - `ops-maker` / `ops-checker`: resend.
  - Update `deploy/keycloak/realms/ops-realm.json` grants accordingly.
- **Docs:** `docs/testing-guide.html` (recon day walkthrough, AML hold release), `docs/runbook.md`.

**FRS IDs covered:** ADM-10, ADM-11, ADM-12, REC-05, REC-06 (download), CMP-02 (release), NOT-06 (console visibility), NOT-08 (view)

**Blocked by:** 125, 127, 111

**Status:** open

- [ ] Handler unit tests first: resend of an OTP is refused; `recon-action` REVERSE on a MATCHED line is refused; a maker cannot approve their own action
- [ ] `ReconActionIT`: an approved FORCE_COMPLETE on a STATUS_MISMATCH exception produces one `mw.txn.action.v1` command and marks the exception RESOLVED; a replay produces none
- [ ] `AmlReleaseIT`: an approved release unfreezes the hold on core-mock and sends UNLOCK on `auth.wallet-lock.v1` for a BLOCK
- [ ] Unmasked KYC/AML fields are returned only for `compliance-officer`, and each reveal writes an audit event
- [ ] Vitest tests for the queues and filters; `pnpm test` green
- [ ] Playwright `e2e/80-recon.spec.ts` (`@recon`), `81-compliance.spec.ts` (`@compliance`), `82-notifications.spec.ts` (`@notify`) against the seeded stack with an injected mismatch and an AML HOLD
- [ ] `./gradlew :mw-admin-api:build :contractsLint` green with Docker; `scripts/ui-e2e.sh "@recon|@compliance|@notify"` green
- [ ] API suite folder `68-admin-backoffice-actions` passes (`scripts/api-test.sh 68`)
