# mw-adapter-billpay

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.adapter.billpay`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Bill pay adapter (spec WP15 part, 7.3 rule 5, 7.4, 11): consumes `mw.partner.request.billpay.v1`
  (`messaging/BillpayRequestHandler`, a Spring Kafka `@KafkaListener` with retry topics + DLQ), checks a `PAY` / `STATUS_QUERY` / `REVERSE` (ticket 97) request
  (`mapper/PartnerCallMapper`: family `billpay`, `partner.billerCode` and a `BILLER_ACCOUNT` credit
  party with a bill number for PAY), claims the request in the inbox (consumer
  `mw-adapter-billpay.partner-call`, committed before the call: a request Kafka delivers again is
  never sent to the partner again, metric `mw.partner.request.duplicate`), calls the aggregator once
  through its partner route (`partner` package, below) with our stable reference, and publishes
  `mw.partner.reply.v1` through the outbox (inbox-deduplicated on `requestId`). `service/BillPaymentService` is the copy of telco's
  TopupService; replies, outcomes and safety rules are telco's (see mw-adapter-telco CLAUDE.md):
  SUCCESS (`partnerRef` = aggregator's `paymentRef`), FAILED `MW-PTR-<AGGREGATOR>-<suffix>` from
  the record's `error-map` (unlisted `UNMAPPED`), FAILED `MW-PTR-UNKNOWN-PARTNER` (not retryable) for a
  partnerId that is neither a partner nor a `partner-aliases` entry, FAILED `MW-SYS-PARTNER_*` when nothing left
  (retryable), UNKNOWN `MW-TMO-PARTNER_*` (status inquiry resolves it), NOT_FOUND for a
  STATUS_QUERY only, a PAY past its `deadlineAt` refused unsent (`MW-SYS-PARTNER_REQUEST_EXPIRED`),
  FAILED `MW-VAL-INVALID_PARTNER_REQUEST` for requests that cannot be mapped.
- Stable partner reference (`service/PartnerReferenceService`, table `partner_reference` in own
  schema `billpay_ref`; mw-adapter-telco owns `partner_ref`): `BITS` + txnId hex, stored on first
  use, sent as the aggregator's `transactionId`; a repeated one returns the original outcome.
- The aggregator call runs on a virtual-thread executor (`billpayCallExecutor`), never on the
  Kafka consumer thread; the offset is committed at hand-over (crash -> orchestrator deadline ->
  UNKNOWN -> TSQ, as telco).
- Biller catalogue (`config/BillerProperties`, `mw.billers.<code>: {name, category, partner-id}`):
  which partner instance (aggregator) serves which biller. Static seed of 10 Bangladesh billers, all ->
  `billagg` (REST: BREB, CWASA, DESCO, DPDC, TITAS, WASA; SOAP: BTCL, JGTDSL, KGDCL, LINK3, see the
  aggregator paragraph at the end of "Partner engine") until mw-admin-api publishes the catalogue (spec 11.2, ticket 38). Routing and failover
  between aggregators (FR-09) are not built: one aggregator per biller.
- Internal API (ticket 21; contract `api/mw-adapter-billpay/billpay.v1.openapi.yaml`, owned here),
  `controller/BillerController` + `service/BillFetchService`: `GET /billpay/v1/billers` (catalogue
  with `partnerId`) and `POST /billpay/v1/billers/{code}/bills/fetch {billNumber}` -> `{billerCode,
  billNumber, amount {valueMinor, currency}, dueDate, billPeriod}`. The fetch is one framework call
  with operation `INQUIRY` (same instance, circuit breaker, bulkhead and time limit as payments; no
  reference, no txnId, nothing written). Errors: unknown biller 404 `MW-VAL-003`; refusal 502
  `MW-PTR-<AGGREGATOR>-<suffix>` (e.g. `BILL_NOT_FOUND`); not sent 503 `MW-SYS-PARTNER_*`; timeout,
  ambiguous code, unreadable amount 504 `MW-TMO-PARTNER_*` (both retryable). Runs on the HTTP
  request's (virtual) thread. Clients: mw-app-gateway (`POST /app/v1/billers/{code}/fetch-bill`) and
  mw-ussd (Pay Bill menu: `billers` list and the `bill` lookup, `client/BillPayClient`), over the
  `app` network (no APISIX route,
  no published port; the zone is the access control, as for every service-to-service call in the
  MVP). See "Design decision: synchronous bill fetch" below.
- Partner callbacks (ticket 22, spec 11.2 inbound path; `controller/PartnerCallbackController`,
  `service/PartnerCallbackService`, `mapper/PartnerInboundMapper`, own type `dto/PartnerInbound`):
  `POST /partner/{partnerId}/callback/**`, reached from outside only through the APISIX route of
  that partner (`deploy/apisix/routes/partner-billagg-callback.json`: ip allow-list, 16 KiB body
  cap, 600/min per source address in Valkey; path and body forwarded unchanged). Steps: (0) the path's partner id must
  match `^[a-z0-9][a-z0-9-]{0,63}$` (`@Pattern` on the path variable; else 400 `MW-VAL-001`, detail
  `partnerId`), and `mapper/InboundCallMapper` builds the framework's `InboundCall` (T7); (1) the
  partner in the URL must have config of its own (no default-partner fallback) else 404; (2) the
  framework verifies the signature with that partner's auth scheme and maps the body
  (`PartnerGateway.inbound`, CALLBACK operation): not authentic 401 `MW-AUTH-001`
  (`security.partner_callback_rejected`), unreadable 400 `MW-VAL-002`; (3) the callback's
  `reference` must be one this adapter issued for that partner (`partner_reference`), giving the
  txnId, else 404 `MW-VAL-003`; (4) inbox row (consumer `mw-adapter-billpay.callbacks`, key
  `inboundId` = name UUID of `<partnerId>:<reference>:<outcome>`) and a synchronous Kafka send
  (`acks=all`) of `mw.partner.inbound.v1` (family `billpay`, kind `CALLBACK`, key txnId) in one DB
  transaction, then 200 `{status: ACCEPTED, inboundId, duplicate}`. Kafka down: rolled back, 503
  `MW-SYS-EVENT_NOT_WRITTEN` (retryable; the partner sends again). A duplicate (same partner,
  reference, outcome) is acknowledged `duplicate: true` and not published again; a PENDING and the
  final outcome of one reference are different callbacks. FAILED carries `errorCode`
  `MW-PTR-<PARTNER>-<suffix>` from the error map; a FAILED with an ambiguous code is published as
  PENDING (fail closed); `partnerRef` = the aggregator's `paymentRef`, else our reference;
  `attributes.message` = the partner's message.
- Partner-call audit (S6, spec 12.2, 17): the `mw-audit` Kamelet (`partner/kamelet/MwAuditStep`)
  hands every call to `partner/route/PartnerAudit.publishing` (wired in `PartnerFrameworkConfiguration`), which logs `partner.call.audit` and hands every masked entry to
  `messaging/PartnerAuditPublisher`, which writes an `AuditEvent` (`dto/AuditEvent`, contract
  `mw.audit.event.v1` owned by mw-audit-dw, unchanged) to the outbox in its own transaction
  (REQUIRES_NEW): action `partner.call.completed`, actor `system`/<service>, resource
  `partner/<partnerId>/<operation>`, outcome SUCCESS (partner SUCCESS/PENDING) else FAILURE,
  `after` = `partnerInstance`, `httpMethod`, `path`, `httpStatus`, `partnerOutcome`, `durationMs`
  and the `request`/`response` bodies as the framework masked them (`sensitive-fields`, scrub, 4 KB
  excerpt); `payloadRef` (`partner-payload/<uuid>`) names the full masked bodies in this adapter's
  `partner_payload` table (ticket 97, below). Key txnId (else partnerId: a bill fetch has no txnId and is
  audited under the biller's aggregator; its 6.1 txn header is the audit id, ticket 97: such calls used
  to be dropped). A failing publisher is logged
  `partner.call.audit_failed` and never fails the call. Identical in telco, billpay and bank.
- Dev provisioning (`provision_mw_adapter_billpay` in `deploy/provision/services.sh`, run by the one-shot `provision` on every `docker compose up`, idempotent, dev only): role
  `adapter_billpay_rw` and schema `billpay_ref`, `secret/mw-adapter-billpay` (DB password, never
  overwritten), `secret/mw-adapter-billpay/billagg` (fields `hmac-secret` from `BILLAGG_HMAC_SECRET` and
  `ws-password` from `BILLAGG_WS_PASSWORD`; the route reads both when it loads, so a missing field leaves
  billagg without a route) and
  the AppRole `mw-adapter-billpay` (policy: `secret/mw-adapter-billpay` and `secret/mw-adapter-billpay/*`;
  configtree volume at `/run/mw/approle`).

## Design decision: synchronous bill fetch (ticket 21)

The app's `POST /app/v1/billers/{code}/fetch-bill` needs an answer within the HTTP call, but the
partner topics run orchestrator -> adapter -> orchestrator and `mw.partner.reply.v1` is consumed
by mw-orchestrator only. Chosen: **mw-app-gateway calls this adapter's internal HTTP API** for the
catalogue and the fetch (mw-ussd's Pay Bill menu later reused the same API for the same reasons). Why:
- A fetch moves no money and has no saga: it needs no idempotency key, txnId or saga (its partner call is still audited, S6), and
  spec 2.1's "no direct service-to-service calls on the money path" does not apply. The payment
  itself still goes app -> Kafka -> orchestrator -> `mw.partner.request.billpay.v1` -> this adapter.
- No Kafka thread ever waits: the gateway waits on its HTTP (virtual) thread, this adapter calls
  the aggregator on its HTTP (virtual) thread.
- Module isolation holds: the dependency is a contract (`contracts/api/mw-adapter-billpay/`), not
  code; each side has its own DTOs. No contract of another owner changes.
- Rejected alternative: Kafka request/reply with the gateway's reply-waiter. It would put the
  gateway on `mw.partner.request.billpay.v1` (orchestrator-owned, produced only by the orchestrator)
  and route INQUIRY replies to a topic the orchestrator consumes (it would see replies for txnIds it
  does not know), or need two new topics and contracts plus per-instance reply routing, for a read.
- Ticket 22 (async callbacks) builds on the payment path, not on this API (see "Partner
  callbacks" above and "Design decision: callback signature" below).

## Design decision: callback signature at the adapter, not APISIX hmac-auth (ticket 22)

Spec 8.2 lists `hmac-auth` / `jwt-auth` / mTLS per partner at the edge; the adapter framework
already has a per-partner `hmac-sha256` auth scheme. Chosen: **the adapter verifies the
signature**; APISIX does ip allow-list, body cap and quota only. Why: the partner's wire scheme
(header names, string to sign, key id, skew) is partner config here and changes with a
config-changed record, whereas APISIX `hmac-auth` has its own fixed scheme (`Authorization:
Signature keyId=...`) that real aggregators do not speak; the secret stays in OpenBao
(`secret/mw-adapter-billpay/<partner>`) instead of a consumer credential in etcd; the 401 is the
service's 5.3 envelope. Cost: an unsigned flood reaches the adapter (bounded by the quota and the
allow-list). A partner that speaks APISIX `hmac-auth`/`jwt-auth`/mTLS can get that plugin on its
own route as an extra layer; the adapter check stays (the `none` scheme refuses every callback).

## Partner engine (`partner` package; ADR D28, ticket 82)

A partner is a **record holding a Camel YAML DSL route** built from the shipped Kamelets; a new
REST/JSON biller is a `mw.config.changed.v1` record, no Java and no restart (`NewBillerRouteIT`).
This module is the first; ticket 84 copied it into telco and bank (the `partner/` trees, main and the shared
tests, and the Kamelet folders are identical apart from the package), 85-87 add Kamelets. Camel stays behind
`partner/route/PartnerGateway` (one shared `ProducerTemplate`); Kafka is Spring Kafka (below).

- **Packages** (the framework exception of the root CLAUDE.md): `partner/` the SPI and
  `PartnerFrameworkConfiguration`; `partner/config/` the record, `PartnerConfigStore`, the
  config-changed listener, `PartnerEndpointPolicy`, `PartnerTlsProperties`; `partner/route/` the loader
  (`PartnerRouteLoader`, `PartnerBatchRoutes`, `QueryParams`), `PartnerRoutes`, `PartnerGateway`,
  `PartnerHostGuard`, `PartnerDnsResolver`, `PartnerDryRun`, `PartnerAudit`, `PartnerRecon`,
  `KameletCatalogue`; `partner/kamelet/` the Java steps of the Kamelets (`mwHttp`, `mwSoap`, `mwAuth`,
  `mwMap`, `mwClassify`, `mwAudit`, `mwRecon`, `mwSftpSink`); `partner/auth/` the auth schemes,
  `PartnerCredentials`, `OAuth2ClientCredentials`, `DetachedJws`, `PartnerTlsContexts`; `partner/mapping/`
  `CompiledMapping` (JSONata) and the SOAP mappings (`SoapMapping`: `CompiledStylesheet` + `StylesheetPolicy`
  for XSLT, which also holds the one secure XML parser setup, and `JsonXmlMapping`).

- **Record** (`partner/config/PartnerConfig`, `mw.partners.instances.<id>` seeds, `partner/<id>`
  records): `route`, `mappings` (JSONata by name, `<OPERATION>.request` / `<OPERATION>.response`,
  `CALLBACK.response` for callbacks; compiled once per version, `partner/mapping/CompiledMapping`),
  `allowedHosts`, `family`, `inquiryRetries` (default 1), `resilience` (breaker/bulkhead overrides;
  null = the resilience4j instance named by the partner id), `dryRun {operation, sample, baseUrl?}` (ticket 83),
  timeouts, `headers`, `auth` (callback verification), `errorMap`, `ambiguousCodes`, `sensitiveFields`.
  The old shape (`template` + `baseUrl` + `operations`) is gone since ticket 84: a record without a
  `route` is refused (`a route is required`). `mw.partners.family` is required (no default).
- **Route contract** (`partner/route/PartnerExchange`): in `from: direct:partner-<id>`, body the
  `PartnerCall` JSON, headers `mwOperation`, `mwReference`, `mwCorrelationId`, `mwPartnerId`, `mwTxnId`,
  `mwPartnerCode` (`PartnerExchange.PARTNER_CODE`: `input.partner.billerCode`, else `productCode`, when a
  non-blank string; absent otherwise; set by `PartnerGateway.send`, so a route can branch per biller or
  product; `PartnerGatewayPartnerCodeTest`, identical in the three adapters);
  the runtime of the version (`PartnerRuntime`) and the operation ride as exchange properties (a route
  cannot change them). Out: `{outcome, partnerRef, code, message, reason, errorSuffix, retryable,
  attributes}` (`PartnerResult`), checked by `json-validator` against
  `classpath:partner/partner-result.schema.json` in `mw-classify`; a missing or invalid one is UNKNOWN
  (NOT_SENT `MISCONFIGURED` when nothing was sent). The route ends with `mw-classify` (directly, or at
  the end of every branch of a final `choice`) and then `mw-audit`.
- **Kamelets** (`src/main/resources/kamelets/*.kamelet.yaml`, Java steps in `partner/kamelet/`):
  `mw-map` (request mapping), `mw-http-call` (url + method + path, `{reference}` URL-encoded into
  `CamelHttpPath`; `auth=none|apikey|hmac-sha256` calls `mw-auth-<auth>`; resilience4j breaker with
  `timeoutDuration` = the record's `timeoutMs` and a bulkhead; `onFallback` always; a 5xx throws inside
  the breaker so it counts and ends UNKNOWN `HTTP_5XX`; a `loop` re-sends only an INQUIRY / STATUS_QUERY,
  at most `inquiryRetries`, after a timeout, 5xx, reset or failed connect; a PAY never), `mw-auth-none`,
  `mw-auth-apikey`, `mw-auth-hmac-sha256` (the `partner/auth` schemes, same wire format,
  `AuthSchemesTest`), and since ticket 87 `mw-auth-basic` (`userField` + `secretField`),
  `mw-auth-oauth2-cc` and `mw-auth-jws` (below, "Auth options"), `mw-classify` (the fail-closed rules of the former `RestJsonAdapter.classify`:
  SUCCESS/PENDING need a 2xx and a reference, PENDING only with `pending=true`, FAILED a code and a
  `definiteStatuses` status, ambiguous codes AMBIGUOUS, NOT_FOUND only for STATUS_QUERY), `mw-audit`.
  `partnerId`, `timeoutMs`, `connectTimeoutMs`, `readTimeoutMs`, `bulkhead` are injected by the loader
  from the record, never set by a route. `KameletCatalogueTest` lints them (type label, title,
  description, documented properties, `format: password` + password `x-descriptors` on secret
  references, no endpoint but `kamelet:`, `json-validator:`, the partner's `{{url}}` and, in `mw-soap-call`
  only, `cxf://{{address}}` in PAYLOAD mode). **Versioning:**
  a breaking change ships as a new name (`mw-http-call-v2`); the old file stays until no LIVE route uses
  it; `PartnerRuntime.kamelets()` lists what a version uses: `GET /internal/v1/partners/kamelet-usage`
  (`controller/PartnerUsageController` -> `service/KameletUsageService`, contract `billpay.v1`) and the
  `kamelets` of each LIVE status report.
- **SOAP partners** (ticket 85; `mw-soap-call`, `partner/kamelet/MwSoapStep`, `partner/mapping/CompiledStylesheet`
  + `StylesheetPolicy`; example `deploy/partner-routes/examples/soap-biller.yaml`, simulator stub `soapbill`):
  `mw-map` (JSONata) -> `mw-soap-call?address=<static http(s) URL>&request=<x.xsl>&response=<y.xsl>&soapAction=..
  &soapVersion=1.1|1.2&wsUsername=..&wsPasswordSecret=<field>&wsTokenType=PasswordText|PasswordDigest` ->
  `mw-classify` (`definiteStatuses=200`) -> `mw-audit`. Review of 85: `auth=basic&userField=..&secretField=..`
  calls `mw-auth-basic` before each attempt (camel-cxf sends the `Authorization` header as an HTTP header;
  no other scheme), `sslContextRef=<name>` sends through a `toD` with `sslContextParameters=#mwTls-<name>`
  (as `mw-http-call`: the partner's own context, https; the host guard allows that option on `cxf*` too;
  `PartnerAuthKameletsIT` runs both against WireMock), and `request=json` / `response=json` (with
  `namespace=<urn: or http(s) URI>`) replace a stylesheet by the fixed JSON/XML convention of
  `partner/mapping/JsonXmlMapping` (one root key = the body element, keys elements in `namespace`, `@x`
  attributes, arrays repeat; back: local names, `@x`, repeated elements as arrays, a fault as
  `{"fault": {"@code", "@subcode", "@reason", "detail"}}`), so JSONata maps both sides; the DOM builds the
  XML, a value is never markup. A mapping whose name ends in `.xsl` is an XSLT 3.0
  stylesheet (or `classpath:partner/xslt/*.xsl`), compiled once per version with Saxon-HE: the request one
  reads mw-map's JSON as its XPath 3.1 XML form (`fn:json-to-xml`) and makes the SOAP body element; the
  response one turns the SOAP body, or a fault as `<fault xmlns="urn:bits:mw:soap-fault" code subcode
  reason><detail/></fault>`, into JSON (`xsl:output method="json"`) for the JSONata response mapping; both
  answers report status 200 to `mw-classify` (the fault's code maps through `errorMap`; a `soap:Server`
  fault should map to UNKNOWN). Give a catch-all `/fn:map` template `priority="-1"`: two templates of equal
  priority make Saxon take the last one. A stylesheet may not reach outside itself (`StylesheetPolicy`, the
  same in mw-admin-api: no DOCTYPE/entity, `document()`/`doc()`/`unparsed-text()`/`collection()`/
  `json-doc()`/`transform()`/`function-lookup()`, `xsl:import`/`include`/`result-document`/
  `source-document`/`evaluate`, `parse-xml`/`parse-xml-fragment`/`json-to-xml`, extension namespaces,
  shadow attributes (`_select`), static params/variables, a DOCTYPE or entity even inside a value; a security
  refusal), then the compiled tree is checked as well (Saxon's `explain` export: no `fn`/`fnRef`/`*Call` of
  those functions, no `resultDoc`/`sourceDoc`/`evaluate`), whatever the text built. Saxon runs with every
  resource, unparsed-text and collection resolver denied, its own parse options on a DOCTYPE-refusing reader
  with no entity resolution, an environment-variable resolver that shows nothing, external functions off,
  `xsl:message` dropped; the partner's XML is parsed with DOCTYPE refused (security review of ticket 85). The send is camel-cxf PAYLOAD dispatch (no WSDL: verified that the body
  element goes out as it is; an empty `wsdlURL=` breaks the dispatch, so `wsdlUrl` / `serviceName` /
  `portName` are not offered: a remote WSDL would also be fetched outside the host guard), SOAP 1.2 by
  `bindingId`, the action through camel-cxf's `SOAPAction` header, CXF connect / receive timeouts from the
  record through the `RequestContext` header (`jakarta.xml.ws.client.*`), inside the same breaker, time limit,
  bulkhead, INQUIRY loop and `mwHttp.fallback` as `mw-http-call` (a `SoapFault` is caught inside the breaker,
  so a business fault never opens it; connect failure NOT_SENT, `HttpTimeoutException` UNKNOWN TIMEOUT).
  `headerFilterStrategy=#mwSoapHeaderFilter` (`PartnerSoapHeaderFilterStrategy`) keeps `mw*`, `Camel*`,
  `RequestContext` and `org.apache.cxf.*` headers in (without it they go out as HTTP headers). The
  WS-Security UsernameToken (OASIS 1.0, built in `MwSoapStep`, no WSS4J) has its password from the record's
  secret field (read at load). The host guard also intercepts `cxf*`: only `cxf://<address>` with
  `dataFormat=PAYLOAD`, the header filter and the SOAP 1.2 binding id, no `CamelDestinationOverrideUrl`, then
  the http host rules. Open item: CXF resolves the host again when it connects (the http components check
  the connected addresses, `PartnerDnsResolver`; CXF's conduit does not). Camel's `xslt-saxon` component is
  not used: in 4.22 its `XsltUriResolver` still resolves `http:`/`file:` in `document()` at transform time
  (Camel 4.23 upgrade guide), and it compiles per endpoint. Saxon-HE on the classpath is also the JVM's JAXP
  `TransformerFactory` (its service file); only DOM/StAX already parsed by CXF passes through it.
- **Loader** (`partner/route/PartnerRouteLoader`, order matters): record problems; no `{{` anywhere;
  SnakeYAML safe load: exactly one `- route:` holding only `from {uri: direct:partner-<id>, steps}`, no
  error-handling keys anywhere (Camel's YAML loader acts on top-level `beans`, `sslContextParameters`,
  `restConfiguration` while parsing, so nothing else reaches it); Camel's `schema/camelYamlDsl.json`
  (networknt, draft 4); Kamelet calls only as `to: "kamelet:mw-*"` strings of the library, without
  `location`/`templateId`/... or loader parameters, `${...}` refused; parameters are URL-decoded as
  Camel reads them, keys must be `^[A-Za-z][A-Za-z0-9]*$` and appear once, `RAW(...)` is refused and `url`/`path`/`authHeader`/`keyId` may hold no `% ? & #`;
  `{{` is looked for in every parsed string value (YAML escapes and block scalars cannot hide it) and in
  the YAML handed to Camel; then it injects route id
  `partner-<id>-v<version>`, `from: direct:partner-<id>-v<version>` and `routeConfigurationId
  mw-partner` into the tree (Camel binds route configurations while it parses, too late afterwards)
  and Camel parses it (`RoutesLoader`); the model walk is an allowlist (`choice`/`when`/`otherwise`,
  `to`, `setHeader` not `Camel*`/`mw*`, `setBody`, `transform`, `log` without the body, JSON
  `marshal`/`unmarshal`; `constant` or `simple` reading only `${body}`, `${header.x}`, `${variable.x}`,
  `${date:now}`); every `mw-http-call` URL against the record's hosts; mappings exist; secret fields read
  now. `PartnerRouteLoaderTest` covers each refusal.
- **Error handling** (`partner/route/PartnerRouteConfiguration`): one `routeConfiguration` `mw-partner`
  for every partner route, `defaultErrorHandler` with no redeliveries (`noErrorHandler` would ignore
  `onException`), `onException(Exception)` handled -> `mwAudit.failed`. `camel.main.message-history`,
  `tracing`, `routes-reload-enabled` off.
- **Lifecycle** (`partner/route/PartnerRoutes`, `partner/config/PartnerConfigStore`): a new version is
  loaded and started beside the live one, the snapshot flips, then the old version is stopped in the
  background (waiting up to its `timeoutMs` + 5 s so calls in flight finish on it) and removed with
  every Kamelet route whose `parent` is it. A refused record leaves the last good version live and logs
  `security.partner_route_refused` (`securityRule` true for a security rule). The previous record is
  kept in the store's `Entry`. Seeds load once Camel has started; a refused seed leaves that partner
  without a route, never the service down. Shutdown: `camel.main.shutdown-timeout` 30 s (25 s + 5 s).
- **Family** (ticket 83): `mw.partners.family` (`billpay`): a record whose `value.family` is another
  family is skipped (`config.other_family`, debug), not refused.
- **Dry-run** (ADR D28 guardrail 3, ticket 83; `partner/route/PartnerDryRun`, `PartnerConfigStore`): a
  record with `dryRun` goes to one dry-run worker (virtual thread, records in arrival order, never the
  Kafka thread; `partner.dry_run.queued`): the version is loaded beside the live one
  (`PartnerRoutes.load`) and so is its candidate (`PartnerRoutes.loadCandidate`, loader with a
  `DryRunTarget`): route `partner-<id>~candidate~v<n>` from `direct:partner-<id>~candidate`, circuit
  breaker `mw-cb-<id>~candidate` (default config, so the live breaker never counts a dry-run; `~` is
  outside the id characters, so no id collides), every `mw-http-call` URL rebuilt from parsed parts as
  target base + the URL's own path and held to the strict parameter rule (no `% ? & #`, placeholder, user
  info, query, fragment, `..`). Target: the record's `dryRun.baseUrl` (a sandbox; `PartnerConfig.problems`
  checks its shape as a security rule, the loader its host against `allowedHosts`), else
  `mw.partners.dry-run.base-url` with `{partnerId}` (dev: `http://mw-simulators:8080/sim/partners/{partnerId}`,
  mw-simulators' stub; its host joins the candidate's hosts; empty in production), neither: REJECTED.
  `dryRun.operation` (PAY, INQUIRY, STATUS_QUERY) is sent with `dryRun.sample` as `PartnerCall.input`,
  reference `DRYRUN-<version>`, through `PartnerGateway.dryRun` (no §21.2 metrics; audited); SUCCESS or
  PENDING pass. Pass: candidate removed, the version swapped in (unless a newer one went live meanwhile);
  fail: both removed, the live version served throughout. Every record of version >= 1 ends in a
  `PartnerRouteStatus` (LIVE with its Kamelets, or REJECTED with the loader's or the dry-run's problems)
  for `messaging/PartnerRouteStatusPublisher`: `mw.partner.route.status.v1` (own contract, key partnerId,
  `dto/PartnerRouteStatusEvent`) through the outbox in its own transaction (`PartnerRouteDryRunIT`).
  A record without `dryRun` (seeds; ENABLE / DISABLE / SET_TIMEOUTS and rollback records) loads at once.
  Restart gap (closed in ticket 84, D28 amendment): on a REJECTED report for the current version
  mw-admin-api republishes the last LIVE version as the next one (`system/rollback`), so the latest
  record replayed after a restart is a good one. `PartnerRouteDryRunIT` is shared by the three adapters
  (it reads this adapter's family).
- **Circuit breaker per partner**: the EIP's own breaker is per processor node and dies with its route,
  so `mw-http-call` references `mw-cb-<id>`, the resilience4j instance named by the partner id, bound in
  Camel's registry by `PartnerRoutes`; its state survives a swap. Time limiter and bulkhead are per call
  site (Camel).
- **Hosts** (guardrail 4): the record's `allowedHosts`; `mw.partners.security.allowed-hosts.<id>`, when
  set, is a floor the record can only narrow; https unless `insecure-http-hosts`; a host that resolves to
  loopback, private, shared, link-local (cloud metadata), unspecified or multicast addresses is refused
  unless it is a static dev host (`insecure-http-hosts` or the floor), at load, again in the guard before
  the send, and on the very addresses connected: the `http`/`https` components are
  `partner/route/PartnerHttpComponent` with `PartnerDnsResolver` on their connection manager (resolve
  once, check every address, return only checked ones; DNS rebinding). IPv6: only global unicast
  `2000::/3` outside 6to4 and Teredo; IPv4-mapped addresses are checked as IPv4. The guard also refuses
  any http endpoint option but the fixed set `mw-http-call` adds (`PartnerHostGuard.OPTIONS`). Checked at load and before
  every send by one context-wide `interceptSendToEndpoint("http*")` declared once on an anchor route
  (`partner/route/PartnerHostGuard`; declared per route it would pile up callbacks), which also refuses a
  `CamelHttpUri` override; a refusal is NOT_SENT `MISCONFIGURED` (`security.partner_target_refused`).
- **Headers**: `mw-http-call` sends with `headerFilterStrategy=#mwPartnerHeaderFilter`
  (`PartnerHeaderFilterStrategy`): no `mw*` or `Camel*` header leaves, and a response cannot set one.
  `CamelHttpUri`, `CamelHttpQuery`, `CamelHttpPath` are reset by `mw-http-call` only; `mw-soap-call` has
  its own filter (`mwSoapHeaderFilter`, above).
- **Secrets**: `{{hashicorp:secret:mw-adapter-billpay/<id>#<field>}}`, Camel's HashiCorp Vault
  properties function against OpenBao KV v2 `secret/mw-adapter-billpay/<id>`, read when a route loads,
  never refreshed (D28), never logged, never in a URI or audited header (`partner/auth/PartnerCredentials`;
  the path is built from the partner's own id). The function only knows a static token, so it runs on
  Spring Cloud Vault's `VaultTemplate` (AppRole) when `VAULT_ENABLED=true`
  (`partner/auth/SpringVaultPropertiesFunction`; Camel 4.22 rebuilds its client in `doStart`, which this
  skips). Tests use a fake `hashicorp` function (`src/test/.../partner/FakeVaultFunction`, found first
  through `META-INF/services`; `fake-<partner>-<field>-for-tests` unless a test sets one) and `NewBillerRouteIT` the real one against OpenBao in Testcontainers.
- **Aliases** (D28, user decision 2026-11-18): `mw.partners.partner-aliases` replaces `default-partner`.
  The channels and seeds send the catalogue's `partnerId` (`billagg`) itself, so the map is empty; any
  other id is refused `MW-PTR-UNKNOWN-PARTNER` (502 on the bill fetch). The reply contract's partner
  codes have two segments, hence not `MW-PTR-UNKNOWN`.
- **REVERSE, health, payload store** (ticket 97, FRS CON-01/04/05): see the section "REVERSE, health and
  the payload store" of `docs/concepts/camel-partner-routes.md`. REVERSE: a route branch on
  `mwOperation == 'REVERSE'` (`mw-map` -> `mw-http-call`/`mw-soap-call` -> `mw-classify`); `{reference}` is
  our original reference, `PartnerGateway.withReversalReference` puts `reversalReference` =
  `reverse:<txnId>` into the mapping input (header `mwReversalReference`); `PartnerExchange.sentOnce`: a
  PAY, a REVERSE (anything but INQUIRY / STATUS_QUERY / HEALTH_CHECK) is never sent again by
  `mwHttp.fallback`, and the HTTP client has automatic retries off (`PartnerReverseRouteIT`: a 503 is one
  request, UNKNOWN `HTTP_5XX`). The request topic accepts `REVERSE` (`mapper/PartnerCallMapper`: partner
  and a positive amount; never refused for `deadlineAt`, the saga resends it, ticket 96). Health:
  `PartnerRouteHealth.State` UP / DEGRADED (breaker half-open or failure rate >=
  `mw.partners.health.degraded-failure-rate`, 25) / DOWN (route stopped, breaker open, probe failing) /
  MAINTENANCE (record `maintenance: true`) / DISABLED / DELETED; record `healthCheck: true` = a
  `HEALTH_CHECK` branch probed by `partner/route/PartnerHealthMonitor` from `scheduler/PartnerHealthJob`
  (ShedLock `partner-health`, `mw.partners.health.interval` 30 s, `mw.partners.health.enabled`, off in
  tests), which publishes every change once through `PartnerRouteStatusPublisher` (LIVE + `health`, seed
  version 0, idempotency key `partner/<id>@<v>:health:<state>:<ms>`; `PartnerRouteHealthTest`). Payload
  store: `PartnerAudit` with `PayloadStore` = `repository/PartnerPayloadRepository`, table
  `partner_payload(payload_ref, txn_id, partner_id, op, direction, body, latency_ms, at)` (Flyway V6), full
  masked bodies, purged after `mw.retention.partner-payload` (30 d, `service/PartnerPayloadRetentionService`,
  `scheduler/PartnerPayloadPurgeJob` on `mw.retention.cron`; `PartnerPayloadIT`). The dev seeds (here and
  in mw-admin-api `partner-seeds/dev`) reverse at `POST <path>/{reference}/reverse` of mw-simulators.
  `scripts/test/test_partner_engine_copies.sh` (in `:scriptTests`) fails when the three `partner/` trees,
  Kamelets or `partner/` resources differ apart from the package.
- **Health and metrics**: `partners` on `/actuator/health` (`PartnerRouteHealth`: per partner version, route,
  `circuit` and status UP, DEGRADED, DOWN, MAINTENANCE, DISABLED, DELETED (ticket 97); the indicator stays UP and is not in the readiness group), Camel's own checks skip
  `partner-*` and `mw-*` routes (`camel.health.exclude-pattern`; Camel's readiness would otherwise turn the
  whole service not ready). Camel route metrics get a `partnerId` tag (`PartnerFrameworkConfiguration`
  `MeterFilter`), `camel.metrics.uri-tag-enabled` off; the §21.2 metrics (`mw.partner.call`,
  `mw.partner.call.duration`, `mw.partner.circuit.state`) stay in the gateway.
- **Callbacks** stay Java (`PartnerGateway.inbound`): the record's `auth` scheme with its secret, then
  its `CALLBACK.response` mapping.
- **Batch partners over SFTP** (ticket 86; `partner/route/PartnerBatchRoutes`, `PartnerRecon`,
  `partner/kamelet/MwReconStep`): a second route shape, `from: kamelet:mw-sftp-source?...` (a record of
  its own, e.g. `billagg-recon`), whose last step is `kamelet:mw-recon`; `PartnerRuntime.batch()`. The
  loader holds `mw-sftp-source` / `mw-sftp-sink` / `mw-recon` to short parameter lists, each value to a
  pattern (`host` (in `allowedHosts`, internal addresses only for static dev hosts:
  `PartnerEndpointPolicy.hostProblems`), `port`, `username`, `directory` (relative, no `..`), `auth`
  `password|key`, `secretField`, `knownHostsField`; source `delay` (100 ms or more) or `cron` (a Spring
  cron of six fields, `+` for a space; the loader adds `scheduler=spring`, never both), `maxMessagesPerPoll`
  (1-100); sink `fileName` (`[A-Za-z0-9._-]` and `${date:now:<pattern>}`) and `mapping` (a JSONata mapping
  of the record whose string result is the file text, CSV or fixed-width: request routes only,
  `partner/kamelet/MwSftpSinkStep`; there the put is the partner call, `mw-classify` sees 200 and
  `{"fileName"}`; `bindy` is not offered: it needs a Java model class per layout); recon `columns`, `delimiter`
  `comma|semicolon|pipe|tab`); any other SFTP option (`delete`, `strictHostKeyChecking`,
  `privateKeyFile`, `scheduler`, ...) is a security refusal. Values are read URL-decoded (`QueryParams`, shared with
  the host guard), as Camel reads them. It rebuilds the call with `partnerId` and the registry
  references `password=` / `privateKey=#bean:mw-sftp-<routeId>-<field>` and
  `knownHosts=#bean:mw-sftp-<routeId>-<field>`: the credential and the host's known_hosts lines are fields
  of the partner's own OpenBao secret, read at load, bound by `PartnerRoutes` before the route starts
  (`Loaded.beans()`) and unbound with it; never in a URI. A batch route has no `routeConfiguration` (a
  failure must reach the file consumer) and no dry-run (refused). `enabled: false`: the route is not
  added, so nothing polls (health DISABLED). `mw-sftp-source` (camel-sftp 4.22.2, options checked in
  its `SftpEndpointConfigurer`): `strictHostKeyChecking=yes`, `useUserKnownHostsFile=false`,
  `autoCreateKnownHostsFile=false` (the component's default is `no`), `readLock=changed`, `idempotent`
  on bean `mwSftpFiles` (camel-sql `JdbcMessageIdRepository`, table `partner_file_processed`, Flyway
  `V5`, key `<partnerId>/${file:name}-${file:size}-${file:modified}`, shared by two versions polling
  during a swap), `move=.done`, `moveFailed=.error` (never `delete`), `streamDownload=true` with
  `stepwise=false` (not the ticket's `stepwise=true`: with a stream download camel-sftp then sends `cd`
  on the channel the open download uses; the Kamelet comment and ADR D28 say why), `disconnect=true`,
  bounded `maxMessagesPerPoll`. `mw-recon` reads the stream line by
  line (header line or `columns`, quoted fields) and hands the lines to `messaging/PartnerReconPublisher`:
  `mw.partner.recon.v1` (key partnerId, idempotency key `recon/<id>/<fileId>#<line>`, `last` on the
  final line) through the outbox, every line of a file in one transaction, so a bad line (wrong field
  count, over 8192 characters, more data lines than `mw.partners.recon.max-lines`, env
  `MW_PARTNER_RECON_MAX_LINES`, default 1000000) publishes nothing and the file goes to `.error`
  (`partner.recon.file_refused` logs the reason, never content); `split().streaming()` is
  not used because it would publish the lines before the bad one. Choices that differ from the ticket
  text: the known hosts come from OpenBao (`knownHosts` bytes), not a `knownHostsUri` file of the
  adapter host; the sftp host is checked at load only (the `http*` interceptor does not see sftp). Tests:
  `PartnerBatchRouteLoaderTest`, `MwReconStepTest`, `KameletCatalogueTest`, `SftpReconRouteIT` (atmoz/sftp
  Testcontainer: read once, `.done`, `.error`, disabled, the sink, an unknown host key refused). Example:
  `deploy/partner-routes/examples/sftp-recon.yaml`; dev SFTP host `partner-sftp` (profile tester).

- **Auth options** (ticket 87; `partner/kamelet/MwAuthStep`, `partner/auth/OAuth2ClientCredentials`,
  `DetachedJws`, `PartnerTlsContexts`; tests `AuthKameletsTest` (AdviceWith/MockEndpoint, RFC 7617
  vector, JWS checked by nimbus-jose-jwt), `AuthRouteLoaderTest`, `PartnerAuthKameletsIT` (WireMock),
  `NewBillerRouteIT` (OAuth2 biller by config, OpenBao)). `mw-http-call` passes every auth parameter to
  `mw-auth-<auth>` (Camel ignores the ones a Kamelet does not declare) and calls it inside its loop, before
  each attempt. `basic`: `Authorization: Basic` from two secret fields. `oauth2-cc`: a client-credentials
  token from `tokenUrl` (POST, client authenticated with HTTP Basic, `scope`/`audience` when set) sent with
  the shared `ProducerTemplate` through the partner http component, so the host guard holds `tokenUrl` to
  `allowedHosts` (the loader checks it at load too); cached per (partner, tokenUrl, clientId, scope,
  audience) until `expires_in` minus `mw.partners.oauth2.expiry-skew` (30 s; none given: 60 s), one
  request at a time per key; a partner 401 marks the token's generation rejected and `MwHttpStep.response`
  sends once more (any operation, a PAY too: a 401 is a refusal, nothing was applied; ADR D28 amends 82's
  "no redelivery of a PAY" for this one resend; it does not count against `inquiryRetries`); a
  second 401 is classified. Camel's `oauth2*` http options are not used: the client secret would be an
  endpoint option (in the URI) and a refused token is not dropped. `jws`: detached JWS (RFC 7515 F) over
  the request body, RS256 or ES256 (JOSE R||S), PKCS#8 PEM key in a secret field, `kid` = `keyId`, header
  `authHeader` (default `X-JWS-Signature`); `services/mw-crypto` has no source on this branch, so no key
  purpose there (user decision): the key is an OpenBao field (fake in dev only). mTLS: `sslContextRef`
  names a context of static config `mw.partners.tls.contexts.<name>.partner-id` (`config/PartnerTlsProperties`),
  read once when Camel starts (before the routes) from that partner's secret fields `<name>-keystore`
  (base64 PKCS#12), `<name>-keystore-password`, with `truststore: true` `<name>-truststore`/`-password` (a failed read leaves the context unregistered, never the JVM trust store), and bound as the
  `SSLContextParameters` bean `mwTls-<name>`; the loader and the host guard allow only the owning
  partner and https (guard option `sslContextParameters=#mwTls-<name>`). The send with a context is a
  `toD` (resolved only when used, so a partner without one needs no bean) with `cacheSize: 1` and
  `allowOptimisedComponents: false` (the http toD optimisation would send the endpoint options as query
  parameters). A wrong key store fails the handshake: UNKNOWN, never a success. `MaskingPolicy.scrub`
  masks `Bearer`/`Basic` values and `access_token`/`refresh_token`/`id_token`/`client_secret` fields;
  `scripts/log-scan.sh` reports them as `credential`.

Choices where Camel 4.22.2 differs from the ticket text (verified in the 4.22.2 jars, Context7 not
available): `camel-jsonata` is a component that recompiles per exchange, so mappings are compiled
once by `CompiledMapping` inside the Kamelet beans and `camel-jsonata-starter` is not used;
`camel-observation` is deprecated, so `camel-micrometer-observability-starter` (its
`trace-processors` / `disable-core-processors` must be set, the starter has no defaults); Kamelet
`dataTypes` are not declared (an `out` type without a registered transformer fails at runtime), the
types are in each description; the YAML `kamelet:` EIP form is refused, only `to: "kamelet:..."`;
`camel-json-validator` brings networknt json-schema-validator 2.0.1 (Jackson 2), so the contract tests
use it too through `contracts/JsonSchema` (one artifact, one version). The outbound `traceparent` is
not asserted yet (open item).

The aggregator is the seed `billagg` in `application-dev.yml` (profile `dev`; the packaged
`application.yml` seeds no partner): a YAML route with PAY (`POST /billagg/v1/payments`), STATUS_QUERY
(`GET /billagg/v1/payments/{reference}`), INQUIRY (`POST /billagg/v1/bills/fetch`), hmac-sha256 both
ways (field `hmac-secret` of `secret/mw-adapter-billpay/billagg`), base URL `BILLAGG_BASE_URL`, hosts
`MW_BILLAGG_ALLOWED_HOSTS`, timeout 25 s (below the orchestrator's 30 s deadline), resilience4j instance
`billagg` (bulkhead 100, 20 s slow-call threshold). Error map `3001` BILL_NOT_FOUND, `3002`
BILL_ALREADY_PAID, `3003` PAYMENT_REFUSED, `3004` UNKNOWN_BILLER; `9876` ambiguous. One aggregator, two
protocols: PAY and INQUIRY of the SOAP billers (`${header.mwPartnerCode} in 'KGDCL,JGTDSL,BTCL,LINK3'`, the
first two `when`s) go `mw-map` (`PAY.soap.request` / `INQUIRY.soap.request`) -> `mw-soap-call` (address
`${mw.partners.instances.billagg.base-url}/billagg/v1/soap`, Spring-resolved before the loader sees it, as
the REST `url`s; SOAP 1.1, namespace `urn:billagg:v1`, `FetchBill` / `PayBill` with `billerCode`,
stylesheets `soap.request.xsl` / `soap.response.xsl`, UsernameToken user `bits`, password field
`ws-password` of the same secret, dev value `BILLAGG_WS_PASSWORD`) -> `mw-classify` (`PAY.soap.response`,
`INQUIRY.soap.response`, `definiteStatuses=200`, PAY `pending=true`); a `soap:Client` fault with a code is
FAILED with that code (3001-3004 through the same error map, 1002 auth failure and others `UNMAPPED`), a
`soap:Server` fault or any other reply UNKNOWN; INQUIRY SUCCESS has the REST attributes (`amount`,
`dueDate`, `billPeriod`). STATUS_QUERY (only our reference) and callbacks stay REST: the aggregator keeps
one payment store for both front ends. `BillaggSoapRouteIT`. mw-admin-api's
`partner-seeds/dev/billagg.json` holds the same record (base URL written out as `http://mw-simulators:8080`);
keep the two equal. An example record:
`deploy/partner-routes/examples/rest-apikey-biller.yaml`; the concept page: `docs/concepts/camel-partner-routes.md`.

Ticket 23 (bank C2W) copied this chain with the framework into mw-adapter-bank (see its
CLAUDE.md): a CALLBACK operation mapping that returns our `reference`, an
async-callback instance with an inbound-capable auth scheme, a callback controller + service
like `PartnerCallbackService` (own-config check, reference lookup in the adapter's own table,
inbox + synchronous publish, 401/404/400/503 answers), `PartnerInboundMapper`'s `inboundId`
rule, one APISIX route per partner (`/partner/<id>/callback/*`, upstream the owning adapter,
ip-restriction, client-control, limit-count on `remote_addr`), the simulator's signed callbacks
(`sim.callback-secrets.<partner>`, `CallbackSigner`) and replay endpoint. The orchestrator's
`PartnerInboundService` handles kind CALLBACK only; C2W confirmations are CALLBACKs of the
customer's own request (ticket 23), so REMITTANCE / NPSB_CREDIT (new inbound money with an
adapter-minted txnId) are still dropped and belong to the REMIT_IN / NPSB tickets.

## Callback replay protection (ticket 99, FRS PLT-008 / PLT-014)

A callback signed with `hmac-sha256` must carry `X-Nonce` (16-128 of `A-Z a-z 0-9 . _ -`), signed as its own
line between the timestamp and the body hash (`partner/auth/HmacSha256AuthScheme.sign(..., nonce, body)`;
an outbound request carries none and keeps the four-line string), and a timestamp within `max-skew-seconds`.
After the signature verifies, `PartnerGateway.inbound` lets the nonce through once per partner for twice
the skew plus 30 s (`partner/auth/CallbackNonces`, table `callback_nonce`, Flyway V7; PostgreSQL, the
adapters have no Valkey/Redis); a reused nonce is 401 `MW-AUTH-001` like a bad signature. A partner's resend
takes a fresh nonce and is then deduplicated by reference as before (mw-simulators' `CallbackSigner` signs a
fresh UUID per send). Identical in the three adapters (telco has no callback endpoint yet). Tests
`AuthSchemesTest`, `controller/CallbackReplayIT`.

## Contracts owned

- `api/mw-adapter-billpay/billpay.v1.openapi.yaml` (internal API; clients mw-app-gateway and mw-ussd;
  `GET /internal/v1/partners/kamelet-usage`, ticket 83).
- `events/mw-adapter-billpay/mw.partner.route.status.v1` (ticket 83): LIVE / REJECTED per partner record
  version, key partnerId; since ticket 97 also health reports (`health`, version 0 for a seed); consumer mw-admin-api; telco and bank produce it too since ticket 84 (topic 6
  partitions, 7 d, retry group mw-admin-api).
- `events/mw-adapter-billpay/mw.partner.recon.v1` (ticket 86): one line of a partner's SFTP
  reconciliation file (`messaging/PartnerReconPublisher`, `dto/PartnerReconEvent`), key partnerId; telco
  and bank produce it too; consumer the future mw-recon (ticket 88; `contracts/EXTERNAL.yml` until then;
  topic 6 partitions, 7 d, no retry group yet).

## Contracts used

- Machine-readable list: `contracts.yml` (checked by `./gradlew :contractsLint`, tested by
  `contracts/ProducedContractsTest` / `ConsumedContractsTest`). `ApiContractsTest` validates the `billpay.v1` responses (catalogue, bill, error envelope) built by `service/BillFetchService`.
- `events/mw-audit-dw/mw.audit.event.v1` out (`partner.call.completed`, `messaging/PartnerAuditPublisher`);
  own type `dto/AuditEvent`.
- `events/mw-orchestrator/mw.partner.request.v1` (family `billpay`, shared payload) in; own type
  `dto/PartnerRequest`. Canonical BILL_PAY: `partner {partnerId: <aggregator>, productCode: BILL,
  billerCode}`, credit party `{type: BILLER_ACCOUNT, id: <bill number>}`.
- `events/mw-orchestrator/mw.partner.reply.v1` (family `billpay`) out; own type `dto/PartnerReply`.
  `dto/PartnerMessagesSchemaTest` keeps both aligned with the billpay samples.
- `events/mw-orchestrator/mw.partner.inbound.v1` (family `billpay`, kind `CALLBACK`) out; own type
  `dto/PartnerInbound`, checked by `mapper/PartnerInboundMapperTest` and
  `controller/PartnerCallbackIT`.
- `events/mw-admin-api/mw.config.changed.v1` in (PARTNER records), as telco.

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (written inline in
  `build.gradle.kts`; drop once the Boot / Camel BOMs ship them); the shared dev provisioning image `deploy/provision/Dockerfile` runs `apk upgrade` and removes gosu. `scripts/security.sh <this module>` scans the SBOM and the
  `mfs-mw/*:local` images (HIGH/CRITICAL fail; accepted ones in `.trivyignore.yaml` with a reason and expiry).
- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field (bill
  numbers as ACCOUNT, last 4 only). Party ids are `PARTY_ID` (ticket 23): masked by the party's
  `type` (wallet/MSISDN as an MSISDN, a bank account or any other type last 4 only).
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
- `config/MwServiceProperties`: config binding; secrets from OpenBao when `VAULT_ENABLED=true`.
- Spring Kafka (ADR D27, copied from the template, ticket 78): `config/KafkaConfig` (retry topics
  `<topic>.<group>.retry.1..3` 10 s / 1 min / 10 min then `.dlq`, `messaging/RetryTopicNaming`,
  `RetryTopicRecoverer`, `RetryDueTimeCap`, `ConsumerContextInterceptor`), `messaging/EventPublisher`
  (`KafkaTemplate`), outbox/inbox, `scheduler/OutboxRelayScheduler`. Listeners: `BillpayRequestHandler`
  (`mw.billpay.request-consumer.enabled`) acknowledges after the hand-over;
  `partner/config/PartnerConfigKafkaListener` (`mw.partners.config-consumer.enabled`) has a stable
  per-instance group `<service>-config-<HOSTNAME>`, seeks to the beginning on every assignment and never
  commits (the former Camel route left a random group behind per start); `mw.config.changed.v1` has no
  retry topics here (`mw.kafka.retry.exclude-topics`). Admin and producer timeouts are bounded (5 s,
  delivery 10 s, `mw.kafka.send-timeout` 12 s) so a callback fails fast with Kafka down.
- `application.yml`: Resilience4j defaults (19.2), own schema `billpay_ref` migrated by Flyway.

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-adapter-billpay:test` | every test in one JVM: unit, slice and contract tests (`contracts/ProducedContractsTest`, `ConsumedContractsTest`, `ApiContractsTest`), the Testcontainers `*IT` classes and the `@Conformance` suite; one PostgreSQL and one Kafka per test JVM, a fresh database per Spring context; the `*IT` and conformance classes are skipped without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-adapter-billpay:build` | `test`, then the boot jar `build/libs/mw-adapter-billpay.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-adapter-billpay:bootJar && docker build -t mw-adapter-billpay:dev services/mw-adapter-billpay` | image, as spec 4.3 rule 1 |
| `./gradlew clean build` | the full gate: every module, plus the repo checks (`:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`); unchanged modules come from the build cache |
| `./gradlew :mw-adapter-billpay:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-adapter-billpay` | rebuild this service's image (`mfs-mw/mw-adapter-billpay:local`) and restart it with what it depends on (dev timers from `deploy/.env`) |
