# 96 split (user decisions 2026-11-20) — read with 96-orchestrator-saga-frs.md

- **96a** (blocks 98, 104): inquiry schedule default 30s, 2m, 10m, 1h then MANUAL_REVIEW (replaces the 6h-repeat-to-24h
  rule); per-partner overrides keep only their own list of waits; MANUAL_REVIEW ops alert: mw-notify gets an ops recipient
  kind + ops address setting (migration, rule engine, bn/en templates, test).
- **96b** (after 96a): partner REVERSE after a refused commit. After the partner confirms the REVERSE, ask the core: if the
  hold still exists, release it and end REVERSED; if the hold is gone, end REVERSED with nothing to release; unclear core
  answer -> MANUAL_REVIEW. Pending leg tracked explicitly (column or timer step name, implementer's choice). `PartnerReversalIT`.
- **96c** (after 96b): reversing a completed TOPUP/bill payment: **partner REVERSE first, core reversal only after the
  partner confirms**; partner refusal -> MANUAL_REVIEW. API folder 30-saga-frs, FRS traceability, spec 7.4.
