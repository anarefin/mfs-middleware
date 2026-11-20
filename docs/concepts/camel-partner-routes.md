# Partner routes as config (Camel, ADR D28)

A partner of a known kind (a REST/JSON biller, later a telco, a bank, a SOAP or SFTP partner) is a
record, not code: a Camel YAML DSL route built from the shipped `mw-*` Kamelets, plus the JSONata
mappings it names, the hosts it may call and its error-code map. The record arrives on
`mw.config.changed.v1` (maker-checker in mw-admin-api); the adapter checks it, loads it beside the
version in use and swaps to it without a restart. Camel is used only in the `mw-adapter-*` modules
and only for this (ADR D1 amendment); Kafka goes through Spring Kafka (ADR D27).

First implemented in mw-adapter-billpay (ticket 82); its `CLAUDE.md` holds the details.

A SOAP partner end to end: mw-adapter-merchant's `merchant-soap` record (tickets 115a/115b) is one
`choice` branch per operation (INQUIRY = ValidateMerchant, PAY = NotifyPayment, STATUS_QUERY =
QueryNotify, REVERSE = Refund), each `mw-map` -> `mw-soap-call` (XSLT request and response, a
WS-Security UsernameToken from the partner's Vault secret) -> `mw-classify`. mw-orchestrator sends
the INQUIRY before a MERCHANT_PAY to a merchant with its own system and the PAY (the notify) after it
completed; mw-simulators' `MerchantSoapMock` plays the merchant system. mw-admin-api holds the same
record as its dev seed (`partner-seeds/dev/merchant-soap.json`).

## The route contract

- In: `from: direct:partner-<id>`. The body is the canonical partner call (JSON: `partnerId`,
  `operation`, `txnId`, `reference`, `input`); the headers `mwOperation` (`PAY`, `INQUIRY`,
  `STATUS_QUERY`, `REVERSE`, `HEALTH_CHECK`), `mwReference`, `mwCorrelationId`, `mwPartnerId`, `mwTxnId`, `mwPartnerCode` (the call's `billerCode`,
  else its `productCode`). No `mw*` header ever leaves the service.
- Branch per biller: a `when` on `${header.mwPartnerCode} in 'KGDCL,JGTDSL,BTCL,LINK3'` before the REST
  ones sends those billers of the dev `billagg` over SOAP; the others stay on REST.
- Out: `{outcome, partnerRef, code, message, reason}` (plus `errorSuffix`, `retryable`,
  `attributes`), produced and checked by `mw-classify`. A missing or invalid output is UNKNOWN
  (fail closed).
- The route ends with `mw-classify` (directly, or at the end of every branch of a final `choice`)
  and then `mw-audit`.

## REVERSE, health and the payload store (ticket 97)

- **REVERSE** undoes an applied call: a route branch on `${header.mwOperation} == 'REVERSE'` maps
  (`mw-map`), calls (`mw-http-call` / `mw-soap-call`) and classifies (`mw-classify`) like a PAY. The
  path's `{reference}` stays our reference of the original call; the mapping input carries the
  idempotent reversal reference `reversalReference` = `reverse:<txnId>` (also header
  `mwReversalReference`), which the partner deduplicates on. A REVERSE is sent once, like a PAY: never
  again by the engine or the HTTP client (a 503 ends UNKNOWN `HTTP_5XX`); only the saga resends it
  (ticket 96). The dev seeds reverse at `POST <path>/{reference}/reverse`; mw-simulators reverses
  once and answers every later reverse with the first result.
- **Health** per partner (`PartnerRouteHealth`, `/actuator/health` `partners`): `UP`; `DEGRADED`
  (breaker half-open, or its failure rate at or over `mw.partners.health.degraded-failure-rate`,
  25 %); `DOWN` (route not running, breaker open, or a failing probe); `MAINTENANCE` (record flag
  `maintenance: true`, or inside `maintenanceWindow`; a call is then refused unsent as
  `MW-SYS-PARTNER_MAINTENANCE` with the window's bn/en message, ticket 131b); `DISABLED`; `DELETED`.
  A route's `url={{endpoint}}` is filled from `endpoints` by the selected `endpoint` (`MOCK` or
  `REAL`) before the loader checks it. A record with
  `healthCheck: true` has a `HEALTH_CHECK` branch, probed by the ShedLock job
  `scheduler/PartnerHealthJob` every `mw.partners.health.interval` (30 s). Every change goes out once
  on `mw.partner.route.status.v1` with `health` (a seed reports version 0), for routing (98) and the
  console.
- **Payload store**: `mw-audit` writes the full masked request and response (the record's
  `sensitiveFields`, then scrubbing) to the adapter's own table `partner_payload` (Flyway V6) with the
  latency; the `mw.audit.event.v1` event keeps the 4 KB excerpt plus `payloadRef`
  (`partner-payload/<uuid>`); rows older than `mw.retention.partner-payload` (30 d) are purged. A call
  without a txnId (bill fetch, dry-run, probe) is audited too.

## The Kamelet library

| Kamelet | What it does |
| --- | --- |
| `mw-map` | the request body from the canonical call, by a named JSONata mapping of the record (compiled once per version) |
| `mw-http-call` | one call to `url` + `path` (`{reference}` = our reference), signed by `mw-auth-<auth>`, inside the partner's circuit breaker with its time limit and bulkhead; a PAY is never sent again, an INQUIRY / STATUS_QUERY at most `inquiryRetries` times |
| `mw-auth-none`, `mw-auth-apikey`, `mw-auth-hmac-sha256` | the auth schemes; the secret is a field of the partner's own Vault secret, never in the route |
| `mw-auth-basic`, `mw-auth-oauth2-cc`, `mw-auth-jws` | more auth schemes (ticket 87, below) |
| `mw-classify` | the answer to the route output, with the fail-closed rules no mapping can override; `json-validator` on the output |
| `mw-audit` | the masked audit (`mw.audit.event.v1`) |
| `mw-sftp-source` | ticket 86, type source: the start of a batch route (`from:`); polls the partner's SFTP directory, strict host key checking against the partner's known hosts, each file once (JDBC idempotent repository), streamed, then `.done` or `.error`, never deleted |
| `mw-sftp-sink` | ticket 86, type sink: puts the file the route built on the partner's SFTP host (temporary name, then renamed, never over an existing file) |
| `mw-recon` | ticket 86: the last step of a batch route; every line of the file becomes `mw.partner.recon.v1` through the outbox, a file all or nothing |

A **batch route** (ticket 86) starts from `kamelet:mw-sftp-source?...` instead of
`direct:partner-<id>` and ends with `mw-recon`; it takes no calls, has no dry-run, and polls only while
the record is enabled (`deploy/partner-routes/examples/sftp-recon.yaml`, runbook "Batch partner over SFTP").

A breaking change to a Kamelet ships under a new name (`mw-http-call-v2`); the old one stays until no
LIVE route uses it.

## Auth options (ticket 87)

`mw-http-call` names the scheme with `auth=` and passes its parameters to the `mw-auth-<auth>` Kamelet,
again before every attempt. Every secret is a *field name* of the partner's own Vault secret
`secret/<adapter>/<partnerId>`, read through Camel's HashiCorp Vault function when the route loads (static,
D28); a value never appears in the route, a URI, a log or the audit.

