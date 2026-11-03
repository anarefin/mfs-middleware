# contracts/

Files only, no code (spec 4.2, 4.3, D14). Modules read this folder through the
`mwContractsDir` Gradle property, in tests and build-time schema checks only.

## Layout (contracts v1.1, ticket 48)

```
OWNERS.yml                                         owning service per file
common/defs.v1.schema.json                         shared types: Amount, Party, Msisdn, EncryptedPin,
                                                   TxnId, CorrelationId, SchemaVersion, ...
events/<owner>/<topic>.schema.json                 one schema per topic (JSON Schema 2020-12)
events/<owner>/<topic>/<Msg>.payload.schema.json   multi-type topics: one payload schema per message type
events/<owner>/samples/<topic>/                    single-type topics: valid*.json, invalid*.json
events/<owner>/samples/<topic>/<Msg>/              multi-type topics: samples per message type
.../expected-errors.json                           next to invalid samples: why each one fails
```

**Single-type topics** (`mw.txn.request`, `mw.txn.status`, `mw.notify.*`, `mw.audit.event`,
`mw.config.changed`) have one schema that `$ref`s the shared types.

**Multi-type topics** have an **envelope** schema. It holds the fields every message carries,
a type field whose `enum` lists the message types, and a `payload`. A `oneOf` keyed on the type
field (with `"discriminator": { "propertyName": <type field> }`) picks the payload schema:

| Topic | Envelope | Type field | Message types |
| --- | --- | --- | --- |
| `core.wallet.command.v1`, `core.payment.command.v1` | `events/mw-orchestrator/core.command.v1` | `commandType` | 9 commands (spec 6.3) |
| `core.wallet.event.v1`, `core.payment.event.v1` | `events/mw-core-mock/core.event.v1` | `eventType` | 17 events (spec 6.3) |
| `mw.partner.request.<family>.v1` | `events/mw-orchestrator/mw.partner.request.v1` | `family` | telco, billpay, bank, interop, compliance |
| `mw.partner.reply.v1` | `events/mw-orchestrator/mw.partner.reply.v1` | `family` | same 5 families |
| `mw.partner.inbound.v1` | `events/mw-orchestrator/mw.partner.inbound.v1` | `family` | same 5 families |

Other topics: `mw.txn.request.v1`, `mw.txn.status.v1` -> `events/mw-orchestrator/mw.txn.*.v1`;
`mw.notify.request.v1`, `mw.notify.status.v1` -> `events/mw-notify/`; `mw.audit.event.v1` ->
`events/mw-audit-dw/`; `mw.config.changed.v1` -> `events/mw-admin-api/`.

### Ids and references

Every schema has an absolute `$id` that mirrors its path:
`https://contracts.upay.mw/<path under contracts/>`. References between files are relative
and resolve against that id, e.g. from an envelope `"$ref": "core.command.v1/HoldFunds.payload.schema.json"`
and `"$ref": "../../common/defs.v1.schema.json#/$defs/TxnId"`, from a payload
`"$ref": "../../../common/defs.v1.schema.json#/$defs/Amount"`. Nothing is fetched over the
network: the lint registers every file first, and the service template's test loader
(`ContractSchemas`) reads every `https://contracts.upay.mw/` id from `mwContractsDir`.

## Rules checked by `make contracts-lint` (scripts/contracts-lint/lint.mjs)

- every schema compiles (Ajv strict mode, all files registered first) and its `$id` matches its path
- every message schema (single-type or envelope) has a required `schemaVersion` with a `const`;
  payload schemas and `common/` are fragments, not messages, so they carry no `schemaVersion`
- no `"type": "number"` anywhere: amounts are `{ valueMinor: integer, currency: "BDT" }` (`Amount`)
- an envelope's type `enum`, its `oneOf` branches and its payload files name exactly the same types
- every single-type topic and every message type has at least one valid and one invalid sample;
  a sample under `<Msg>/` carries that type in its type field
- every invalid sample fails with exactly the errors stated for it in `expected-errors.json`
  (`"<instance path> <Ajv message>"`; wrapper errors from `if`, `anyOf`, `oneOf` are left out)
- every file except samples and payload schemas has an owner in `OWNERS.yml`; under
  `<kind>/<owner>/` it must be that folder's service; `common/` may name any service

Within a version only additive changes are allowed; consumers ignore unknown fields, so schemas
leave `additionalProperties` open.

## Adding a message type to a multi-type topic

A contract change is its own WP, made by the topic's owner (ground rule "Contract first").

1. Add the value to the envelope's type `enum`, e.g. `commandType` in `core.command.v1.schema.json`.
2. Add a `oneOf` branch:
   `{ "properties": { "commandType": { "const": "NewThing" }, "payload": { "$ref": "core.command.v1/NewThing.payload.schema.json" } } }`.
   Branch-level rules that depend on the type (an `outcome` const, a required `reasonCode`) go in
   the branch too.
3. Create `core.command.v1/NewThing.payload.schema.json` with
   `"$id": "https://contracts.upay.mw/events/mw-orchestrator/core.command.v1/NewThing.payload.schema.json"`,
   `"type": "object"`, its fields and `required`. Use the `common/defs.v1` types by relative `$ref`.
4. Add `samples/core.command.v1/NewThing/valid-<case>.json` and `invalid-<reason>.json` (whole
   envelope messages), and `expected-errors.json` stating each invalid sample's errors, e.g.
   `{ "invalid-without-pin.json": ["/payload must have required property 'encryptedPin'"] }`.
   Run `make contracts-lint`; on a mismatch it prints the errors the sample actually produced.
5. No OWNERS.yml entry is needed: the payload belongs to the envelope.

A new single-type topic is one `events/<owner>/<topic>.schema.json`, its samples folder with
`expected-errors.json`, and an OWNERS.yml entry. A new shared type goes in `common/defs.v1`
(owner: mw-orchestrator, see OWNERS.yml).
