# 112: mw-adapter-telco: four operator OCS profiles, postpaid, reverse (FRS UC-08, EXT-08, FL-03)

**What to build:** Replace the single `ocs` partner record and its aliases (gp/robi/banglalink/teletalk → ocs) with
four real partner records, one per operator. Each has its own payload style, error codes and simulator profile.
Postpaid gets a bill fetch before pay (FL-03). REVERSE and STATUS use the operation added in ticket 97. The preview
checks the amount against the operator's denominations.

**Principles:**
- The operator is chosen by the routing module (ticket 98: prefix + MNP), never by the channel.
- PAY is never resent. A timeout goes to STATUS (PLT-031).

**Scope:**
- `deploy/partner-routes/` and the admin-api dev seeds (`partner-seeds/dev`; keep them in sync with the adapter
  seeds, a known open item): route records `gp-ocs`, `robi-ocs`, `teletalk-ocs`, `bl-ocs`. Each has its own JSONata
  mappings, `errorMap` to `MW-PTR-<partner>-*`, and auth (api-key / hmac / oauth2-cc / basic; one different scheme
  each, to exercise ticket 87's Kamelets).
- Remove the `partner-aliases` telco map, or keep it only as a legacy fallback (document which).
- Operations per record: PAY (prepaid), INQUIRY (postpaid bill fetch), PAY (postpaid), STATUS_QUERY, REVERSE.
- Orchestrator `TopupFlow`:
  - postpaid adds a bill-fetch step at preview time (app `/preview` from 105 returns the outstanding amount);
  - the confirmed amount must match the fetched bill or be ≥ the minimum;
  - prepaid checks `mw.topup.denominations.<operator>`, a config list.
- `services/mw-simulators`: `OcsController` gets four profiles (different JSON shapes, status codes, error
  vocabularies) and seeded postpaid accounts. It is stateful: a reversed recharge can't be reversed twice (MCK-02).
  Scenarios run through `/mock-control/ocs-<op>/scenarios` (102).
- Docs: `services/mw-adapter-telco/CLAUDE.md`, simulator trigger table, `docs/concepts/camel-partner-routes.md` example.

**FRS IDs covered:** UC-08, FL-03, EXT-08, CON-02 (four partners by config only), MCK-02, PLT-042 (consumes routing)

**Blocked by:** 97, 98, 102

**Status:** open

- [ ] Route tests (Camel) per operator: mapping round-trip and error-code mapping from recorded mock payloads
- [ ] `PostpaidTopupIT`: fetch → pay; amount below the minimum is refused with `MW-VAL-*` before any hold
- [ ] T1–T12 (FRS 10.2) for TOPUP against at least two operators, including T3/T4 through STATUS and a T2 mapped bn/en error
- [ ] T8: the routing fallback is used when `gp-ocs` is DOWN via `/mock-control/ocs-gp` (if a fallback is configured; else fast `MW-SYS-PARTNER_CIRCUIT_OPEN`)
- [ ] Reverse of a completed recharge is idempotent; a second reverse returns the first outcome
- [ ] `./gradlew :mw-adapter-telco:build :mw-orchestrator:build :mw-simulators:build :mw-admin-api:build :contractsLint` green with Docker
- [ ] API suite folder `46-topup-operators` passes; `05-topup` still passes
