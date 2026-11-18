# 82: Route-as-config engine and Kamelet library in mw-adapter-billpay (REST/JSON)

**What to build:** Replace the Java partner framework in mw-adapter-billpay with Camel-native **partner routes held as config**
(ADR D28).

**Today:**
- `PartnerRouteTemplates` is `direct:partner-{{partner}}` → `.process(java)`.
- The real work is plain Java:
  - `RestJsonAdapter` with `java.net.http.HttpClient`;
  - `JsonataMapping` (dashjoin, re-parsed per call);
  - `PartnerCallWrapper` (Resilience4j by hand).

**After this ticket:**
- Each partner is a Camel YAML DSL route built from shipped Kamelets.
- A new REST/JSON biller needs no Java.

**Check each Camel and Kamelet feature named here against the current Camel 4.x docs (Context7) before use.** If a feature
is missing or works differently, choose the nearest standard Camel mechanism and record the choice in the module's `CLAUDE.md`.

## Kafka side

- **Listeners:** port the adapter's Kafka side to Spring Kafka (template from ticket 78). `BillpayRequestRouteBuilder` becomes a `@KafkaListener` with retry, and
  `PartnerConfigRouteBuilder` becomes a `@KafkaListener`.
- **Config consumer group:** fix it. Today it is a random UUID per start, which leaves orphaned groups. Use a stable
  per-instance id, or `assign` + seek to the beginning with no group.
- **Hand-off to Camel:** then `BillPaymentService` / `BillFetchService` → `PartnerGateway` → `ProducerTemplate.requestBody("direct:partner-<id>")`.
  - Use one shared `ProducerTemplate` bean, never one per call.
  - Camel stays only behind `PartnerGateway`.

## Dependencies

`camel-spring-boot-starter`, `camel-yaml-dsl-starter`, `camel-kamelet-starter`, `camel-http-starter`, `camel-jsonata-starter`,
`camel-resilience4j-starter`, `camel-jackson-starter`, `camel-json-validator-starter`, `camel-hashicorp-vault-starter`,
`camel-observation-starter`, `camel-micrometer-starter`. The `camel-health` support comes with the Spring Boot starter.

Drop `camel-kafka-starter`. Versions come from the camel-spring-boot-bom already pinned in the module.

## The partner route contract (`partner/route/`)

- **Input:** the exchange body is the `PartnerCall` JSON; the headers are `mwOperation` (PAY / INQUIRY / STATUS), `mwReference`, `mwCorrelationId`, `mwPartnerId`.
- **Output:** the body is `{outcome, partnerRef, code, message, reason}`, checked inside `mw-classify` with `json-validator:` against
  a schema on the classpath (Camel-native, not Java in `PartnerGateway`). A missing or invalid output gives `UNKNOWN`, fail-closed as `RestJsonAdapter.classify` does today.
- **Input and output data types:** declare them on the Kamelets if the Camel version supports Kamelet data types.

## Rule: a PAY is never redelivered automatically

A partner call that moves money is not idempotent, and a redelivered PAY can charge twice.

- **No redelivery for PAY:** every partner route runs with no redelivery for PAY. The loader's `routeConfiguration` sets the error handler
  to `noErrorHandler` (or `maximumRedeliveries=0`). `mw-http-call` sets no retry in its resilience4j configuration.
- **Redelivery only for INQUIRY/STATUS:** they may be redelivered, at most the record's `inquiryRetries` (default 1). This is chosen by `mwOperation` inside the Kamelet, never by the partner YAML.
- **Unknown outcome:**
  - **How it ends:** a timeout, an open breaker or a 5xx on PAY gives `UNKNOWN`. The saga resolves it through STATUS, as today.
  - **What it must never do:** retry the PAY.
- **Kafka-level redelivery is safe:** a redelivered `mw.partner.request` record re-sends with the **same** `stable reference`, as today.
  - **Add an inbox check before the partner call:** a request already answered returns the stored reply instead of calling the partner again. Today `inbox.processOnce` only wraps the outbox write (`BillPaymentService.java:80-82`).

## Shared error handling (`routeConfiguration`)

- **One `routeConfiguration`, owned by the loader:**
  - **Applies to:** every `partner-*` route.
  - **Maps exceptions:** a timeout or a `CallNotPermittedException` (breaker open) gives UNKNOWN, a validation error gives FAILED/UNKNOWN as today.
  - **No message history in the body or logs:** `camel.main.message-history=false` in production profiles.
- **The partner YAML cannot change it:** it may not declare `onException`, `errorHandler`, `routeConfiguration` or `intercept*`, and the allowlist refuses them.

