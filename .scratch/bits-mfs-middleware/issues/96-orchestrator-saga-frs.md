# 96: Saga to FRS: inquiry schedule, MANUAL_REVIEW alert, partner reversal step (PLT-023..025)

**What to build:** Three saga changes the FRS asks for: the default status-inquiry schedule, an alert the moment a transaction
enters `MANUAL_REVIEW`, and a generic partner REVERSE step (today reversal is core-only) that is idempotent and retried until the
partner acknowledges.

**Scope (today -> change):**
- `service/saga/InquirySchedule` + `config/InquiryProperties`: default 30s,1m,2m,5m,15m,1h then 6h to 24h -> FRS default
  30s,2m,10m,1h, then `MANUAL_REVIEW`; still configurable; `deploy/dev.env.defaults` short timers kept.
- Alert: on entry to `MANUAL_REVIEW` publish an ops alert (`mw.txn.status.v1` already carries the state; add a rule in mw-notify
  for an ops email/SMS via `mw.notify.request.v1`, template bn/en) and a counter `mw_manual_review_entered_total`; `service/SagaAlerts`
  holds the hook. `ManualReviewAgeingService` stays.
- Partner reversal: `Step` gains an optional partner compensation; flows with a partner leg (TOPUP, BILL_PAY, ADD_MONEY_BANK, later
  W2B/NPSB) declare it. After a partner success followed by a later failure (e.g. core commit rejected, or an approved manual
  reversal of a COMPLETED txn with a reversible partner), the saga sends `REVERSE` on `mw.partner.request.<family>.v1`
  (operation added in 97), keyed `reverse:<txnId>`, resends on the reversal schedule until `REVERSED`/definite reply; no answer
  -> `MANUAL_REVIEW`. States `REVERSING`/`REVERSED` reuse the existing ones (FRS mapping in `docs/frs-traceability.md`).
- Definite partner rejection keeps ending `FAILED` (documented mapping FRS REVERSED vs repo FAILED after release).
- `contracts/events/mw-orchestrator/mw.partner.request.v1` operation enum `REVERSE` (+ samples); `mw.partner.reply.v1` outcome for reversal.
- `services/mw-orchestrator/CLAUDE.md` (reversal no longer core-only), spec 7.4 note.

**FRS IDs covered:** PLT-023, PLT-024, PLT-025, PLT-022 (compensation in reverse order with partner leg), T5 prerequisite

**Blocked by:** 95, 97

**Status:** open

- [ ] `InquiryScheduleTest` first: default offsets 30s/2m/10m/1h, then MANUAL_REVIEW
- [ ] `SagaMachine` tests for every new transition (partner compensate, reverse ack, reverse exhausted)
- [ ] `PartnerReversalIT`: partner success + core commit failure -> REVERSE sent once per attempt with the same key, ack -> REVERSED, money released; duplicate ack ignored (inbox)
- [ ] Alert IT: entering MANUAL_REVIEW emits one notify request and increments the counter
- [ ] `./gradlew :mw-orchestrator:build :mw-notify:build :contractsLint` green with Docker
- [ ] New API folder `30-saga-frs` (manual review alert, partner reversal of a top-up via mock control/magic value) passes; `12-reversal` still passes
