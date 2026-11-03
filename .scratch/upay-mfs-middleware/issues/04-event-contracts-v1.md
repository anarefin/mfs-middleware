# 04: Event contracts v1

**What to build:** Agents can build services in parallel against fixed message contracts. The root `contracts/` folder (files only, no code) gets JSON Schemas for: the transaction envelope (`mw.txn.request.v1`, `mw.txn.status.v1`); core commands and events from §6.3, with `encryptedPin { keyId, ciphertext }` on ValidateTransaction, HoldFunds and PostTransfer; partner request/reply/inbound; notify request/status; audit event; and `mw.config.changed.v1`. Each file is placed under its owning service, and `contracts/OWNERS.yml` records the owner of every file. Spec WP04.

**Blocked by:**

- 01: Repo skeleton and local quality gate

**Status:** ready-for-agent

- [ ] Every schema has at least one valid and one invalid sample payload, and `make contracts-lint` validates all of them
- [ ] Every schema carries `schemaVersion`, and amounts use the `valueMinor` integer
- [ ] `OWNERS.yml` names an owner for every contract file, and lint fails if a file has no owner
- [ ] The template reads contracts through the `mwContractsDir` Gradle property, and a schema test in the template loads one contract that way