## The Kamelet library (`src/main/resources/kamelets/*.kamelet.yaml`)

- **Metadata rules:**
  - each Kamelet has the label `camel.apache.org/kamelet.type: action|source|sink`, a title and a description;
  - each property has a `title`, `description`, `type`, a default where it makes sense, and `required` where needed;
  - secret-reference properties are marked `format: password` with a password `x-descriptors` entry.
- **The Kamelets:**
  - **`mw-http-call`**, an action:
    - **URI:** the endpoint URI is **static per partner** (`{{url}}`, no `${…}`). Path parts and query values are set on the
      `CamelHttpPath` / `CamelHttpQuery` headers from the route, so a bill number, account or amount never appears in an endpoint URI. URIs
      show up in logs, metrics and the endpoint registry. If `toD` is needed, set `cacheSize`.
    - **Breaker and timeout:** `circuitBreaker` with resilience4j, `timeoutEnabled`, and `timeoutDuration` = the record's `timeoutMs`. There is one breaker and bulkhead
      config per partner id; the thresholds come from the record, or the defaults.
    - **Response:** `throwExceptionOnFailure=false` and the body is set to `{status, body}`. A 5xx, a timeout or an open breaker gives UNKNOWN through `onFallback`.
  - **`mw-auth-apikey`, `mw-auth-hmac-sha256`:** ports of the existing `partner/auth` schemes. The wire format is identical, proven by the existing auth tests.
  - **`mw-classify`:**
    - **Mapping:** the response JSONata (a property; camel-jsonata compiles it once per route version), then the record's `errorMap` /
      `ambiguousCodes` / `definiteStatuses`. The fail-closed rules are those of `RestJsonAdapter.classify:253-321`.
    - **Check:** then `json-validator:` on the output.
  - **`mw-audit`:** a masked audit (`sensitiveFields`) through `messaging/PartnerAuditPublisher`.
- **Versioning:** a breaking change to a Kamelet ships as a new name (`mw-http-call-v2`). The old one stays until no LIVE route uses it.
  `GET /internal/v1/partners/kamelet-usage` (or the status event) reports which routes use which Kamelet for the admin console (ticket 83).
- **Kamelet lint test (`KameletCatalogueTest`):** every `mw-*` Kamelet:
  - parses and has the type label;
  - has documented properties;
  - marks secret properties as secrets;
  - has no raw endpoint outside `http`/`https` (85/86 extend the list).

## Secrets (Camel vault function, still static)

- **Where they come from:** Camel's HashiCorp Vault properties function (`camel-hashicorp-vault`), configured with
  `camel.vault.hashicorp.*` (host, port, scheme, token or auth from the existing OpenBao setup) and pointed at OpenBao.
- **How routes name them:** routes and Kamelets use `{{hashicorp:secret:mw-adapter-billpay/<partner>#<field>}}`. A Kamelet takes a `secretRef`, never a value.
  Secrets are read at route start and are not refreshed. Refresh is deliberately off (D28, static secrets).
- **What it replaces:** `EnvironmentCredentialResolver` and the `mw.partner-secrets.*` Spring binding in the partner path.
- **Logging:** a resolved value is never logged and never put in a header that is audited. The masking tests cover the auth headers.
- **In tests:** OpenBao in Testcontainers (dev image as in `deploy/`) with fake values.

## The route loader (`partner/route/PartnerRouteLoader`)

- **Steps, in order:**
  1. **Schema check first:** validate the uploaded YAML against Camel's published YAML DSL JSON Schema (from the
     `camel-yaml-dsl` artifact) for clear errors.
  2. **Parse** it with Camel's `RoutesLoader` into a `RouteDefinition`.
  3. **Walk the model and enforce the allowlist.**
- **Allowed:**
  - exactly one route;
  - `from: direct:partner-<id>` (the id matches the record);
  - `to`: `kamelet:mw-*` from the library only, with a static URI (no `${`);
  - `transform`, `setHeader`, `setBody` with `jsonata`, `constant`, or `simple` without `${bean:`, `${exchange…}`, `${sys` / `${env`;
  - `choice`/`when`/`otherwise`;
  - `log`;
  - `marshal`/`unmarshal json`.
- **Refused:** everything else, e.g. `bean`, `process`, `exec`, `file`, raw `http`/`toD`, `groovy`, `script`, `language`, `class`, `ref`,
  `onException`/`errorHandler`/`intercept*`/`routeConfiguration`, and a `kamelet` not in the library.
