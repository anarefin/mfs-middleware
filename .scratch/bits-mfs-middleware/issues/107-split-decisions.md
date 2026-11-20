# 107 split (user decisions 2026-11-20) — read with 107-app-nonfinancial.md

The first implementer stopped with no commit. Copy this file onto the branch that implements a slice.

- **107a** (start now): saved billers and favourites in mw-app-gateway's own schema; customer inbox; test-console pages; append-only Newman rows in folders 28 and 29. No profile, limits, or statement.
- **107b** (after 107a merges; same app-gateway module): profile (WAL-01), limits (WAL-05), statement (WAL-12, max 90 days). mw-app-gateway calls core directly.

User decisions:

1. Core caller: add `mw-app-gateway` to core `allowed-clients`. It calls WAL-01, WAL-05, and WAL-12 itself. Do not add an orchestrator proxy.
2. Encryption: saved billers and favourites are not encrypted. No `KEK_APP_PREF`, no change to `KEK_COLUMN`, no mw-crypto call. Store the account number and the counterparty MSISDN in the app-gateway schema. Logs, audit, and admin views still mask them (`@Sensitive`: account last 4, MSISDN `0171****789`). 107c is dropped.

Inbox path (111 already owns `GET /internal/v1/notifications` as the admin log, caller `mw-admin-api`, MSISDN masked). Customer inbox is a new notify path: `GET /internal/v1/inbox` and `POST /internal/v1/inbox/{id}/read`, caller `mw-app-gateway` only, OTP bodies omitted, rows limited to the requested MSISDN. Contract owner stays mw-notify.
