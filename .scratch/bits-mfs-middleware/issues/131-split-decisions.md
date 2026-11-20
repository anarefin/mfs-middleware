# 131 split (user decisions 2026-11-20) — read with 131-admin-partner-credentials.md

- **131a** (mw-admin-api backend): credential change-request type writing to Vault as a **field of the adapters' existing
  secret** `secret/<adapter>/<partnerId>` (ADR D28, `partner/CredentialRef`); provisioning gives mw-admin-api
  **write-only** access to `secret/data/mw-adapter-*/*` (`deploy/provision/lib.sh`); `credential_meta` (next free V),
  `CredentialController`, `CredentialExpiryJob` + `ops.credential.expiring` template seeded in mw-notify, error-map
  change type, `admin.v1` contract, `CredentialVaultIT`, Keycloak roles, CLAUDE.md, runbook cert-rotation section.
  API suite folder **45** (not 65).
- **131b** (adapter engines, all copies identical): maintenance window (start/end, bn/en message, refusal
  `MW-SYS-PARTNER_MAINTENANCE`, extends 97's flag and `mw.config.changed.v1`) and endpoint profile: routes use an
  **`{{endpoint}}` placeholder** filled from `endpoints {MOCK, REAL}` in the partner config; switching is a maker-checker
  config change, no route edit; admin change types for both. Run **before 115/118-121** so new adapters copy the new engine.
  The "routing treats the partner as MAINTENANCE" part waits for 98.
- **131c** (after 131a, 131b): admin UI partner tabs, expiring-credentials card, Playwright `70-partner-credentials`,
  testing-guide walkthrough.
