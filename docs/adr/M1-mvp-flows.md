# M1: MVP flows

Status: Accepted, 2026-11-03. Refined at the M1 and M2 gates (CASH_OUT is customer-initiated with the agent as payee; reversal is core-only).

## Context

The MVP must prove every core pattern (simple transfer, hold/commit, partner request/reply, partner callback, inbound credit, merchant payment) without building every product.

## Decision

MVP flows: `P2P`, `CASH_IN`, `CASH_OUT`, `TOPUP`, `BILL_PAY`, `ADD_MONEY_BANK` (C2W), and `MERCHANT_PAY` through web checkout and the merchant app. Also in: bulk disbursement from the org portal and operator reversal of a completed transaction.

## Consequences

- Each flow has success, business failure, timeout -> TSQ -> commit and timeout -> TSQ -> release scenarios in the e2e suite (criterion 1).
- Partner-side reversal, partial reversal and the other RFP flows are deferred.

## Where in the repo

- `tests/e2e/TRACEABILITY.md`
- `tests/e2e/src/test/resources/features`
- spec "MVP scope"
