# APISIX edge configuration

The edge gateway (spec 8) as code. `apply.sh` runs as the one-shot `apisix-init` container on
every `make up` and PUTs everything here to the APISIX Admin API.

```
config.yaml                static config (listeners, etcd, Admin API, nginx env)
apply.sh                   edge cert + SSL object, then global_rules/*.json, then routes/*.json
global_rules/edge.json     policies on every route (correlation ID, X-Mw-* strip, error envelope)
routes/<id>.json           one route per file, PUT as route <id>
routes-test/<id>.json      TEST ONLY, see "Test-only objects" below
consumers-test/<name>.json TEST ONLY consumers of those routes
lua/*.lua                  serverless / _meta.pre_function code, inlined as "@lua:lua/<file>"
schemas/*.json             request-validation schemas derived from contracts/, inlined as "@json:schemas/<file>"
```

## Format decision: Admin API JSON, not ADC YAML

Spec 8.1 says "declarative YAML applied by ADC". The MVP keeps Admin API JSON applied by
`apply.sh` (ticket 13 decision), because:

- **Nothing new to run or pin.** ADC is a separate CLI (Node binary) that is not in the
  APISIX image; it would need its own pinned image and a second one-shot. `apply.sh` runs in
  the APISIX image that is already pinned by digest.
- **Same idempotency.** Every object has a fixed id and is PUT whole, so a second apply
  stores the same objects (`make edge-test` checks this: Admin API objects identical except
  `update_time`). That is the property ADC's diff-based `sync` gives.
- **ADC's `sync` deletes what the file does not hold.** Partner onboarding writes consumers and
  credentials through `mw-admin-api` (spec 8.1); a full sync would wipe them unless every
  object is label-scoped. The PUT model leaves objects it does not own alone.
- **Exact schema.** The JSON files are exactly what the Admin API validates; no translation
  layer whose APISIX 3.19 support has to be checked first.

Cost: `apply.sh` never deletes. Removing a file leaves its object in etcd; delete it through
the Admin API (from the ops network) or drop the volumes (`make down DOWN_ARGS=-v`). Moving to
ADC later is mechanical (one route file = one ADC route); revisit when the internal gateway
group or production pipeline arrives.

## Edge policies (spec 8.3, ticket 13)

Global rule `edge` (all routes, including 404s for unknown paths):

