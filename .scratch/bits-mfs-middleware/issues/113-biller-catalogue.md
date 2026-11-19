# 113: Biller catalogue in the database, with bill month and amount rules (FRS FL-04, FL-05, ADM-05)

**What to build:** Move the biller list out of the static `mw.billers.*` config (10 billers today) into a
database catalogue. It must hold about 17 utility and about 40 other billers. Each entry carries its aggregator
mapping, input fields, validation regex, amount rule (FIXED, MINIMUM, OPEN), bill-month support and a fallback
aggregator. The catalogue is edited through mw-admin-api maker-checker and published on `mw.config.changed.v1`;
the app, USSD and billpay read it from there.

**Principles:**
- One owner of the catalogue: `mw-admin-api` stores and publishes it. `mw-adapter-billpay`, `mw-app-gateway` and
  `mw-ussd` keep their own read projections (no cross-schema reads).
- The aggregator choice at pay time comes from the routing module (ticket 98), which reads the catalogue's primary
  and fallback aggregators.

**Scope:**
- `mw-admin-api`: Flyway `biller` table (code, name bn/en, category UTILITY/OTHER, primary aggregator, fallback
  aggregator, fields jsonb `[ {name, label bn/en, regex, required} ]`, amount rule + min/max, bill month
  required, enabled, maintenance text bn/en, version), plus change-request resource `biller` (create, edit, enable,
  disable) with diff and maker-checker. Publishes `mw.config.changed.v1` with `resourceType=biller` (contract owned by
  mw-admin-api: add the payload type with samples).
- Seed: `services/mw-admin-api/src/main/resources/biller-seeds/dev/billers.csv` (versioned) with about 57 billers mapped to
  billagg, sslcommerz, ekpay, agg3, agg4 (the last four go live in ticket 114). mw-seeder applies it if that matches
  the current seeding pattern.
- `mw-adapter-billpay`: replace the static catalogue with a projection consumer. `GET /app/v1/billers` and
  `fetch-bill` validate fields by regex and accept `billMonth` (YYYYMM).
- `mw-app-gateway` / `mw-ussd`: biller lists and input prompts come from the projection. The USSD Pay Bill menu
  uses a DYNAMIC node over it.
- Orchestrator `BillPayFlow`: enforces the amount rule (FIXED must equal the fetched amount; MINIMUM ≥ min; OPEN
  within min/max).
- Docs: CLAUDE.md of the touched services, admin resource list, `docs/frs-traceability.md` row.

**FRS IDs covered:** FL-04, FL-05, ADM-05 (API), PLT-043 (biller disable message)

**Blocked by:** 98

**Status:** open

- [ ] Unit: field regex validation; amount rule checks for FIXED / MINIMUM / OPEN
- [ ] `BillerChangeRequestIT` (admin-api): create a biller, approve with a second user, `mw.config.changed.v1` published
- [ ] `BillerProjectionIT` (billpay): the projection picks the change up within 30 s, and the new biller appears in `GET /app/v1/billers`
- [ ] A disabled biller returns its bn/en maintenance text to app and USSD
- [ ] Seeding loads 57 billers; re-running is idempotent
- [ ] `./gradlew :mw-admin-api:build :mw-adapter-billpay:build :mw-app-gateway:build :mw-ussd:build :mw-orchestrator:build :contractsLint` green with Docker
- [ ] API suite folder `47-biller-catalogue` passes; `06-billpay` still passes
