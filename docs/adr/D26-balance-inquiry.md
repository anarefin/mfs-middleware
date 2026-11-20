# D26: Balance inquiry over the saga, the balance passed through and never stored

Status: Accepted, 2026-11-16.

## Context

Customers could send money, cash out, recharge and pay bills on USSD and in the app, but could not ask
for their balance. The core owns balances and the middleware must never compute or store one (spec 3,
rule 1). The core contract (6.3) had no read of a balance: every command moves or reserves money, or
asks about a transaction. Every channel feature reaches the core the same way: the channel asks
`mw-auth` for the transaction assertion after the PIN check, publishes `mw.txn.request.v1`,
`mw-orchestrator` sends core commands, and the channel waits for `mw.txn.status.v1`.

## Decision

- **A transaction type that moves no money.** `BALANCE` joins the TxnType list. A balance request keeps
  the request's required fields: `amount` `{0, BDT}` and `creditParty` = `debitParty` = the customer's own
  wallet (type WALLET). `mw.txn.request.v1` checks the amount and the party types for BALANCE; the
  orchestrator checks that both parties are the same wallet. Amount 0 is allowed for BALANCE only.
- **PIN required.** The channel gets `mw-auth`'s assertion (D22) for BALANCE with amount 0 and payee =
  payer; `mw-auth` allows amount 0 for BALANCE only. The txnHash is computed as for every transaction.
- **A new core command.** `BalanceInquiry` on `core.wallet.command.v1` (commandId `<txnId>:balance`)
  carries the transfer-shaped payload (txnType, both parties, amount 0, assertion, idempotencyKey), so the
  core verifies the assertion as for a transfer. The core answers `BalanceReported` (`walletId`,
  `available` = balance minus open holds, `asOf`) or `BalanceInquiryRejected` (`WALLET_NOT_FOUND`,
  `WALLET_NOT_ACTIVE`, `MW-AUTH-046`).
- **Saga.** `BalanceFlow`: RECEIVED → VALIDATING (BalanceInquiry sent) → COMPLETED on `BalanceReported`,
  FAILED with `MW-CORE-<reason>` on a rejection, FAILED with `MW-TMO-001` (retryable) when the core does
  not answer in time: nothing moved, so there is no UNKNOWN and no status inquiry. No new status value.
- **The balance passes through.** `mw.txn.status.v1` gets an optional `balance` (`available`, `asOf`),
  set only on a BALANCE COMPLETED status, carried from the core event to the status message in memory.
  It is never written to the saga record, the timeline, the audit trail, the wallet history index or a
  channel's projection. The BALANCE transaction row itself (amount 0) stays, so operators see that a
  check happened.
- **Channels.** USSD main menu option `5 ব্যালেন্স / Balance` → PIN → the available balance on the
  screen (seed menu `main-v3`). The app gets `POST /app/v1/balance` (app.v1 1.6.0): signed, PIN, a new
  `Idempotency-Key` per check, 200 `{txnId, available, asOf}`, 504 `MW-TMO-001` when no answer came in
  the budget (no 202: a balance check has nothing to finish).
- **No SMS.** No notification rule for BALANCE; a USSD check that gets no answer in time says "please
  try again", never "result by SMS".

## Consequences

- Every balance check is a transaction row and an audit trail of status changes (without the amount),
  which costs a few rows per check; it also gives operators the trail of who looked when.
- A balance check is not in the app history (`GET /app/v1/transactions`) or the app's detail projection.
- Kafka retains the status message with the balance for the topic's retention; the middleware's own
  stores never hold it.
- The core team must confirm the `BalanceInquiry` command (open point 1 of 6.3, as for the rest).
- The COMPLETED status sits in the orchestrator's transactional outbox (`txn.outbox`) until it is relayed
  and purged, so the balance is in that table for that short time; it is transport, not a store.
- A repeated request with the same idempotency key answers without a balance (nothing was kept): the
  app answers 504 `MW-TMO-001`, so every check uses a new key (USSD derives one per attempt).
- The core refuses a check on a wallet that is not ACTIVE (LOCKED included) with `WALLET_NOT_ACTIVE`.
- The main menu must fit one 70-character UCS-2 screen: with option 5 the main menu says
  `3 রিচার্জ` / `3 Recharge` (the recharge screen keeps "Mobile Recharge"). mw-admin-api's menu validator
  accepts the BALANCE flow, and mw-seeder's first admin version of the menu is the v3 copy.

## Where in the repo

- spec decision D26, sections 5.2, 6.3, 9.1, 9.2
- `contracts/common/defs.v1.schema.json` (TxnType), `contracts/api/mw-core-mock/core-wallet.v1.openapi.yaml` (balance inquiry; the command payload schema was retired in ticket 95b),
  `contracts/events/mw-core-mock/core.event.v1/BalanceReported.payload.schema.json`,
  `contracts/events/mw-core-mock/core.event.v1/BalanceInquiryRejected.payload.schema.json`,
  `contracts/events/mw-orchestrator/mw.txn.status.v1.schema.json` (`balance`),
  `contracts/api/mw-app-gateway/app.v1.openapi.yaml` (1.6.0), `contracts/api/mw-auth/auth-internal.v1.openapi.yaml`
- `services/mw-orchestrator` (BalanceFlow), `services/mw-core-mock` (BalanceInquiry handler),
  `services/mw-auth` (amount 0 for BALANCE), `services/mw-ussd` (`menus/main-v3.json`),
  `services/mw-app-gateway` (`POST /app/v1/balance`)
- `services/mw-admin-api` (`service/MenuValidator` BALANCE), `services/mw-seeder` (`seed-content/menus/main.json`)
- `ui/mw-test-console` (app page Balance, `e2e/10-app.spec.ts`, `e2e/20-ussd.spec.ts`)
- `tests/api/src/29-balance`
