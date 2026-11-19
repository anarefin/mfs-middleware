# D31: The core wallet over REST

Status: Accepted, 2026-11-19 (user decision, FRS planning, ticket 89). Amends D5 (asynchronous
Kafka commands to the core) and M4; spec 6.3. Built by tickets 93 (core-mock REST API), 94 (core
model) and 95 (orchestrator client).

## Context

D5 made every call into the core an asynchronous Kafka command with a result event (spec 6.3), and
mw-core-mock implements that contract. The Upay FRS section 7 describes the mock core wallet as a
REST API (WAL-01 to WAL-13: accounts, PIN verify, balance, charges, limits, holds, transfers,
credits, reversals, statements, transaction status) and EXT-18 assumes REST to the real core. The
core vendor has not published its contract (FRS 10.6, spec open point 1), so the mock's API is the
proposal either way. Holds, commits and releases are short, idempotent calls whose answer the saga
needs before its next step; over Kafka they cost two topics, an outbox row and an inbox row each.

## Decision

- **The orchestrator calls the core over REST** for WAL-01 and WAL-03 to WAL-13, through a
  `client/` class with Resilience4j (timeout, retry only where the call is idempotent by `txnId`,
  circuit breaker, bulkhead: PLT-030, PLT-033). The contract is an OpenAPI file owned by
  mw-core-mock under the contracts folder (ticket 93).
- **Every core operation is idempotent by `txnId`** (FRS 7.3): a repeated call returns the first
  answer. Saga steps keep their state before and after each call (PLT-021), so a restart resumes or
  compensates.
- **A timeout or an ambiguous answer** moves the transaction to `UNKNOWN`, and the status inquiry
  asks the core through WAL-13 (`GET /transactions/{txnId}`), the same schedule as partner
  inquiries (spec 7.4, PLT-023).
- **No REST call on a Kafka listener thread** (root rule): a listener hands the step to the saga's
  executor and returns; the REST call runs on a virtual thread of that executor.
- **WAL-02 (PIN verify) is not built.** The middleware owns the PIN (D20); mw-auth checks it and
  issues the transaction assertion (D22). Hold, transfer and credit calls carry that assertion in a
  request header and the core mock verifies it, as it does on the commands today.
- **Events stay on Kafka**: the core keeps publishing wallet-state changes (lock, unlock, status),
  hold expiry (FRS 7.3 auto-release) and ledger entries, consumed by mw-auth, mw-orchestrator and
  mw-recon. Only the request/reply pairs move to REST.
- Ticket 95 retires the core command topics and their contracts once the orchestrator, mw-auth
  (`VerifyCustomerFactors`) and the admin compliance actions call REST.

### Also decided with this record

- **Routing (FRS M07) is a module of mw-orchestrator**, not a service (ticket 98): rules (PLT-040),
  prefix to MNO with MNP override (PLT-042), failover (PLT-032), cached with a 30 s refresh.
- **No schema registry.** Event schemas stay JSON Schema files in Git, checked by tests and
  `./gradlew :contractsLint` (M9, D10). FRS DAT-01 asks for "Avro or JSON Schema in a schema
  registry": the JSON Schema half is met, the registry is a recorded deviation
  (`docs/frs-traceability.md`).
- **Coverage is reported, not gated** (D19 kept): FRS 10.3's 80 % target is a report of ticket 137.

## Consequences

- D5's "no synchronous calls into the core" no longer holds for the orchestrator; the core must
  still never be blocked by a channel: the REST calls have short timeouts and a bulkhead of their own.
- The saga loses the core `commandId` and gains the `txnId` as the idempotency key of each core
  call; the orchestrator's `core_call` and `saga_step` rows record the call and its answer.
- mw-core-mock grows a REST API and keeps its Kafka event publishing; its fault controls move to the
  unified mock control (D33).
- Contract tests compare the orchestrator's client with the mock's OpenAPI file (FRS 10.3).

## Where in the repo

- `services/mw-core-mock`, `services/mw-orchestrator`
- `contracts/events/mw-core-mock` (the core events; ticket 95 retires the request/reply pairs and
  the core command topics)
- spec decision table (D31), section 6.3
