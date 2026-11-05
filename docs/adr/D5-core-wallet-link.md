# D5: Asynchronous Kafka commands and events to the core

Status: Accepted, 2026-11-03 (spec revision 1). Topic routing revised at the M2 gate: HoldFunds, CommitHold and ReleaseHold on `core.payment.*`, everything else on `core.wallet.*`.

## Context

The core wallet must never be blocked by a slow channel or partner, and the middleware must survive core restarts without losing or duplicating money movements.

## Decision

The middleware never calls the core synchronously. The orchestrator sends commands (`core.wallet.command.v1`, `core.payment.command.v1`) and consumes events, with `commandId` deduplication on the core side. Synchronous channels wait for the saga's result with a bounded budget and fall back to 202 `PROCESSING` (spec 7.5). Unknown outcomes are resolved by status inquiry (TSQ).

## Consequences

- The proposed contract (spec 6.3) must be accepted by the core team; `mw-core-mock` implements it for the MVP (M4).
- Every flow has timeout, TSQ and manual-review paths, tested in e2e (`@tsq`).

## Where in the repo

- `contracts/events/mw-orchestrator`, `contracts/events/mw-core-mock`
- `services/mw-orchestrator/src/main/java/mfs/mw/orchestrator/service/CoreTopics.java`
- spec sections 6.3, 7
