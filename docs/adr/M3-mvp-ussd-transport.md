# M3: USSD: SMPP and HTTP both built, GP live over SMPP

Status: Accepted, 2026-11-03. M3 gate: one APISIX consumer and IP allow-list per MNO, per-MSISDN quota.

## Context

D7 promises a transport switch by configuration; the MVP has to prove it.

## Decision

Both transports are implemented. GP runs over SMPP in the MVP; Robi, Banglalink and Teletalk are configured for HTTP and ready; HTTP mode is proven against the USSD gateway simulator.

## Consequences

- The e2e suite drives robi over HTTP and gp over SMPP with the same menus (criterion 5).
- The real GP SMPP account values are placeholders until the MNO provides them.

## Where in the repo

- `services/mw-ussd/CLAUDE.md`
- `tests/e2e/src/test/resources/features/ussd-smpp.feature`
