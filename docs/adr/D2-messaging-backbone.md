# D2: Apache Kafka in KRaft mode

Status: Accepted, 2026-11-03 (spec revision 1).

## Context

Transactions span several services and an asynchronous core (D5). Commands, events, partner requests and notifications need durable, ordered (per transaction) and replayable delivery.

## Decision

Apache Kafka, KRaft mode (no ZooKeeper). Topics are declared as code; ordering is per key (`txnId`). Delivery is effectively-once: idempotent producer + transactional outbox + inbox deduplication, no Kafka transactions (M0 gate decision). Failed records go through per-consumer retry topics (10 s, 1 min, 10 min) to a DLQ.

## Consequences

- No synchronous coupling between services on the money path.
- Every consumer must be idempotent (inbox) and every producer writes through its outbox in the same database transaction as the business change.
- In the MVP one broker, replication factor 1 (D11, M8); ACLs and quotas are deferred.

## Where in the repo

- `deploy/kafka/topics.txt` (topic catalogue, applied by `kafka-init`)
- `services/mw-orchestrator/src/main/java/mfs/mw/orchestrator/messaging` (`OutboxWriter`, `OutboxRelay`, `Inbox`)
- spec section 6