| Option | `mw-http-call` parameters | What goes on the wire |
| --- | --- | --- |
| `auth=apikey` | `secretField`, `authHeader` | the key in one header (default `X-Api-Key`) |
| `auth=hmac-sha256` | `secretField`, `keyId` | `X-Signature` over method, path, timestamp, body hash |
| `auth=basic` | `userField`, `secretField` (both secret fields) | `Authorization: Basic base64(user:password)` (RFC 7617) |
| `auth=oauth2-cc` | `tokenUrl`, `clientId`, `secretField` (client secret), `scope`, `audience` | `Authorization: Bearer <token>`; the token from a client-credentials POST to `tokenUrl` (client authenticated with HTTP Basic), cached per partner until `expires_in` minus 30 s; a partner 401 drops it and the request is sent once more with a fresh one |
| `auth=jws` | `secretField` (PKCS#8 PEM key), `alg` (`RS256`, `ES256`), `keyId`, `authHeader` | the body signed as a detached JWS `header..signature` in `authHeader` (default `X-JWS-Signature`) |
| mTLS | `sslContextRef=<name>` (with any `auth`, https only) | the client certificate of context `<name>`: static config `mw.partners.tls.contexts.<name>.partner-id`, key store fields `<name>-keystore` (base64 PKCS#12), `<name>-keystore-password`, with `mw.partners.tls.contexts.<name>.truststore: true` also `<name>-truststore` / `<name>-truststore-password` (if that read fails the context is not registered: no fall-back to the JVM trust store), read at startup into the `SSLContextParameters` bean `mwTls-<name>`; only the owning partner's route may name it |

The `tokenUrl` host must be in the record's `allowedHosts` like the `url`: the token request goes through
the same partner http component, so the host guard and the checked DNS resolver apply. Camel's own
`oauth2*` http options are not used (the client secret would be an endpoint option, and a refused token
would not be dropped). An mTLS call with the wrong key store fails the handshake: UNKNOWN (or NOT_SENT),
never a success.

```yaml
- to: "kamelet:mw-http-call?url=https://api.gasco.example&method=POST&path=/v1/pay&auth=oauth2-cc&secretField=client-secret&clientId=bits-mw&scope=bill.pay&tokenUrl=https://auth.gasco.example/oauth/token"
```

## The guardrails

1. Maker-checker in mw-admin-api: a record goes LIVE only once a second person approves it.
2. The allowlist, on the parsed model: one route, only `choice`/`when`/`otherwise`, `to:
   kamelet:mw-*`, `setHeader` (not `Camel*`/`mw*`), `setBody`, `transform`, `log` (no body),
   `marshal`/`unmarshal` JSON; `constant` or a restricted `simple`; no `{{...}}`, no error handling,
   no beans, processors or raw endpoints.
3. Dry-run before the version goes live (ticket 83): the approved version is loaded beside the live one
   with a candidate (`direct:partner-<id>~candidate`, its calls sent to `mw.partners.dry-run.base-url`, in
   dev mw-simulators' stub `/sim/partners/<id>/**`, or to the record's `dryRun.baseUrl` sandbox, own circuit
   breaker); the record's `dryRun.operation` with `dryRun.sample` must answer SUCCESS or PENDING. Pass: the
   version is swapped in and reported LIVE on `mw.partner.route.status.v1`; fail: both are removed, the live
   version keeps serving, REJECTED with the problems. mw-admin-api checks the route at submit with a copy
   of the loader's rules (`MW-VAL-041`, line and construct).
4. The hosts of the approved record: checked when the route loads and before every send.

## Example

`deploy/partner-routes/examples/rest-apikey-biller.yaml` is a whole record: a REST/JSON biller with an
API key, PAY, STATUS_QUERY and INQUIRY. The billagg dev seed in
`services/mw-adapter-billpay/src/main/resources/application-dev.yml` is another. The telco operator
profiles `gp-ocs`, `robi-ocs`, `teletalk-ocs` and `bl-ocs` in
`services/mw-adapter-telco/src/main/resources/application-dev.yml` are four more, one auth scheme each
(api-key, hmac-sha256, oauth2-cc, basic); `ocs` stays the legacy alias of the channel operator ids.
