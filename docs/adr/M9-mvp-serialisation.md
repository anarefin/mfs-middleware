# M9: JSON on Kafka with JSON Schema in Git

Status: Accepted, 2026-11-03. Schema layout decided at the M0 gate: one envelope schema per topic with one payload schema per message type.

## Context

A schema registry and Avro add infrastructure the MVP does not need; contracts must still be strict and reviewable.

## Decision

Kafka messages are JSON. Their JSON Schemas live in `contracts/events/<owner>/`, each topic with an envelope schema whose payload is a reference to one payload schema per message type; shared types in `contracts/common/defs.v1.schema.json`. No Apicurio, Avro or Harbor.

## Consequences

- `./gradlew :contractsLint` (originally `make contracts-lint`) validates every schema and its valid/invalid samples.
- Producers and consumers test their messages against the schemas (`ProducedContractsTest`, `ConsumedContractsTest`).

## Where in the repo

- `contracts/README.md`
- `scripts/contracts-lint.sh`
