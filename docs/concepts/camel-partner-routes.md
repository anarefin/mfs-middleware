# Partner routes as config (Camel, ADR D26)

A partner of a known kind (a REST/JSON biller, later a telco, a bank, a SOAP or SFTP partner) is a
record, not code: a Camel YAML DSL route built from the shipped `mw-*` Kamelets, plus the JSONata
mappings it names, the hosts it may call and its error-code map. The record arrives on
`mw.config.changed.v1` (maker-checker in mw-admin-api); the adapter checks it, loads it beside the
version in use and swaps to it without a restart. Camel is used only in the `mw-adapter-*` modules
and only for this (ADR D1 amendment); Kafka goes through Spring Kafka (ADR D25).

First implemented in mw-adapter-billpay (ticket 82); its `CLAUDE.md` holds the details.

## The route contract

- In: `from: direct:partner-<id>`. The body is the canonical partner call (JSON: `partnerId`,
  `operation`, `txnId`, `reference`, `input`); the headers `mwOperation` (`PAY`, `INQUIRY`,
  `STATUS_QUERY`), `mwReference`, `mwCorrelationId`, `mwPartnerId`, `mwTxnId`. No `mw*` header ever
  leaves the service.
- Out: `{outcome, partnerRef, code, message, reason}` (plus `errorSuffix`, `retryable`,
  `attributes`), produced and checked by `mw-classify`. A missing or invalid output is UNKNOWN
  (fail closed).
- The route ends with `mw-classify` (directly, or at the end of every branch of a final `choice`)
  and then `mw-audit`.

## The Kamelet library

| Kamelet | What it does |
| --- | --- |
| `mw-map` | the request body from the canonical call, by a named JSONata mapping of the record (compiled once per version) |
| `mw-http-call` | one call to `url` + `path` (`{reference}` = our reference), signed by `mw-auth-<auth>`, inside the partner's circuit breaker with its time limit and bulkhead; a PAY is never sent again, an INQUIRY / STATUS_QUERY at most `inquiryRetries` times |
| `mw-auth-none`, `mw-auth-apikey`, `mw-auth-hmac-sha256` | the auth schemes; the secret is a field of the partner's own OpenBao secret, never in the route |
| `mw-auth-basic`, `mw-auth-oauth2-cc`, `mw-auth-jws` | more auth schemes (ticket 87, below) |
| `mw-classify` | the answer to the route output, with the fail-closed rules no mapping can override; `json-validator` on the output |
| `mw-audit` | the masked audit (`mw.audit.event.v1`) |

A breaking change to a Kamelet ships under a new name (`mw-http-call-v2`); the old one stays until no
LIVE route uses it.

## Auth options (ticket 87)

`mw-http-call` names the scheme with `auth=` and passes its parameters to the `mw-auth-<auth>` Kamelet,
again before every attempt. Every secret is a *field name* of the partner's own OpenBao secret
`secret/<adapter>/<partnerId>`, read through Camel's HashiCorp Vault function when the route loads (static,
D26); a value never appears in the route, a URI, a log or the audit.

| Option | `mw-http-call` parameters | What goes on the wire |
| --- | --- | --- |
| `auth=apikey` | `secretField`, `authHeader` | the key in one header (default `X-Api-Key`) |
| `auth=hmac-sha256` | `secretField`, `keyId` | `X-Signature` over method, path, timestamp, body hash |
| `auth=basic` | `userField`, `secretField` (both secret fields) | `Authorization: Basic base64(user:password)` (RFC 7617) |
| `auth=oauth2-cc` | `tokenUrl`, `clientId`, `secretField` (client secret), `scope`, `audience` | `Authorization: Bearer <token>`; the token from a client-credentials POST to `tokenUrl` (client authenticated with HTTP Basic), cached per partner until `expires_in` minus 30 s; a partner 401 drops it and the request is sent once more with a fresh one |
| `auth=jws` | `secretField` (PKCS#8 PEM key), `alg` (`RS256`, `ES256`), `keyId`, `authHeader` | the body signed as a detached JWS `header..signature` in `authHeader` (default `X-JWS-Signature`) |
| mTLS | `sslContextRef=<name>` (with any `auth`, https only) | the client certificate of context `<name>`: static config `mw.partners.tls.contexts.<name>.partner-id`, key store fields `<name>-keystore` (base64 PKCS#12), `<name>-keystore-password`, optional `<name>-truststore` / `<name>-truststore-password`, read at startup into the `SSLContextParameters` bean `mwTls-<name>`; only the owning partner's route may name it |

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
`services/mw-adapter-billpay/src/main/resources/application-dev.yml` is another.
