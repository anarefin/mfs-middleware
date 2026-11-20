# 112 split (2026-11-20) — read with 112-telco-four-ocs.md

The first implementer stopped with no commit. Copy this file onto the branch that implements a slice.

- **112a** (start now): four operator OCS route records (`gp-ocs`, `robi-ocs`, `teletalk-ocs`, `bl-ocs`), one auth scheme each, JSONata mappings and error maps, admin seeds kept in sync, four simulator profiles with seeded postpaid accounts, and Camel route tests. Keep `ocs` only as a documented legacy alias. No TopupFlow change, no `/preview` change, no amount rule.
- **112b** (after 112a, and after the amount decision below): orchestrator `TopupFlow` postpaid fetch at preview, prepaid denomination check, and `PostpaidTopupIT`. This slice may edit `mw-app-gateway` `/preview`.
- **112c** (after 112b): T1–T12 for two operators. T3/T4 drive STATUS. No timer sleep. T8 fallback. Traceability and API folder `46-topup-operators` only. `05-topup` still passes.

Amount decision (user, 2026-11-20): any amount from the minimum due through the outstanding bill. Preview returns both numbers. The quote stores the fetched bill, and confirm does not fetch again. An amount outside that range is refused before a hold. 112a does not implement this.