- **Required:** the route ends with `mw-classify` then `mw-audit`.
- **Lifecycle:**
  - **Version ids:** each version is loaded as route id `partner-<id>-v<version>`, from `direct:partner-<id>-v<version>`.
    `PartnerGateway` resolves `<id>` to the live version's endpoint from the snapshot.
  - **Swap:** start the new version, flip the snapshot, then stop and remove the old version through the `RouteController`, with a timeout so
    calls in flight finish. Keep one previous version for rollback.
  - **Shutdown:** graceful, `camel.main.shutdown-timeout` = the longest partner timeout + 5 s.
  - **Reload:** `camel.main.routes-reload-enabled` is never on outside local dev.
- **Unit tests** cover each refused construct.

## Host allowlist

- **Where it comes from:** the approved record's `allowedHosts`. The static `mw.partners.security.allowed-hosts` becomes an optional floor (keep
  `insecure-http-hosts` for dev).
- **Enforcement:** `interceptSendToEndpoint("http*")` (85/86 add `cxf*`, `sftp*`) checks the resolved host against the record of the
  calling partner (`mwPartnerId`) and refuses before any network I/O. This replaces `PartnerEndpointPolicy`'s same-origin check.

## Observability and health

- **Tracing:** `camel-observation`, with the W3C `traceparent` propagated on the outbound HTTP call (header name as today).
- **Metrics:** `camel-micrometer` route metrics, tagged with `partnerId`. Keep the existing `partner.call.completed` event and log fields (`partnerId`,
  `durationMs`, `errorCode`).
- **Health:** route and consumer health checks exposed on `/actuator/health` (readiness). A partner route that fails to start makes the
  partner DOWN, not the whole service.
- **Production profiles:** Camel tracing (`camel.main.tracing`) and message history are off.

## Config record and migration

- **New fields:** `PartnerConfig` gains `route` (YAML string), `allowedHosts`, `family`, `inquiryRetries`, `resilience`, `dryRun {operation, sample}`.
  - The old `template`/`operations` fields stay readable for one release so the existing seeds load; drop them in 84.
  - `PartnerConfigStore.apply` calls the loader. A refused record keeps the last good route and logs `security.partner_route_refused`.
- **Migration:** the `billagg` seed in `application-dev.yml` becomes a YAML route.
  - **Parity first:** when `BillpayRequestRouteIT`, `PartnerConfigReloadIT` and the conformance ITs pass on the YAML route, delete `RestJsonAdapter`,
    `PartnerCallWrapper`, `JsonataMapping`, `PartnerRouteTemplates`, `PartnerRouteRegistrar`, `PartnerTemplates`, `EnvironmentCredentialResolver`, the second
    time-limiter executor and the silent `default-partner` fallback (an unknown partner gives `MW-PTR-UNKNOWN` instead).
  - **Ask the user before removing `default-partner`** if any channel relies on it.

## Tests (TDD, write them first)

- **Kamelet unit tests:** each Kamelet is tested alone in `@CamelSpringBootTest`, with `AdviceWith` (`replaceFromWith`, `weaveByToUri` to a
  `MockEndpoint`) and `NotifyBuilder` to wait for completion. No sleeps.
- **Route ITs:** against WireMock: success, a business error, an ambiguous code, a 5xx, a timeout, an open breaker.

## Docs

- `services/mw-adapter-billpay/CLAUDE.md`: the framework, the route contract, the Kamelet catalogue and the versioning rule.
- `docs/concepts/`: a Camel page with the route contract and an example route.
- `deploy/partner-routes/examples/rest-apikey-biller.yaml`.

**Blocked by:** 78

**Status:** open

- [ ] `BillpayRequestRouteIT`, `PartnerConfigReloadIT` and the conformance ITs pass with `billagg` as a YAML route (parity)
- [ ] New `NewBillerRouteIT`: a config-changed record for an unknown partner (new host, different JSON field names, api-key from OpenBao) makes a bill fetch and a pay against a WireMock/simulator stub succeed, **with no restart and no Java change**
- [ ] **A PAY is sent exactly once** when the partner times out, and once when the Kafka request is redelivered (WireMock request count = 1); the outcome is UNKNOWN; INQUIRY is retried as configured
- [ ] A call in flight during a route swap completes on the old version; the next call uses the new one
- [ ] The loader refuses `exec:`, `bean:`, raw `http:` outside a Kamelet, `${…}` in a `to` URI, `onException` in partner YAML, a host not in `allowedHosts`, and a route without `mw-classify`/`mw-audit`; on each refusal the last good route stays live
- [ ] `KameletCatalogueTest` passes; partner routes appear in `/actuator/health` and the metrics; the endpoint registry dump contains no bill number, account or secret
- [ ] No secret value in the logs (`scripts/log-scan.sh` with the stack up); `./gradlew :mw-adapter-billpay:build` is green with Docker
