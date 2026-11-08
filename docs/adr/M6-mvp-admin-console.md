# M6: Admin console: operations essentials

Status: Accepted, 2026-11-03. The wallet unlock action was added at the M2 gate (ticket 52).

## Context

Operators must be able to find and fix transactions from day one, under four-eyes control.

## Decision

Login with MFA, transaction search by txnId or MSISDN and saga timeline with audit, manual TSQ and reversal with maker-checker, partner enable/disable and timeouts, USSD menu and SMS template editing (all through change requests), wallet unlock.

## Consequences

- `mw-admin-api` enforces roles and maker != checker; the UI only hides buttons.
- Recon, AML and the other spec 15.2 screens are deferred.

## Where in the repo

- `services/mw-admin-api/CLAUDE.md`
- `ui/mw-admin-ui/CLAUDE.md`
- `tests/api/src/14-operator-console` and `ui/mw-admin-ui/e2e` (formerly the Cucumber feature operator-console.feature)
