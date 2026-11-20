# D22: PIN on every transaction, exchanged for a signed assertion before submit

Status: Accepted, 2026-11-14 (spec revision 6).

## Context

Under M11 the encrypted PIN rides with the transaction request over Kafka into the orchestrator, sits on `txn.transaction.encrypted_pin` until a terminal state, and reaches the core in `ValidateTransaction`, `HoldFunds` and `PostTransfer`. Once the middleware owns the PIN (D20) the core needs proof that the customer authorised this exact transaction, and the PIN ciphertext no longer needs to travel past `mw-auth`.

## Decision

- Every financial transaction needs the PIN (as bKash, Nagad and Upay do).
- The channel (`mw-app-gateway`, `mw-ussd`, `mw-checkout`) calls `mw-auth` `POST /internal/v1/txn-authorisations` over internal HTTP (mTLS + service JWT) with the PIN ciphertext and a SHA-256 hash of the canonical transaction (type, amount, currency, payer, payee, idempotency key, channel).
- `mw-auth` returns a compact JWS (ES256, 60 s, single use by `jti`, `aud` core) with the hash, channel, auth method, device and a `newDevice` flag; the request and core commands carry only the assertion; the core recomputes the hash and checks signature, expiry, audience and `jti`.
- This is the second documented exception to "no service-to-service calls on the money path" (spec 2): it moves no money.

## Consequences

- No PIN, plain or encrypted, on Kafka or in the `txn` schema: `txn.transaction.encrypted_pin` and the `EncryptedPin` fields of the transaction request and core commands are replaced by an assertion field (contract changes owned by `mw-orchestrator`).
- A wrong PIN is refused before any saga starts.
- A retry with the same idempotency key reuses the assertion within its life; later the customer enters the PIN again.
- Bulk children carry no assertion (the org approved the batch, ticket 34).

## Where in the repo

- spec sections 2, 9.1–9.3, 18.3, 18.7 and decision D22
- contracts to change: `contracts/events/mw-orchestrator/mw.txn.request.v1.schema.json` (the core command contract was retired in ticket 95b; the assertion now travels on `contracts/api/mw-core-mock/core-wallet.v1.openapi.yaml`)
- tickets 71, 72
