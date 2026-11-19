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
| Correlation ID | `request-id` on `X-Correlation-Id` (UUID when absent), forwarded upstream and returned. Its `_meta.pre_function` (`lua/edge-request.lua`) first drops an ID that is repeated, not `[A-Za-z0-9._:-]{1,128}` or holds a run of 6+ digits (same rule as the services' `CorrelationIdFilter`), so edge and services log the same ID. The ID is logged unmasked (only system ids escape the masking scrub; root `CLAUDE.md` "Masking"), so a caller ID that could hide an NID or MSISDN is replaced by a generated UUID. `traceparent` passes the edge untouched; the services drop one whose trace or parent id has such a run. The error log's `request_id` is this ID too. |
| Internal headers | the same pre_function removes every inbound `X-Mw-*` header (any spelling); `proxy-rewrite` cannot remove by prefix. |
| Security headers | `lua/edge-security-headers.lua` (header_filter, after the envelope): HSTS (1 year, subdomains) on every HTTPS response, `X-Content-Type-Options: nosniff` when the upstream sent none, and on edge-produced errors the services' `SecurityHeadersFilter` set (`Cache-Control: no-store`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Content-Security-Policy: default-src 'none'`). `config.yaml` hides the APISIX version (`enable_server_tokens: false`). Checked by the ZAP baseline (`make security`, `deploy/zap/decisions.tsv`). |
| Error envelope | `lua/edge-error-headers.lua` (header_filter) + `lua/edge-error-body.lua` (body_filter) turn any 4xx/5xx the edge produced itself into the 5.3 envelope. An upstream's JSON answer passes untouched. |
| Body logging | off: no logger plugin, default access log (no bodies). `test_apisix_edge.sh` fails on a logger plugin or `include_*_body`. |
| Access log masking | `config.yaml` `nginx_config.http.access_log_format` is APISIX's default format with the request URI, the referrer and the upstream URI swapped for masked copies (`$mw_log_uri`, `$mw_log_referer`, `$mw_log_upstream_uri`, nginx `map` chains in `http_configuration_snippet`): the query string is dropped and up to three MSISDNs per field (`01…`, `880`/`+880`/`%2B880` forms, not part of a longer digit run) become `0171****789`. An 11-digit run inside a UUID in the path is masked too (harmless; `$apisix_request_id` stays whole). `test_apisix_edge.sh` checks the format and emulates the maps; `make edge-test` checks a live line; `make log-scan` scans the result. |

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
| 429 | `MW-SYS-RATE_LIMITED` | yes | `limit-count`, `limit-req`; always with `Retry-After` (below) |
| 502, 503 | `MW-SYS-UPSTREAM_UNAVAILABLE` | yes | upstream unreachable |
| 504 | `MW-TMO-UPSTREAM_TIMEOUT` | yes | upstream timeout: outcome unknown, check the status first |
| other 5xx / 4xx | `MW-SYS-001` / `MW-VAL-002` | yes / no | anything else |

Rate limits (ticket 99, FRS PLT-004): every 429 carries `Retry-After` in whole seconds, set by the
edge header filter (`lua/edge-error-headers.lua`): limit-count's `X-RateLimit-Reset` (seconds to the
window's end), else 1 (limit-req), also on an upstream's own 429 that set none. A bucket is per
client and API: every limited route counts in its own `group` (the API) and keys the counter by a
per-client variable (`consumer_name`, `mw_consumer`, `mw_auth_key`, `mw_ussd_msisdn`, or
`remote_addr` where there is no consumer); `scripts/test/test_apisix_edge.sh` refuses a shared group or
another key. The window is fixed per group (limit-count); token-bucket quotas per registered client are
ticket 100.

Route `app-v1` (`/app/v1/*`), in plugin order:

1. `client-control` `max_body_size` 64 KiB (413).
2. `openid-connect` bearer-only against mw-auth (ticket 69, D21 amendment): `discovery`
   `http://mw-auth:8080/auth/v1/.well-known/openid-configuration` (mw-auth serves `issuer` and the
   in-stack `jwks_uri`), `use_jwks`, `client_id` `mw-app` (required by the plugin's schema; the
   token's `aud`), `iat_slack` 10 s (the plugin's default accepts a token 120 s past `exp`). The issuer
   is the discovery document's (exact match). An unknown `kid` makes the plugin fetch the JWK Set again,
   so a rotated `JWT_SIGN` key works at once; a discovery or JWKS fetch failure is 401 (fail closed).
   **Choice (ticket 69):** `openid-connect` with mw-auth's discovery document, not `jwt-auth`: APISIX
   3.19's `openid-connect` (lua-resty-openidc 1.9.0) verifies RSA JWKS only, which is why `JWT_SIGN`
   is RSA 3072 / RS256 (user decision, D21 amendment); `jwt-auth` has no JWKS and no issuer check and
   would need a consumer per key version re-applied on every rotation. mw-app-gateway verifies the token
   again (RS256, `typ at+jwt`, issuer, audience). A Keycloak token is refused here (another
   issuer); the Keycloak realm `customers` is removed (ticket 73).
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

Route `auth-v1` (`/auth/v1/*`, POST and PUT, tickets 68-70; PUT added in ticket 76) -> `mw-auth:8080`
(mw-auth's public port; its mTLS internal port 8443 is never routed, nor `/internal/*` on any port), path
and body unchanged: the OTP (`POST /auth/v1/otp`), device binding (`POST /auth/v1/devices`), login (`POST
/auth/v1/sessions`), refresh (`POST /auth/v1/tokens/refresh`), logout (`POST /auth/v1/sessions/logout`),
the first PIN (`POST /auth/v1/pin`), the PIN change (`PUT /auth/v1/pin`), the PIN reset (`POST
/auth/v1/pin-resets`) and its new PIN (`PUT /auth/v1/pin-resets/{id}/pin`).

1. `client-control` `max_body_size` 16 KiB (413): the bodies are an MSISDN, a key, an OTP, a PIN block.
2. No auth plugin (ticket 69): these requests prove the wallet themselves (device signature, OTP,
   PIN); logout and the first PIN carry mw-auth's own access token, which mw-auth verifies.
3. No `request-validation` and no rewrite: binding, login and refresh are signed by the device over
   the path and the raw body; mw-auth checks the derived schemas itself (table below).
4. `serverless-pre-function` (access, `lua/auth-rate-key.lua`): `ctx.var.mw_auth_key` =
   `msisdn:<sha256 hex of the canonical MSISDN of the body>` (never the MSISDN itself), else
   `sub:<bearer sub>` (logout, first PIN; not verified here, mw-auth does), else unset (per address).
5. `limit-count` 20 requests / 60 s per that key, group `auth-v1`, Valkey as on `app-v1`.
6. `limit-req` 5 requests/s, burst 20, per `remote_addr` (local to the node, 429): the per-address
   limit next to the per-wallet one (one `limit-count` per route).

Route `auth-v1-keys` (`GET /auth/v1/.well-known/jwks.json`, `GET /auth/v1/.well-known/assertion-jwks.json`,
`GET /auth/v1/pin-key`, tickets 69, 71, 76) -> `mw-auth:8080`: the access tokens' JWK Set, the transaction
assertions' (ES256) JWK Set and the PIN transport key the apps encrypt the PIN with; no auth,
`limit-count` 120 / 60 s per `remote_addr`.

Route `auth-v1-pin-resets` (`GET /auth/v1/pin-resets/*`, ticket 76) -> `mw-auth:8080`: the state of a
PIN reset, which the app polls while the core checks the factors (mw-auth answers only the device that
opened it, `X-Device-Id`); no body, no auth plugin, `limit-count` 60 / 60 s per `remote_addr`. The discovery document
(`/auth/v1/.well-known/openid-configuration`, which names the in-stack JWKS URL) is not routed: only
APISIX reads it, inside the stack.

Route `partner-billagg-callback` (`/partner/billagg/callback/*`, POST only, ticket 22) ->
`mw-adapter-billpay:8080`, path and body unchanged (the partner signed them):

1. `ip-restriction`: dev/MVP loopback and private ranges (the simulator posts from the `app`
   network, the host API suite through Docker's NAT); production lists the partner's egress
   addresses.
2. `client-control` `max_body_size` 16 KiB (413).
3. `limit-count` 600 / 60 s per `remote_addr`, Valkey as on `app-v1`.
4. No `hmac-auth`: the adapter verifies the partner's `hmac-sha256` signature with the secret in
   OpenBao and answers 401 `MW-AUTH-001` itself (decision in `services/mw-adapter-billpay/CLAUDE.md`).
   No `request-validation`: the aggregator's wire format is partner-specific, not a contract.
   Replay protection (ticket 99, FRS PLT-008) is the adapter's too: a callback signs `X-Nonce`
   besides `X-Timestamp` (within 300 s); a reused nonce is 401 like a bad signature.

Route `partner-c2wbank-callback` (`/partner/c2wbank/callback/*`, POST only, ticket 23) ->
`mw-adapter-bank:8080`: the same four rules (own `limit-count` group
`partner-c2wbank-callback`); the C2W bank's debit outcome callbacks, verified by mw-adapter-bank.

Routes `ussd-http-<mno>` (`/ussd/<mno>` for gp, robi, bl, teletalk; POST only; ticket 26, M3 gate)
-> `mw-ussd:8080`, path and body unchanged: the MNO HTTP USSD gateways (`HttpUssdTransport`,
contract `contracts/api/mw-ussd/ussd-http.v1.openapi.yaml`). One route and one consumer per MNO:

1. `ip-restriction` with `"@list:USSD_<MNO>_ALLOWED_CIDRS"`: the MNO's gateway addresses, from the
   apisix-init environment. Required, with no fallback: `deploy/docker-compose.yml` uses
   `${USSD_<MNO>_ALLOWED_CIDRS:?...}` and `apply.sh` fails on an unset or empty list, so a missing
   list stops the stack instead of opening the route. Dev values are fixed in the committed
   `deploy/dev.env.defaults` (copied to the gitignored `deploy/.env`): loopback and Docker's default address pools
   (`172.16.0.0/12`, `192.168.0.0/16`), where the compose networks and the Docker Desktop host
   gateway live (the compose networks have no fixed subnet). Edit the `deploy/.env` line if your Docker
   uses other pools. Production lists each MNO's egress addresses.
2. `client-control` 4 KiB (413). The step body's schema is checked by mw-ussd (`UssdStep`), not here.
3. `basic-auth` (`hide_credentials`: mw-ussd never sees it) against consumer `consumers/ussd_<mno>.json`,
   user `ussd-<mno>`, password `"@env:USSD_<MNO>_GATEWAY_SECRET"`: a fixed fake dev value
   from `deploy/dev.env.defaults` (in `deploy/.env`), also written to OpenBao `secret/mw-simulators` as
   `sim.ussd-gateway.credentials.<mno>` by the one-shot `provision` (the simulator plays the MNO). No
   credential 401 `MW-AUTH-001`.
4. `consumer-restriction` whitelist `[ussd_<mno>]`, 403: the consumer is bound to the `{mno}` path
   segment, so a GP credential on `/ussd/robi` is refused `MW-AUTH-002`.
5. `serverless-pre-function` (access, `lua/ussd-msisdn-key.lua`): `ctx.var.mw_ussd_msisdn` =
   `<mno>:<sha256 hex of the canonical 01X... MSISDN>` read from the step body (the body is read,
   not changed; no MSISDN in Valkey keys).
6. `limit-count` 120 steps / 60 s per MSISDN (group `ussd-http-<mno>`, Valkey, fail-open as every
   limited route; a step without a readable msisdn is counted per source address). A subscriber
   types far slower; 120 leaves room for the API suite's test MSISDNs. MNO contract value, TBC.

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
mw-admin-api later; the dev merchants `consumers-test/merchant_demo.json` (key id `merchant-demo`,
secret `CHECKOUT_DEMO_MERCHANT_SECRET`, wallet 01911000001 Karim Traders), `merchant_pharmacy.json`
(`merchant-pharmacy`, `CHECKOUT_PHARMACY_MERCHANT_SECRET`, 01911000002 Lalbagh Pharmacy) and
`merchant_fashion.json` (`merchant-fashion`, `CHECKOUT_FASHION_MERCHANT_SECRET`, 01911000003 Gulshan
Fashion House) take their secrets from the generated `.env` (each also synced to `secret/mw-checkout` as
that merchant's signing secret) and exist only with `MW_E2E_ROUTES=1`. A signed request
(APISIX 3.x `hmac-auth`): `Date: <RFC 7231 GMT>`, `Digest: SHA-256=<base64(sha256(body))>`,
`X-Nonce: <16-128 of A-Z a-z 0-9 . _ ->` (POST only, ticket 99), `Authorization: Signature
keyId="merchant-demo",algorithm="hmac-sha256",headers="@request-target date digest x-nonce",
signature="<base64(hmac-sha256(secret, signing string))>"`, signing string =
`keyId\n<METHOD> <path?query>\ndate: <Date>\ndigest: <Digest>\nx-nonce: <X-Nonce>\n` (each header
line `name: value` in the order of `headers`, every line ending in `\n`; a GET signs only
`@request-target date`). `checkout-v1-sessions` requires `x-nonce` among the signed headers
(`signed_headers`), and mw-checkout (`service/RequestReplayGuard`) refuses a `Date` 5 minutes or more
away (401 `MW-AUTH-005`) and a nonce the merchant already used (401 `MW-AUTH-006`; FRS PLT-008).
`tests/api/lib.js` (`checkout.signed`) and the test console (`crypto.ts` `checkoutHeaders`) sign this way.

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
| `app-v1` | `POST /app/v1/billers/{code}/fetch-bill` | mw-app-gateway, as above | `app-v1-fetch-bill-request.json` (`FetchBillRequest`) |
| `auth-v1` | `POST /auth/v1/otp` | mw-auth: `BodySchemaInterceptor` (cached bytes, the ones the device signs) | `auth-v1-otp-request.json` (`OtpRequest`) |
| `auth-v1` | `POST /auth/v1/devices` | mw-auth, as above | `auth-v1-device-binding-request.json` (`DeviceBindingRequest`) |
| `auth-v1` | `POST /auth/v1/sessions` | mw-auth, as above | `auth-v1-login-request.json` (`LoginRequest`) |
| `auth-v1` | `POST /auth/v1/tokens/refresh` | mw-auth, as above | `auth-v1-refresh-request.json` (`RefreshRequest`) |
| `auth-v1` | `POST /auth/v1/pin` | mw-auth, as above | `auth-v1-set-pin-request.json` (`SetPinRequest`) |
| `checkout-v1-sessions` | `POST /checkout/v1/sessions` | mw-checkout: `BodySchemaAdvice` | `checkout-v1-create-session.json` (`CreateSessionRequest`) |
| `checkout-pay` | `POST /checkout/pay/v1/{token}/otp` | mw-checkout, as above | `checkout-pay-v1-otp.json` (`OtpRequest`) |
| `checkout-pay` | `POST /checkout/pay/v1/{token}/confirm` | mw-checkout, as above | `checkout-pay-v1-confirm.json` (`ConfirmRequest`) |
| `portal-org-v1` | `POST /portal/org/v1/bulk/batches/{batchId}/reject` | mw-portal-api: `BodySchemaAdvice` | `portal-org-v1-reject-request.json` (inline reject body) |
| `ussd-http-<mno>` | `POST /ussd/{mno}` | mw-ussd: `BodySchemaAdvice` | `ussd-http-v1-step.json` (`UssdStep`) |

No body, so no schema: `POST /auth/v1/sessions/logout`, `POST /checkout/pay/v1/{token}/cancel`, portal
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

## Host routes for the API suite (tickets 10, 58)

- `routes/auth-portals-token.json` (always applied, ticket 33): `POST
  /auth/realms/portals/protocol/openid-connect/token`, only Keycloak's public token endpoint of
  realm `portals` (public client `mw-portal`; no other Keycloak path is exposed). Host and
  `X-Forwarded-*` are pinned to `keycloak:8080`, so the token's `iss` is the one `portal-org-v1`'s
  discovery expects. The realm demands TOTP (ticket 73): a password grant carries the form field
  `totp`. Revisit when a public Keycloak hostname (`KC_HOSTNAME`) is fixed. The realm `customers`
  and its route `auth-customers-token` are gone (ticket 73, D21; listed in `retired.txt`).
- `routes-test/auth-ops-token.json` (TEST ONLY, ticket 37; moved out of `routes/` after the
  security review: the ops realm's direct grant is never on the public gateway): the same for realm
  `ops` (dev-only public client `mw-e2e-ops`, direct grant only, no browser flow; the console's
  client `mw-admin-ui` has no direct grant, security review), so the token's `iss` is the one
  `admin-v1`'s discovery expects. Applied only with `MW_E2E_ROUTES=1` like the other test-only objects below, with the same
  `ip-restriction` / `key-auth` (`mw_e2e`, header `apikey`, sent by `tests/api/lib.js` `token('ops', ...)`)
  and a strict `limit-count` of 10 requests per minute per `remote_addr` (429 beyond). The realm
  demands TOTP on this grant (built-in direct-grant flow, conditional OTP): only users with an
  enrolled OTP get a token, i.e. the dev-only e2e operators `ops-e2e-maker01` / `ops-e2e-checker01`
  (secret `KC_OPS_TEST_TOTP_SECRET`); every other ops user must enrol TOTP in the browser first. The
  ops realm has brute-force detection on (`ops-realm.json`: 5 failures, wait from 60 s up to 15 min,
  quick-login check 1 s; `scripts/test/test_platform_compose.sh`). Keycloak imports a realm only when
  it does not exist yet, so an existing dev stack picks the settings up after `make down DOWN_ARGS=-v`.
- Test-only objects, `routes-test/` + `consumers-test/`: applied only when `apisix-init` runs with
  `MW_E2E_ROUTES=1`, which `make api-test` (`tests/api/run.sh`) and `make perf` (`scripts/suite.sh`) do on the running stack; every
  other run (a default `make up`) DELETEs them. Today: `test-core-mock` (`/test/core-mock/v1/*` ->
  core-mock's test/admin API: balances, faults) and `test-simulators` (`/test/sim/*` ->
  mw-simulators' control API: forced scenarios) and `test-mail` (`GET /test/mail/*` -> GreenMail's
  REST API `/api/*`: mailboxes of the dev mail server, ticket 25) and `test-audit-dw` (`GET
  /test/audit-dw/v1/*` -> mw-audit-dw's test API `/test/v1/audit/*`: audit event search and the
  hash-chain check of one month, ticket 58) and `test-orchestrator` (`/test/orchestrator/v1/*` ->
  mw-orchestrator's test API `/test/v1/*`: publish a REVERSAL of a transaction, see whether a
  request's idempotency key was handled, ticket 58) and `test-crypto` (`/test/crypto/v1/payshield/*` ->
mw-crypto's dev-only payShield test API `/test/v1/payshield/*` on its plain port 8082, never the mTLS
internal API: a PIN key's LMK blob for the simulator's PIN block helper, PVV generate and verify as the
test-only client `mw-e2e-crypto`, ticket 76; mw-crypto serves it only with `MW_CRYPTO_TEST_API_ENABLED=true`)
and, for the test console (`ui/mw-test-console`, ADR D17), `test-auth` (`GET`/`POST /test/auth/v1/accounts/*` ->
mw-auth's dev-only account test API `/test/v1/accounts/*` on its plain public port 8080, contract
`contracts/api/mw-auth/auth-test.v1.openapi.yaml`: a wallet's PIN, lockout, OTP and device state, `unlock`,
`unbind`, `pin`; mw-auth serves it only with `MW_AUTH_SEED_API_ENABLED=true`, 16 KiB bodies) and
`test-kc-admin-token` (`POST /test/kc/realms/master/protocol/openid-connect/token` -> Keycloak's master realm
token endpoint, the bootstrap admin `kc-admin` with client `admin-cli`; 10 requests per minute per
`remote_addr` like `auth-ops-token`, so the console caches the token for its lifetime) and `test-kc-admin`
(`GET`/`PUT`/`POST`/`DELETE /test/kc/admin/realms/(ops|portals)/users[/<segment>...]` -> Keycloak's admin REST API
`/admin/realms/$1/users$2`, plus `GET`/`DELETE` only of
`/test/kc/admin/realms/(ops|portals)/attack-detection/brute-force/users/<id>` -> `/admin/realms/$1/attack-detection/brute-force/users/<id>`
(the console's unlock of a brute-force lockout); matched by a `vars` expression: only realms `ops` and
`portals`, path segments of `[A-Za-z0-9_-]` only (no `..`, no other realm or admin resource, no other method on
the brute-force path: 404 at the edge); query strings pass through; 64 KiB bodies). Both Keycloak routes pin `Host` and `X-Forwarded-*` to `keycloak:8080`
(`KC_HOSTNAME_STRICT=false`, `KC_PROXY_HEADERS=xforwarded`): the master token's `iss` is then
`http://keycloak:8080/realms/master`, the issuer Keycloak's admin API expects for a request on that host.
These are all with `ip-restriction` (loopback and
  private ranges only), `key-auth` (header `apikey`, hidden from the upstream) and
  `consumer-restriction` to consumer `mw_e2e`, whose key is `"@env:MW_E2E_API_KEY"`: a fixed fake dev value
  (`e2e-test-apikey`) committed in `deploy/dev.env.defaults` and copied to the gitignored
  `deploy/.env` (ADR D9 amendment); real environments never enable these routes.
  `scripts/test/test_apisix_edge.sh` proves the default apply skips (and removes) them.

## Admin route (ticket 36)

`routes/admin-v1.json` (`GET`/`POST /admin/v1/*` -> mw-admin-api): `client-control` 1 MiB,
`openid-connect` bearer-only on realm `ops` (client `mw-admin-ui`; every ops user needs password +
TOTP), quota 300/min per token subject. The roles of spec 15.4 are checked by mw-admin-api, not
here. Spec 15.1 puts the console on the internal gateway only; the MVP has one APISIX, so the route
is on it (known gap: no network split until a second, internal APISIX exists).

## Admin console routes (ticket 40)

- `routes/admin-ui.json` (`GET`/`HEAD`/`POST /console`, `/console/*` -> `mw-admin-ui:3000`): no auth
  plugin (the console logs operators in itself with Auth.js, realm `ops`, Authorization Code + PKCE),
  64 KiB bodies, quota 600/min per source address. The console sets its own CSP, HSTS and
  X-Frame-Options DENY.
- `routes/auth-ops-browser.json` (`/realms/ops/*`, `/resources/*` -> `keycloak:8080`): the
  browser's Keycloak login and OTP pages. Security review (sec-ops), host-header injection: the
  route answers only for the public host (`"host": "@env:MW_PUBLIC_HOST"`, apisix-init env, default
  `localhost`; any other `Host` -> 404 at the edge), and `proxy-rewrite` pins
  `X-Forwarded-Host` to the same value (never `$host`, so a client Host can never shape Keycloak's
  links, reset mails or issuer), `X-Forwarded-Proto: https`, `X-Forwarded-Port: 443` (APISIX would
  send its listener port 9443), and removes a client `Forwarded` / `X-Forwarded-Prefix`. Keycloak
  builds public URLs and issues tokens with `iss` `https://localhost/realms/ops`; mw-admin-ui sends
  the same headers on its in-stack token calls. Port 443 only; `MW_PUBLIC_HOST` must match
  `MW_ADMIN_UI_PUBLIC_URL`'s host.
- Token endpoint on this route (`lua/ops-token-grant.lua`, access phase): `POST
  /realms/ops/protocol/openid-connect/token` must be a form with one `grant_type`,
  `authorization_code` or `refresh_token` (the browser login: client `mw-admin-ui` is Authorization
  Code + PKCE only, direct grant off; Keycloak's account console); any other grant (password,
  client credentials, token exchange, device, CIBA) -> 403 `MW-AUTH-002`, a non-form body -> 400,
  other methods 405, sub-paths such as `.../token/introspect` -> 404. The path is folded as
  Keycloak routes it (case, `;` path parameters, repeated slashes). mw-admin-ui itself exchanges
  codes and refreshes in the stack (`http://keycloak:8080`), not through this route.
- Keycloak hostname (decision): `KC_HOSTNAME` stays unset and `KC_HOSTNAME_STRICT=false`, because
  the in-stack callers (APISIX discovery/JWKS, services realm client credentials, e2e token routes)
  rely on `http://keycloak:8080` issuers; a fixed hostname would move every realm's issuer. The edge
  pinning above is what keeps a client Host out. `make edge-test` checks it live (foreign Host 404,
  client X-Forwarded-Host ignored, password grant 403, code grant reaches Keycloak, introspection
  404, mw-admin-ui direct grant refused by Keycloak).
- `admin-v1` therefore accepts two issuers (`claim_validator.issuer.valid_issuers`): the in-stack
  `http://keycloak:8080/realms/ops` (tokens asked for directly, e2e) and the public one. Without the
  list APISIX accepts only the discovery document's issuer.

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
