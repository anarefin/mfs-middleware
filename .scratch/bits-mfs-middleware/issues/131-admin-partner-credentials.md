# 131: admin console: partner onboarding, credentials and certificates, response-code map (FRS ADM-01, ADM-02, ADM-08)

**What to build:** Close the partner-management gaps of the admin console. The existing `PartnerController` /
`PartnerConfigService` / `PartnerChangeHandler` (tickets 38, 83, 84) already cover enable/disable, timeouts, route
config, dry run and rollback. This ticket adds:
- a maintenance window;
- an explicit mock/real endpoint switch (FRS 10.4 criterion 3);
- credential and certificate upload into HashiCorp Vault (ticket 91), with an expiry dashboard;
- a screen for the partner-code → canonical-code map (`PartnerConfig.errorMap`).

**Principles:**
- Every change is a change request through `ChangeRequestService` (draft, diff, maker ≠ checker, `MW-AUTH-030`). Nothing is
  applied without a checker.
- A secret is write-only. Once stored in Vault it is never returned by any API, log or audit payload; only its
  metadata is (kid, fingerprint, subject, notAfter, uploadedBy).
- Switching an endpoint from mock to real changes config only (base URL, auth ref, cert ref), never code.

**Scope:**
- **mw-admin-api**
  - New `resourceType` values, each with its own `ResourceChangeHandler`, wired like `PartnerChangeHandler`:
    - `partner-maintenance`: window start/end, bn/en message, published on `mw.config.changed.v1`;
    - `partner-endpoint`: profile `MOCK` | `REAL`, baseUrl, authRef, certRef, validated by `PartnerRouteValidator`
      and the host guard;
    - `partner-credential`: API key, HMAC secret, client cert + key as PEM or PKCS#12. On approval it is written to
      Vault `secret/<adapter>/<partnerId>/<name>`; the request row keeps only a sealed reference, never the plain value;
    - `partner-error-map`.
  - New controller `CredentialController`:
    - `GET /admin/v1/credentials` returns metadata only;
    - `GET /admin/v1/credentials/expiring?days=30` feeds the dashboard.
  - Scheduler `CredentialExpiryJob` (ShedLock) raises alert events at 30, 7 and 1 days. It sends an ops email through
    `mw.notify.request.v1` with template `ops.credential.expiring` (bn/en seed).
  - Flyway: `credential_meta` table in the `admin` schema.
- **Adapters:** the maintenance window and the endpoint profile are honoured by the partner engine.
  - Inside a window the adapter answers `MW-SYS-PARTNER_MAINTENANCE`, carrying the bn/en message, and routing (ticket
    98) treats the partner as MAINTENANCE.
  - Done in all partner-engine copies (telco, billpay, bank and the new `mw-adapter-*`). The copies stay identical
    apart from the package.
- **Contracts:** extend `contracts/api/mw-admin-api/admin.v1.openapi.yaml` and the `mw.config.changed.v1` payload
  (owner mw-admin-api).
- **mw-admin-ui:**
  - Partner detail tabs "Maintenance", "Endpoint (mock/real)", "Credentials" and "Error codes".
  - A "Credentials expiring" dashboard card.
  - The upload form never echoes the secret back, and the field clears after submit.
- **Roles:** the `integration-maker` / `integration-checker` roles of `deploy/keycloak/realms/ops-realm.json` propose and
  approve; `viewer` sees metadata only.
- **Docs:** `docs/testing-guide.html` (partner switch walkthrough), `docs/runbook.md` (certificate rotation), the
  service `CLAUDE.md`.

**FRS IDs covered:** ADM-01, ADM-02, ADM-08, PLT-043 (maintenance message), CON-02, 10.4 criterion 3

**Blocked by:** 91, 97

**Status:** open

- [ ] Unit tests first: each new `ResourceChangeHandler` validates its draft; a credential change request's stored row and diff contain no secret material
- [ ] `CredentialVaultIT` (Testcontainers Vault): an approved upload lands in Vault; `GET /admin/v1/credentials` returns only metadata; a second read of the secret by the API is impossible
- [ ] `CredentialExpiryJobTest`: certificates expiring in 30, 7 and 1 days each raise exactly one alert per threshold
- [ ] Adapter route test (each partner-engine copy): during a maintenance window a PAY returns `MW-SYS-PARTNER_MAINTENANCE` with bn/en text and sends no partner call
- [ ] Switching `ocs` from `MOCK` to a second simulator base URL through maker-checker routes the next top-up there, with no restart
- [ ] `scripts/log-scan.sh` finds no uploaded secret in any container log
- [ ] `./gradlew :mw-admin-api:build :mw-adapter-telco:build :mw-adapter-billpay:build :mw-adapter-bank:build :contractsLint` green with Docker
- [ ] `cd ui/mw-admin-ui && pnpm test` green; new `e2e/70-partner-credentials.spec.ts` (`@partners @credentials`) passes with `scripts/ui-e2e.sh @credentials`
- [ ] API suite folder `65-admin-partner-credentials` passes (`scripts/api-test.sh 65`); requests tagged and mapped in `tests/api/TRACEABILITY.md`
