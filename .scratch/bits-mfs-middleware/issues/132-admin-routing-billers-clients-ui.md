# 132: admin console: channel clients, routing rules and biller catalogue screens (FRS ADM-03, ADM-04, ADM-05)

**What to build:** The admin UI screens, and any missing admin-api read endpoints, for three resources whose
back-end change-request handlers are built earlier:
- channel clients (ticket 100);
- routing rules (ticket 98);
- the biller catalogue (ticket 113).

Includes "test a rule against a sample request" (ADM-04).

**Principles:**
- The UI only proposes changes. The existing approvals inbox (`e2e/20-approvals.spec.ts`) applies them, after a
  checker approves the diff.
- Rule testing is a dry evaluation. It moves no money and sends nothing to a partner.

**Scope:**
- **mw-admin-api**
  - `POST /admin/v1/routing-rules/evaluate` with body `{txnType, product, billerCode|bankCode|operator, amount,
    channel, msisdn?}`. It returns the primary and fallback connectors in order, the matched rule id, the
    MNO resolved from prefix and MNP, and each candidate's current health (UP/DEGRADED/DOWN/MAINTENANCE). It calls the
    orchestrator's routing module over its internal API (internal JWT as other internal calls); it never reads the
    `txn` schema.
  - List and detail endpoints for `channel-client`, `routing-rule` and `biller`, if 98/100/113 did not add them.
  - Pagination and filters on these lists (family, enabled, aggregator).
- **mw-admin-ui (Next.js, `src/app/(console)`)**
  - `/clients`: register apps, MNO binds, merchants and portals. Fields: auth method, allowed APIs, IP allow-list,
    rate limit, daily quota.
  - `/routing`: rule table with ordered fallbacks; "Test a rule" panel showing the evaluation result.
  - `/billers`: catalogue (about 17 utility and 40 other billers) with input fields, validation regex, amount rule
    (fixed, minimum or open), aggregator mapping and fallback aggregator, and enable/disable.
  - Each form has client-side validation that mirrors the server's body schema. A refused submit keeps the user's
    input (the regression of c17e72f6).
  - Masked data only for `viewer`.
- **Roles:**
  - `integration-maker` / `integration-checker`: clients and routing rules;
  - `ops-maker` / `ops-checker`: billers;
  - `viewer`: read only.
- **Docs:** `docs/testing-guide.html` section for each screen.

**FRS IDs covered:** ADM-03, ADM-04, ADM-05, PLT-003, PLT-040, PLT-041 (console side), FL-05 (console side)

**Blocked by:** 98, 100, 113

**Status:** open

- [ ] `@WebMvcTest` for the evaluate endpoint first: rule match, fallback order, MNP override and a DOWN primary all give the expected ordered list
- [ ] `RoutingEvaluateIT`: evaluating a rule writes no change request and produces nothing on any command topic
- [ ] Vitest component tests for the three forms (validation, kept input on a refused submit, read-only for `viewer`); `pnpm test` green
- [ ] Playwright `e2e/71-clients.spec.ts`, `72-routing.spec.ts`, `73-billers.spec.ts` (`@routing`, `@billers`, `@clients`): a maker proposes, a second persona approves, and the change is visible; maker ≠ checker is refused
- [ ] A biller added through `/billers` and approved shows up in the app's biller list without a restart (cache refresh within 30 s)
- [ ] `./gradlew :mw-admin-api:build :contractsLint` green with Docker; `scripts/ui-e2e.sh "@routing|@billers|@clients"` green
- [ ] API suite folder `66-admin-routing-billers-clients` passes (`scripts/api-test.sh 66`)
