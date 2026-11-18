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
| `mw-classify` | the answer to the route output, with the fail-closed rules no mapping can override; `json-validator` on the output |
| `mw-audit` | the masked audit (`mw.audit.event.v1`) |

A breaking change to a Kamelet ships under a new name (`mw-http-call-v2`); the old one stays until no
LIVE route uses it.

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
