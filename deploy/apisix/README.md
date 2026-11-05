# APISIX edge configuration

The edge gateway (spec 8) as code. `apply.sh` runs as the one-shot `apisix-init` container on
every `make up` and PUTs everything here to the APISIX Admin API.

```
config.yaml                static config (listeners, etcd, Admin API, nginx env)
apply.sh                   edge cert + SSL object, then global_rules/*.json, consumers/*.json,
                           routes/*.json, then DELETEs what retired.txt lists
global_rules/edge.json     policies on every route (correlation ID, X-Mw-* strip, error envelope)
consumers/<name>.json      real consumers, PUT as consumer <name> (the MNO USSD gateways)
routes/<id>.json           one route per file, PUT as route <id>
retired.txt                "<kind>/<id>" of objects whose file was removed: deleted on every apply
routes-test/<id>.json      TEST ONLY, see "Test-only objects" below
consumers-test/<name>.json TEST ONLY consumers of those routes
lua/*.lua                  serverless / _meta.pre_function code, inlined as "@lua:lua/<file>"
                           (edge-limit-degraded.lua: the rate-limiter degradation alert, ticket 50)
```

Values: `"@env:<NAME>"` is an environment variable as a string (secrets, never in the repo),
`"@list:<NAME>"` a comma-separated variable as a string array (address lists); unset or empty
fails the apply. There are no body schemas here: **body schemas are checked in the service,
never at the edge** (M3 gate decision). APISIX keeps size caps, header checks and auth.

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

