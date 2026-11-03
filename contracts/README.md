# contracts/

Files only, no code (spec 4.2, 4.3, D14). Modules read this folder through the
`mwContractsDir` Gradle property, in tests and build-time schema checks only.

```
OWNERS.yml                                  owning service per file
events/<owning-service>/<name>.schema.json  JSON Schema 2020-12, one per message
events/<owning-service>/samples/<name>/     valid*.json must validate, invalid*.json must not
```

Rules checked by `make contracts-lint` (scripts/contracts-lint/lint.mjs):

- every schema compiles (Ajv strict mode) and has a required `schemaVersion` with a `const`
- no `"type": "number"`: amounts are `{ valueMinor: integer, currency: "BDT" }`
- every schema has at least one valid and one invalid sample, and each behaves as named
- every file except samples has an owner in `OWNERS.yml`, matching its `<kind>/<owner>/` folder

Within a version only additive changes are allowed; consumers ignore unknown fields,
so schemas leave `additionalProperties` open. Topic -> schema:

| Topic | Schema |
| --- | --- |
| `mw.txn.request.v1`, `mw.txn.status.v1` | `events/mw-orchestrator/mw.txn.*.v1` |
| `core.wallet.command.v1`, `core.payment.command.v1` | `events/mw-orchestrator/core.command.v1` (`commandType` discriminator) |
| `core.wallet.event.v1`, `core.payment.event.v1` | `events/mw-core-mock/core.event.v1` (`eventType` discriminator) |
| `mw.partner.request.<family>.v1` | `events/mw-orchestrator/mw.partner.request.v1` (`family` field) |
| `mw.partner.reply.v1`, `mw.partner.inbound.v1` | `events/mw-orchestrator/mw.partner.*.v1` |
| `mw.notify.request.v1`, `mw.notify.status.v1` | `events/mw-notify/mw.notify.*.v1` |
| `mw.audit.event.v1` | `events/mw-audit-dw/mw.audit.event.v1` |
| `mw.config.changed.v1` | `events/mw-admin-api/mw.config.changed.v1` |