| Policy | How |
| --- | --- |
| Correlation ID | `request-id` on `X-Correlation-Id` (UUID when absent), forwarded upstream and returned. Its `_meta.pre_function` (`lua/edge-request.lua`) first drops an ID that is repeated or not `[A-Za-z0-9._:-]{1,128}` (same rule as the services' `CorrelationIdFilter`), so edge and services log the same ID. The error log's `request_id` is this ID too. |
| Internal headers | the same pre_function removes every inbound `X-Mw-*` header (any spelling); `proxy-rewrite` cannot remove by prefix. |
| Error envelope | `lua/edge-error-headers.lua` (header_filter) + `lua/edge-error-body.lua` (body_filter) turn any 4xx/5xx the edge produced itself into the 5.3 envelope. An upstream's JSON answer passes untouched. |
| Body logging | off: no logger plugin, default access log (no bodies). `test_apisix_edge.sh` fails on a logger plugin or `include_*_body`. |

APISIX allows a plugin in only one global rule, so the edge keeps a single global rule; a route
may still use its own `serverless-*-function` (the app route does).

Edge error codes:

| HTTP | Code | Retryable | Produced by |
| --- | --- | --- | --- |
| 400 | `MW-VAL-001` (reason in `details[0].issue`) | no | `request-validation`, malformed JSON, bad `Authorization` header |
| 401 | `MW-AUTH-001` | no | `openid-connect`: no, invalid, badly signed or expired token |
| 403 | `MW-AUTH-002` | no | `openid-connect` scope/audience checks |
| 404 | `MW-VAL-003` | no | no route |
| 413 | `MW-VAL-BODY_TOO_LARGE` | no | `client-control` `max_body_size` |
| 429 | `MW-SYS-RATE_LIMITED` | yes | `limit-count` |
| 502, 503 | `MW-SYS-UPSTREAM_UNAVAILABLE` | yes | upstream unreachable |
| 504 | `MW-TMO-UPSTREAM_TIMEOUT` | yes | upstream timeout: outcome unknown, check the status first |
| other 5xx / 4xx | `MW-SYS-001` / `MW-VAL-002` | yes / no | anything else |

Route `app-v1` (`/app/v1/*`), in plugin order:

1. `client-control` `max_body_size` 64 KiB (413).
2. `openid-connect` bearer-only, realm `customers` JWKS, `iat_slack` 10 s (the plugin's default
   accepts a token 120 s past `exp`).
3. `request-validation` only for `POST /app/v1/transactions` (`_meta.filter`), after auth
   (`_meta.priority` 2500 < openid-connect's 2599), schema `schemas/app-v1-transaction-request.json`.
4. `serverless-pre-function` (access, `lua/consumer-from-jwt.lua`): `ctx.var.mw_consumer` =
   `sub:<token sub>`, the per-consumer key.
5. `limit-count` 120 requests / 60 s per consumer, counters in Valkey (`policy: redis`,
   password `$env://VALKEY_PASSWORD`, so it is never stored in etcd; `config.yaml` exposes the
   variable to nginx), `allow_degradation: true` (Valkey down: requests pass, APISIX logs it).
   A perf run with one test user needs more users or a higher count.

Route `partner-billagg-callback` (`/partner/billagg/callback/*`, POST only, ticket 22) ->
`mw-adapter-billpay:8080`, path and body unchanged (the partner signed them):

1. `ip-restriction`: dev/MVP loopback and private ranges (the simulator posts from the `app`
   network, the host e2e suite through Docker's NAT); production lists the partner's egress
   addresses.
2. `client-control` `max_body_size` 16 KiB (413).
3. `limit-count` 600 / 60 s per `remote_addr`, Valkey as on `app-v1`.
4. No `hmac-auth`: the adapter verifies the partner's `hmac-sha256` signature with the secret in
   OpenBao and answers 401 `MW-AUTH-001` itself (decision in `services/mw-adapter-billpay/CLAUDE.md`).
   No `request-validation`: the aggregator's wire format is partner-specific, not a contract.

A new partner gets its own `partner-<id>-callback.json` (upstream: the owning adapter, e.g.
`mw-adapter-bank` for ticket 23); `scripts/edge-test.sh` has the cases to copy.

## Request-validation schemas

`schemas/*.json` are generated, never edited: each names its source in `x-mw-derived-from`
(`<file under contracts/>#<JSON pointer>`). `scripts/apisix-schemas.sh --write` regenerates them;
`make contracts-lint` (so `make verify`) fails when one no longer matches its contract. The
derivation inlines `$ref`s, drops documentation keywords and `pattern`/`format` (APISIX's Lua
validator writes a failed pattern with the offending value, e.g. an MSISDN, to the error log;
the owning service still checks them), turns `propertyNames: { enum }` (ignored by the Lua
validator) into a closed `properties` list, and fails on any keyword it cannot carry over.

## Host routes for the e2e suite (ticket 10)

- `routes/auth-customers-token.json` (always applied): `POST
  /auth/realms/customers/protocol/openid-connect/token`, only Keycloak's public token endpoint of
  realm `customers` (client `mw-app` is public; no other Keycloak path is exposed). Host and
  `X-Forwarded-*` are pinned to `keycloak:8080`, so the token's `iss` is
  `http://keycloak:8080/realms/customers`, the issuer `app-v1`'s discovery expects. Revisit when
  a public Keycloak hostname (`KC_HOSTNAME`) is fixed.
- Test-only objects, `routes-test/` + `consumers-test/`: applied only when `apisix-init` runs with
  `MW_E2E_ROUTES=1`, which `make e2e` does on the running stack (`scripts/suite.sh`); every
  other run (a default `make up`) DELETEs them. Today: `test-core-mock` (`/test/core-mock/v1/*` ->
  core-mock's test/admin API: balances, PIN key, faults) and `test-simulators` (`/test/sim/*` ->
  mw-simulators' control API: forced scenarios), both with `ip-restriction` (loopback and
  private ranges only), `key-auth` (header `apikey`, hidden from the upstream) and
  `consumer-restriction` to consumer `mw_e2e`, whose key is `"@env:MW_E2E_API_KEY"`: generated by
  `scripts/gen-dev-env.sh` into the gitignored `deploy/compose/platform/.env`, never in the repo.
  `scripts/test/test_apisix_edge.sh` proves the default apply skips (and removes) them.

## Adding a route (tickets 22, 31, 33, ...)

1. `routes/<id>.json`: `uri`, `methods`, the auth plugin for the route group (spec 8.2), and the
   upstream by Compose DNS name (APISIX is on `app`). The global rule already gives it the
   correlation ID, the `X-Mw-*` strip and the error envelope.
2. Quota: copy the `limit-count` block with its own `group` and `count`. Keyed per consumer:
   on `openid-connect` routes reuse `lua/consumer-from-jwt.lua`; on `hmac-auth`/`key-auth`/
   `jwt-auth` routes APISIX sets a consumer, so use `"key": "consumer_name"`.
3. Body schema: add `schemas/<id>-<op>.json` with only `x-mw-derived-from`, run
   `scripts/apisix-schemas.sh --write`, reference it as `"@json:schemas/<file>"` in a
   `request-validation` limited by `_meta.filter` to the operation, `_meta.priority` below the
   auth plugin's.
4. `client-control` `max_body_size` sized for the route (bulk upload routes need more).
5. Partner/MNO routes: `ip-restriction` (8.3) before auth.
6. Add the route's cases to `scripts/edge-test.sh` and run `make edge-test`.