Cost: `apply.sh` deletes only what `retired.txt` lists. Removing or renaming a file therefore
means adding its old `<kind>/<id>` there (as `routes/ussd-http`); otherwise its object stays in
etcd until deleted through the Admin API or the volumes are dropped (`make down DOWN_ARGS=-v`). Moving to
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
| 400 | `MW-VAL-001` (reason in `details[0].issue`) | no | malformed JSON, bad `Authorization` header (body schemas: the services) |
| 401 | `MW-AUTH-001` | no | `openid-connect`, `hmac-auth`, `basic-auth`: no, invalid, badly signed or expired credential |
| 403 | `MW-AUTH-002` | no | `openid-connect` scope/audience checks, `consumer-restriction` (another MNO's credential), `ip-restriction` |
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
3. No `request-validation`: the submit body (`POST /app/v1/transactions`) is signed by the app's
   bound device (ticket 29), and request-validation forwards a JSON body it validated re-encoded
   (APISIX 3.x `set_body_data`, not configurable), so the gateway could no longer verify the
   signature over the bytes the device sent. mw-app-gateway checks every `app.v1` JSON body
   against its derived schema (`services/mw-app-gateway/src/main/resources/derived-schemas/`, kept
   in sync by `scripts/derived-schemas.sh`; table in "Body schemas (in the services)") on the exact
   bytes it hashes and parses, after the token and before the device gate, with duplicate keys
   refused; errors stay 400 MW-VAL-001. No
   plugin on this route may rewrite the body (`test_apisix_edge.sh`).
4. `serverless-pre-function` (access, `lua/consumer-from-jwt.lua`): `ctx.var.mw_consumer` =
   `sub:<token sub>`, the per-consumer key.
5. `limit-count` 120 requests / 60 s per consumer, counters in Valkey (`policy: redis`,
   password `$env://VALKEY_PASSWORD`, so it is never stored in etcd; `config.yaml` exposes the
   variable to nginx), `allow_degradation: true` (Valkey down: requests pass, APISIX logs it,
   see "Rate-limiter degradation alert" below). A perf run with one test user needs more users
   or a higher count.

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

Route `partner-c2wbank-callback` (`/partner/c2wbank/callback/*`, POST only, ticket 23) ->
`mw-adapter-bank:8080`: the same four rules (own `limit-count` group
`partner-c2wbank-callback`); the C2W bank's debit outcome callbacks, verified by mw-adapter-bank.

Routes `ussd-http-<mno>` (`/ussd/<mno>` for gp, robi, bl, teletalk; POST only; ticket 26, M3 gate)
-> `mw-ussd:8080`, path and body unchanged: the MNO HTTP USSD gateways (`HttpUssdTransport`,
contract `contracts/api/mw-ussd/ussd-http.v1.openapi.yaml`). One route and one consumer per MNO:

1. `ip-restriction` with `"@list:USSD_<MNO>_ALLOWED_CIDRS"`: the MNO's gateway addresses, from the
   apisix-init environment. Required, with no fallback: `deploy/compose/platform/compose.yml` uses
   `${USSD_<MNO>_ALLOWED_CIDRS:?...}` and `apply.sh` fails on an unset or empty list, so a missing
   list stops the stack instead of opening the route. Dev values are written once into the
   gitignored `.env` by `scripts/gen-dev-env.sh`: loopback and Docker's default address pools
   (`172.16.0.0/12`, `192.168.0.0/16`), where the compose networks and the Docker Desktop host
   gateway live (the compose networks have no fixed subnet). Edit the `.env` line if your Docker
   uses other pools. Production lists each MNO's egress addresses.
2. `client-control` 4 KiB (413). The step body's schema is checked by mw-ussd (`UssdStep`), not here.
3. `basic-auth` (`hide_credentials`: mw-ussd never sees it) against consumer `consumers/ussd_<mno>.json`,
   user `ussd-<mno>`, password `"@env:USSD_<MNO>_GATEWAY_SECRET"`: generated by
   `scripts/gen-dev-env.sh` into the `.env`, also written to OpenBao `secret/mw-simulators` as
   `sim.ussd-gateway.credentials.<mno>` by `mw-simulators-init` (the simulator plays the MNO). No
   credential 401 `MW-AUTH-001`.
4. `consumer-restriction` whitelist `[ussd_<mno>]`, 403: the consumer is bound to the `{mno}` path
   segment, so a GP credential on `/ussd/robi` is refused `MW-AUTH-002`.
5. `serverless-pre-function` (access, `lua/ussd-msisdn-key.lua`): `ctx.var.mw_ussd_msisdn` =
   `<mno>:<sha256 hex of the canonical 01X... MSISDN>` read from the step body (the body is read,
   not changed; no MSISDN in Valkey keys).
6. `limit-count` 120 steps / 60 s per MSISDN (group `ussd-http-<mno>`, Valkey, fail-open as every
   limited route; a step without a readable msisdn is counted per source address). A subscriber
   types far slower; 120 leaves room for the e2e suite's one test MSISDN. MNO contract value, TBC.

Upstream read timeout 12 s (above mw-ussd's 8 s sync budget). The former single route `ussd-http`
(`/ussd/*`, no auth) is in `retired.txt`. An SMPP MNO (gp in dev) does not use its route until its
transport is switched to HTTP.

Checkout (ticket 31, spec 8.2/9.3, contract `contracts/api/mw-checkout/checkout.v1.openapi.yaml`)
-> `mw-checkout:8080`, three routes:

- `checkout-v1-sessions` (`POST /checkout/v1/sessions`): `client-control` 16 KiB, `hmac-auth`
  (`hmac-sha256` only, `clock_skew` 300 s, signed headers `@request-target`, `date`, `digest`;
  `validate_request_body`: the `Digest: SHA-256=<base64>` must match the body;
  `hide_credentials`), no `request-validation` (mw-checkout checks the body schema itself on the
  exact bytes, `services/mw-checkout/src/main/resources/derived-schemas/checkout-v1-create-session.json`;
  400 `MW-VAL-001` from the service), `proxy-rewrite` setting `X-Mw-Merchant-Id: $consumer_name` (the global rule strips
  any client `X-Mw-*` first), `limit-count` 600 / 60 s per consumer (group `checkout-v1-sessions`).
- `checkout-v1-session-get` (`GET /checkout/v1/sessions/*`): the same without body checks
  (signed headers `@request-target`, `date`), 1200 / 60 s per consumer.
- `checkout-pay` (`GET|POST /checkout/pay/v1/*`, the hosted page in the browser): no auth plugin
  (the pay token in the path is the bearer), `client-control` 8 KiB (the otp and confirm bodies are
  schema-checked by mw-checkout, not here), 300 / 60 s per
  `remote_addr`, read timeout 35 s (mw-checkout waits up to 25 s for the payment). Spec 8.2 also
  names `csrf` and strict CORS: no `cors` plugin is configured, so no cross-origin access is
  granted (the page must be served from this origin); `csrf` is left out because the API takes
  JSON bodies with the token in the path and sets no cookies (revisit with the page, open point 16).

Merchant consumers carry `hmac-auth {key_id, secret_key}`; the consumer name is the merchant id
mw-checkout knows (`mw.checkout.merchants.<id>`). Real merchants are onboarded through
mw-admin-api later; the dev merchant `consumers-test/merchant_demo.json` (key id `merchant-demo`,
secret `CHECKOUT_DEMO_MERCHANT_SECRET` from the generated `.env`, also synced to
`secret/mw-checkout` as its signing secret) exists only with `MW_E2E_ROUTES=1`. A signed request
(APISIX 3.x `hmac-auth`): `Date: <RFC 7231 GMT>`, `Digest: SHA-256=<base64(sha256(body))>`,
`Authorization: Signature keyId="merchant-demo",algorithm="hmac-sha256",headers="@request-target
date digest",signature="<base64(hmac-sha256(secret, signing string))>"`, signing string =
`keyId\n<METHOD> <path?query>\ndate: <Date>\ndigest: <Digest>\n` (each header line `name: value`
in the order of `headers`, every line ending in `\n`). `tests/e2e/support/CheckoutApi` signs this way.

A new partner gets its own `partner-<id>-callback.json` (upstream: the owning adapter);
`scripts/edge-test.sh` has the cases to copy (billagg and c2wbank both covered).

## Rate-limiter degradation alert (ticket 50)

`limit-count` stays fail-open (`allow_degradation: true`, M2 gate decision): a Valkey outage must
not take the edge down. So that an outage is never silent, the global rule's header_filter also
runs `lua/edge-limit-degraded.lua`: a request that reached the upstream on a route with
`limit-count` (`show_limit_quota_header: true`, required on every limited route) but carries no
`X-RateLimit-Limit` header was let through uncounted, and APISIX writes one **error** log line

```
mw.edge.rate_limit_degraded route=<route id> status=<HTTP status> (limit-count failed open: Valkey unreachable or slow)
```

next to limit-count's own `failed to limit count: ...` line. The `mw.edge.rate_limit_degraded`
prefix is the stable signal: alert on any occurrence (one per uncounted request; no headers,
bodies or personal data). Requests stopped before the limiter (401, 400, 413) and limit-count's
own 429 never match. Where to see it:

- `make smoke` fails "apisix rate limiter not degraded" while the APISIX log of the last 5 minutes
  (`SMOKE_LIMITER_WINDOW`) holds such a line (so the outage `make edge-test` causes ages out);
- `make edge-test` stops Valkey briefly and checks that a request passes unlimited and the line
  is logged for `app-v1`;
- `scripts/test/test_apisix_edge.sh` checks every limited route is fail-open with quota headers
  and the function is in the global rule.

A new rate-limited route needs nothing extra beyond `show_limit_quota_header: true`.

## Body schemas (in the services)

No route uses `request-validation` (`test_apisix_edge.sh` fails if one does): it forwards a body
it validated re-encoded, so a service could not verify a signature over the bytes the client sent,
and the M3 gate decided that body schemas always live in the service. Each service that checks a
body keeps the schema derived from its OpenAPI contract in
`services/<module>/src/main/resources/derived-schemas/*.json` and validates the exact bytes before
binding them (duplicate keys, trailing content and non-objects refused; 400 `MW-VAL-001` from the
service; each keeps its own body cap, 413 `MW-VAL-BODY_TOO_LARGE`). Every public JSON body (review A6):

| Route | Endpoint | Service: check | Derived schema (contract source) |
| --- | --- | --- | --- |
| `app-v1` | `POST /app/v1/transactions` | mw-app-gateway: `BodySchemaInterceptor` (cached bytes, the ones the device signs) | `app-v1-transaction-request.json` (`TransactionRequest`) |
| `app-v1` | `POST /app/v1/devices/otp` (body optional) | mw-app-gateway, as above | `app-v1-device-otp-request.json` (`DeviceOtpRequest`) |
| `app-v1` | `POST /app/v1/devices` | mw-app-gateway, as above | `app-v1-device-binding-request.json` (`DeviceBindingRequest`) |
| `app-v1` | `POST /app/v1/billers/{code}/fetch-bill` | mw-app-gateway, as above | `app-v1-fetch-bill-request.json` (`FetchBillRequest`) |
| `checkout-v1-sessions` | `POST /checkout/v1/sessions` | mw-checkout: `BodySchemaAdvice` | `checkout-v1-create-session.json` (`CreateSessionRequest`) |
| `checkout-pay` | `POST /checkout/pay/v1/{token}/otp` | mw-checkout, as above | `checkout-pay-v1-otp.json` (`OtpRequest`) |
| `checkout-pay` | `POST /checkout/pay/v1/{token}/confirm` | mw-checkout, as above | `checkout-pay-v1-confirm.json` (`ConfirmRequest`) |
| `portal-org-v1` | `POST /portal/org/v1/bulk/batches/{batchId}/reject` | mw-portal-api: `BodySchemaAdvice` | `portal-org-v1-reject-request.json` (inline reject body) |
| `ussd-http-<mno>` | `POST /ussd/{mno}` | mw-ussd: `BodySchemaAdvice` | `ussd-http-v1-step.json` (`UssdStep`) |

No body, so no schema: `POST /app/v1/sessions`, `POST /checkout/pay/v1/{token}/cancel`, portal
approve/pause/resume. Not JSON: the portal CSV upload (`POST /portal/org/v1/bulk/batches`, `text/csv`),
checked by mw-portal-api's `BulkCsvParser` (header, rows, row and byte caps). Partner callbacks
(`partner-billagg-callback`, `partner-c2wbank-callback`) have a partner-specific wire format, not a
contract: the adapters verify them. `test_apisix_edge.sh` checks every row of the table. The files are
generated, never edited: each names its source in `x-mw-derived-from` (`<file under
contracts/>#<JSON pointer>`); `scripts/derived-schemas.sh --write` regenerates them and `make
contracts-lint` (so `make verify`) fails when one no longer matches its contract. The derivation
inlines `$ref`s, drops documentation keywords and `pattern`/`format` (a validator message for them
repeats the value, e.g. an MSISDN; the service's Bean Validation still checks them), turns
`propertyNames: { enum }` into a closed `properties` list, and fails on any keyword it cannot carry
over.

## Host routes for the e2e suite (ticket 10)

- `routes/auth-customers-token.json` (always applied): `POST
  /auth/realms/customers/protocol/openid-connect/token`, only Keycloak's public token endpoint of
  realm `customers` (client `mw-app` is public; no other Keycloak path is exposed). Host and
  `X-Forwarded-*` are pinned to `keycloak:8080`, so the token's `iss` is
  `http://keycloak:8080/realms/customers`, the issuer `app-v1`'s discovery expects. Revisit when
  a public Keycloak hostname (`KC_HOSTNAME`) is fixed.
- `routes/auth-portals-token.json` (always applied, ticket 33): the same for realm `portals`
  (`POST /auth/realms/portals/protocol/openid-connect/token`, public client `mw-portal`), so the
  token's `iss` is the one `portal-org-v1`'s discovery expects.
- Test-only objects, `routes-test/` + `consumers-test/`: applied only when `apisix-init` runs with
  `MW_E2E_ROUTES=1`, which `make e2e` does on the running stack (`scripts/suite.sh`); every
  other run (a default `make up`) DELETEs them. Today: `test-core-mock` (`/test/core-mock/v1/*` ->
  core-mock's test/admin API: balances, PIN key, faults) and `test-simulators` (`/test/sim/*` ->
  mw-simulators' control API: forced scenarios) and `test-mail` (`GET /test/mail/*` -> GreenMail's
  REST API `/api/*`: mailboxes of the dev mail server, ticket 25), all with `ip-restriction` (loopback and
  private ranges only), `key-auth` (header `apikey`, hidden from the upstream) and
  `consumer-restriction` to consumer `mw_e2e`, whose key is `"@env:MW_E2E_API_KEY"`: generated by
  `scripts/gen-dev-env.sh` into the gitignored `deploy/compose/platform/.env`, never in the repo.
  `scripts/test/test_apisix_edge.sh` proves the default apply skips (and removes) them.

## Adding a route (tickets 22, 31, 33, ...)

1. `routes/<id>.json`: `uri`, `methods`, the auth plugin for the route group (spec 8.2), and the
   upstream by Compose DNS name (APISIX is on `app`). The global rule already gives it the
   correlation ID, the `X-Mw-*` strip and the error envelope.
2. Quota: copy the `limit-count` block with its own `group` and `count` (keep
   `allow_degradation: true` and `show_limit_quota_header: true`: the degradation alert needs it). Keyed per consumer:
   on `openid-connect` routes reuse `lua/consumer-from-jwt.lua`; on `hmac-auth`/`key-auth`/
   `jwt-auth` routes APISIX sets a consumer, so use `"key": "consumer_name"`.
3. Body schema: never here. The owning service adds
   `src/main/resources/derived-schemas/<id>-<op>.json` with only `x-mw-derived-from`, runs
   `scripts/derived-schemas.sh --write` and checks it on the exact bytes (its `service/BodySchema`
   from `filter/BodySchemaAdvice`, or mw-app-gateway's `config/BodySchemaConfig`), then adds a row
   to the table in "Body schemas (in the services)" and to `test_apisix_edge.sh`.
4. `client-control` `max_body_size` sized for the route (bulk upload routes need more).
5. Partner/MNO routes: `ip-restriction` (8.3) before auth.
6. Add the route's cases to `scripts/edge-test.sh` and run `make edge-test`.
7. Renaming or removing a route or consumer file: add its old `<kind>/<id>` to `retired.txt`.
