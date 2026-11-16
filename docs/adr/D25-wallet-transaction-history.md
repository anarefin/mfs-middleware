# D25: Wallet transaction history from the orchestrator's query API

Status: Accepted, 2026-11-16.

## Context

The app API had no list endpoint: only `GET /app/v1/transactions/{txnId}`, served from mw-app-gateway's
own projection of the app submissions it made (`app_gateway.app_transaction`). A wallet's money also moves
through other channels (USSD, org bulk credits, an agent's cash-in, a customer paying a merchant), which
the gateway never sees. mw-orchestrator holds every transaction of every channel in `txn.transaction`,
but its internal query API (ticket 35) matched only the initiator (`msisdn`, `orgId`) and the org payees,
never "the wallet that was paid", and it served operators and org portals only. Nobody reads another
service's schema.

## Decision

- **Endpoint.** `GET /app/v1/transactions` (app.v1 1.5.0) returns the signed-in wallet's history, sent
  and received, newest first: `TransactionHistory {items, limit, offset, hasMore}`, each `HistoryItem`
  with `direction` IN / OUT and the masked `counterparty`. The wallet is always the token's `msisdn`,
  never a parameter; any app role; session level (no signature, nothing moves). Offset paging (limit
  1..100, offset 0..10000), filters `txnType`, `direction`, `from`, `to`.
- **Source.** mw-app-gateway reads it from mw-orchestrator's query API (txn-query 1.3.0, new filters
  `walletId`, `direction`), never from its own projection and never from the `txn` schema.
- **Wallet-scoped internal client.** `mw-app-gateway` becomes an allowed client of the query API with a
  new scope `mw.internal-auth.wallet-scoped-clients`: only `GET /internal/v1/transactions` (the detail is
  403), always with a non-blank `walletId`, only the query parameters walletId, direction, txnType,
  status, from, to, limit and offset; anything else is 403 `MW-AUTH-002`, logged
  `security.internal_call_denied` with reason `PATH_NOT_ALLOWED`, `WALLET_REQUIRED` or
  `PARAM_NOT_ALLOWED`. Like the org scope of mw-portal-api, the gateway can only ever read the wallet it
  names, and it names only the token's.
- **Multi-audience token.** The gateway keeps one Keycloak client (`mw-app-gateway`, realm `services`)
  and one cached client-credentials token: the client gets a second audience mapper, so the token's
  `aud` holds both `mw-auth` and `mw-orchestrator`; each service checks only that its own name is in it.
- **Party index.** A new table `txn.wallet_txn (wallet_hash, txn_id, direction, created_at)`, one row per
  wallet a transaction moves money from (OUT, the debit party) or to (IN, the credit party), keyed by the
  typed search hash `HMAC(WALLET:<canonical MSISDN>)` (same key as `initiator_hash`), indexed `(wallet_hash,
  created_at DESC, txn_id DESC)`. A wallet party is a party of type WALLET (any walletType: USSD tags
  credits CUSTOMER) or MERCHANT; an org, a top-up MSISDN, a bank account or a biller account is never
  one. A wallet on both sides counts once, as OUT. Written in the same transaction as the transaction
  row (V12); older rows are filled by the Java migration V13 (`WalletPartyMigration`, the pattern of
  `SearchHashMigration`, `on conflict do nothing`).
- **IN visibility.** A transaction paying the wallet is shown only once the money reached it: COMPLETED,
  REVERSING or REVERSED (`mw.orchestrator.search.wallet-in-statuses`). A failed or still running transfer
  to the wallet gave it nothing. The wallet's own payments (OUT) are shown in every status.
- **Masking.** The counterparty is masked by the orchestrator as in every query view; the gateway leaves
  out the wallet's own side, and the fee and the error of an IN item (the payer's business).
- **Errors.** The orchestrator out of reach (connect failure, timeout, no service token, an answer other
  than 200, an open breaker) is `503 MW-SYS-013`, retryable; Resilience4j `orchestrator` retries twice
  and breaks the circuit.

## Consequences

- History items are self-contained: the detail `GET /app/v1/transactions/{txnId}` still serves only the
  caller's own app submissions, so a received item or a USSD one has no detail call.
- Offset paging: an item that arrives between two pages shifts the next page by one. Keyset paging
  (`createdAt`, `txnId`) is a later change.
- A bulk credit shows the org's wallet masked as its counterparty (party type ORG), not the org's name.
- A reversal is not a transaction of its own: the original item turns REVERSED.
- The realm change (second audience mapper) reaches a running stack only through a fresh Keycloak
  import: `deploy/reset.sh`, then `up`.
- mw-app-gateway now depends on mw-orchestrator being up for the history (and only for it).

## Where in the repo

- spec decision D25
- `services/mw-orchestrator`: `db/migration/V12__wallet_party.sql`, `repository/WalletPartyMigration`
  (V13), `util/SearchKeys.walletParties`, `repository/TransactionRepository`,
  `security/InternalCallerPolicy`, `config/InternalAuthProperties`, `config/SearchProperties`
- `services/mw-app-gateway`: `controller/TransactionController`, `service/TransactionHistoryService`,
  `client/OrchestratorQueryClient`, `mapper/HistoryMapper`
- `contracts/api/mw-orchestrator/txn-query.v1.openapi.yaml` (1.3.0),
  `contracts/api/mw-app-gateway/app.v1.openapi.yaml` (1.5.0)
- `deploy/keycloak/realms/services-realm.json` (client `mw-app-gateway`, mapper `aud-mw-orchestrator`)
- `tests/api/src/28-txn-history`
