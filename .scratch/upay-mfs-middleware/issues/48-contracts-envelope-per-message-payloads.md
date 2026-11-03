# 48: Contracts v1.1: envelope + per-message payload schemas

**What to build:** Each message type gets its own schema file. Today one schema per topic picks payload rules with `if/then` on the type field; ticket 48 replaces that. Each topic keeps one **envelope** schema at `contracts/events/<owner>/<topic>.schema.json`. Its `payload` is a `oneOf` keyed on the type field (`commandType`, `eventType` or `family`). Each branch is a `$ref` to `contracts/events/<owner>/<topic>/<MessageName>.payload.schema.json`. That gives `core.command.v1` 9 payload files, `core.event.v1` 17, and `mw.partner.request.v1`, `mw.partner.reply.v1` and `mw.partner.inbound.v1` one per partner family. Single-type topics (`mw.txn.request`, `mw.txn.status`, `mw.notify.*`, `mw.audit.event`, `mw.config.changed`) keep one schema and only adopt the shared types.

Shared types live once in `contracts/common/defs.v1.schema.json`: Amount (minor units, integers only), Party/Msisdn, EncryptedPin {keyId, ciphertext}, correlation fields and schemaVersion. `$id`s stay absolute (`https://contracts.upay.mw/...`) and `$ref`s are relative. This is a contract change, so it is its own WP (spec ground rule "Contract first"). Decided at the M0 review (2026-11-03).

**Blocked by:**

- 04: Event contracts v1
- 06: Template part 2b: outbox, inbox, conformance suite

**Status:** done

- [x] `make contracts-lint` registers every schema before compiling, so cross-file `$ref`s resolve. Every envelope and payload schema compiles
- [x] Lint fails when an envelope's type enum and its set of payload files differ (a payload file with no enum value, or an enum value with no payload file)
- [x] `contracts/common/` has an owner in `OWNERS.yml`. The ownership rule allows it, and lint still fails on any file without an owner
- [x] Every message type has at least one valid and one invalid sample under `samples/<topic>/<MessageName>/`. Each invalid sample fails for exactly its stated reason, including missing `encryptedPin` on ValidateTransaction/HoldFunds/PostTransfer and a decimal amount
- [x] No schema uses `"type": "number"`. Every schema has a required `schemaVersion` const (message schemas only; payload and common/ schemas are fragments, decided at merge)
- [x] The template's schema loader resolves `https://contracts.upay.mw/` `$ref`s from `mwContractsDir`. A template test validates a `core.command.v1` HoldFunds sample (valid and invalid) through it, and `ConfigChangedEventSchemaTest` still passes
- [x] `contracts/README.md` documents the layout and the steps to add a new message type
- [x] `make verify` passes
