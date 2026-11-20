# 119 split (2026-11-20, pre-split like 115/118, auto-split rule)

- **119a-remit-adapter** (95,97,102): new `services/mw-adapter-remit` (framework copy as in 115a/118a, package
  `mfs.mw.adapter.remit`), family `remit`, partner seed(s), simulator party in mw-simulators, Kafka topic, compose block,
  provision, admin-api family CHECK (next free Flyway), engine-copies test, module CLAUDE.md; inbound remittance callback
  endpoint in the adapter if the ticket's flow is partner-initiated.
- **119b-remit-flow** (119a): mw-orchestrator flow(s), compliance hook (as the ticket says; mw-compliance may not exist yet ->
  stub or open item), admin-api seed copy, Newman folder (next free number), frs-traceability.
