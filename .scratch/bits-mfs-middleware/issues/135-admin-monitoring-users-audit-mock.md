# 135: admin console: monitoring views, users and roles, audit viewer, mock control screen, maker-checker rules (FRS ADM-13..16, 9.2, 9.3)

**What to build:** The remaining console areas and the FRS maker-checker rules:
- live monitoring per channel, partner and biller;
- user and role management through Keycloak;
- a general audit viewer of operator actions;
- a non-production mock control screen over the unified `/mock-control` API (tickets 102, 103);
- the QA/Tester role;
- a 24 h change-request expiry;
- generic rollback of any resource to any earlier approved version.

**Principles:**
- No Prometheus or Grafana stack is in scope (FRS 1.2). Monitoring reads the services' own metrics endpoints and the
  orchestrator's query API, aggregated in mw-admin-api.
- A security admin can't approve their own role change (FRS 9.2). Role changes are maker-checker like every other
  sensitive change.
- The mock control screen and its API proxy are absent from any production build. `MW_MOCK_CONTROL_ENABLED` defaults
  to false, and its route exists only with the test-only APISIX routes (`MW_E2E_ROUTES=1`). This is the same rule as
  MCT-08.

**Scope:**
- **mw-admin-api**
  - `MonitoringController` `GET /admin/v1/monitoring?dimension=channel|partner|biller&window=1h|24h`: volume, success
    rate, p95 latency, failures, circuit state. Built from the orchestrator's internal stats endpoint (added there,
    owned by the orchestrator) and each adapter's `/actuator/health` partner states.
  - `UserController` over the Keycloak admin API for the `ops` realm: list users, assign and remove roles (as a
    `resourceType` `ops-user-role` with maker-checker), force TOTP reset.
  - `AuditViewerController`: operator actions with before/after values, through the mw-audit-dw query API
    (filters actor, action, resource, time).
  - `MockControlProxyController`, conditional on `MW_MOCK_CONTROL_ENABLED`: scenarios, journal and reset per party.
  - Change requests:
    - expiry TTL default **24 h** (`application.yml`, today 72 h) via the existing `ChangeRequestExpiryJob`;
    - generic `POST /admin/v1/resources/{type}/{id}/rollback?toVersion=n`, which creates a change request whose draft
      is version n and so itself goes through maker-checker. Built in `ChangeRequestService` for every resource
      handler that keeps versions (partner routes, menus, templates, rules, billers, clients).
- **Keycloak** `deploy/keycloak/realms/ops-realm.json`:
  - new `qa-tester` role (mock control only, refused in prod config);
  - `security-admin` gets user/role management and the audit viewer;
  - seed persona `seed-qa-tester` in mw-seeder (manifest in `build/seed/manifest.json`).
- **mw-admin-ui:** `/monitoring` (live, auto-refresh), `/users`, `/audit`, `/mock-control` (hidden when disabled),
  and a "Roll back to version" action on resource history pages.
- **Docs:** `docs/testing-guide.html`, `scripts/creds.sh` (new persona), README knob `MW_MOCK_CONTROL_ENABLED`
  (checked by `scripts/test/test_repo_docs.sh`).

**FRS IDs covered:** ADM-13, ADM-14, ADM-15, ADM-16, 9.2 roles (QA/Tester, Security Admin), 9.3 (24 h expiry, rollback to any version), MCT-08

**Blocked by:** 103, 130

**Status:** open

- [ ] Unit tests first: expiry at 24 h; rollback to version n produces a draft equal to version n and needs a different checker; a security admin approving their own role change gets `MW-AUTH-030`
- [ ] `UserRoleChangeIT` (Keycloak Testcontainer): an approved role grant shows up in the user's next token
- [ ] `MonitoringControllerTest`: success rate, p95 and circuit state come out right from canned stats and health inputs
- [ ] With `MW_MOCK_CONTROL_ENABLED=false` the mock-control endpoints return 404 and the UI hides the menu entry; with the production APISIX route set there is no route
- [ ] `qa-tester` can set a TIMEOUT scenario on `ocs` and see it in the journal; `viewer` gets 403
- [ ] Vitest tests; `pnpm test` green; Playwright `e2e/90-monitoring.spec.ts` (`@monitoring`), `91-users.spec.ts` (`@users`), `92-audit.spec.ts` (`@audit`), `93-mock-control.spec.ts` (`@mock`)
- [ ] `./gradlew :mw-admin-api:build :mw-orchestrator:build :mw-seeder:build :contractsLint :scriptTests` green with Docker; `scripts/ui-e2e.sh "@monitoring|@users|@audit|@mock"` green
- [ ] API suite folder `69-admin-monitoring-users-mock` passes (`scripts/api-test.sh 69`)
