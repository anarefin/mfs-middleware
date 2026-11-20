# 127 split (2026-11-20, implementer option 1, auto-split rule)

- **127a-recon-reports-queue** (done, 9ac2723a + security fix aaf3660d): reports CSV/XLSX/PDF + daily job, MT940 closing balance,
  trust position service, exception work queue (list/get/explain; explain only while OPEN/EXPLAINED, 409 MW-VAL-006, audit
  event via outbox). Open: operator identity from `X-Mw-Operator-Id` (mw-recon has no caller auth, same as reprocess).
- **127b-recon-exception-actions** (127a): mw-admin-api `RECON_EXCEPTION` maker-checker (recon-maker/recon-checker, FORCE_COMMIT /
  REVERSE, maker≠checker MW-AUTH-030, `ExceptionActionIT`), mw-recon ACTIONED/RESOLVED on outcome, `mw.recon.exception.v1`
  contract + outbox + mw-audit-dw consumer.
- **127c-trust-job-suite** (127b): core e-money total endpoint (core wallet contract: user decision needed), scheduled trust
  position job, Newman folder `61-recon-reports`, runbook "a day does not reconcile".
- **User decision 2026-11-20 (127c):** add `GET /core/v1/reports/emoney-total` to the core wallet contract and the core mock;
  the scheduled trust job calls it. Open item for Upay: the real core must provide the same endpoint.
