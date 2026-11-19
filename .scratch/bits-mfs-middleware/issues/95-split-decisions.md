# 95 split (user decisions 2026-11-20) — read with 95-orchestrator-core-rest-client.md

Ticket 95 was ~1 day of work (4 modules, ~14 ITs on the Kafka CoreStub), so it is split:

- **95a** (blocks 96, 105): mw-orchestrator `client/CoreWalletClient` (RestClient + Resilience4j, latest stable), effect -> REST
  dispatch off the Kafka listener threads (virtual-thread executor), REST outcome -> `SagaEvent` mapping, timeout -> UNKNOWN ->
  WAL-13 inquiry; a REST stand-in for `messaging/CoreStub` and the ITs rewired; Keycloak `services` realm client
  `mw-orchestrator` with an `aud-mw-core-mock` mapper (deploy/keycloak/realms/services-realm.json); the orchestrator's
  core-event listener ignores the FundsHeld/HoldCommitted events that REST calls also emit (no double apply, tested).
  mw-core-mock additions (it owns `core-wallet.v1`):
  - `POST /core/v1/transactions/validate`: same rules as Kafka ValidateTransaction (assertion check + jti claim), idempotent
    under `<txnId>:rest:validate`.
  - `GET /core/v1/balance/{walletId}` accepts `X-Txn-Assertion` + idempotency key: required for customer reads, optional for
    system/admin reads.
- **95b** (after 95a): mw-auth and mw-admin-api call `/core/v1` (their Keycloak audience), add
  `POST /core/v1/accounts/{walletId}/unlock` and the verify-factors call; then retire the core command topics/contracts and
  mw-core-mock's `CoreCommandListener` (core *events* stay on Kafka, D31).
- **95c** (after 95a, 94): orchestrator consumes `HoldExpired` and wallet-state events (94 added HoldExpired).
