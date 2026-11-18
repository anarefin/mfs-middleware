# 85: SOAP partners via a `mw-soap-call` Kamelet (camel-cxf)

**What to build:** SOAP billers (common for utilities) onboarded through config only. Ship a `mw-soap-call` Kamelet in all three adapters,
identical. It wraps `camel-cxf-soap` in `dataFormat=PAYLOAD` mode inside the same `circuitBreaker`, timeout and host-allowlist
interceptor as `mw-http-call`.

- **Kamelet properties:** `address`, `wsdlUrl` (optional; it must be on the allowlist or a classpath resource), `serviceName`/`portName`, `soapAction`, `timeoutMs`,
  `soapVersion`.
- **The SOAP body is built in the route:** with XSLT (`xslt-saxon`, stylesheet inline in the record or a resource) or JSONata to XML, and parsed back the same way.
- **The allowlist:** the loader's allowlist (82, and the admin-api copy) gains `xslt` with an inline or resource stylesheet only (no `document()` to remote URIs:
  turn off external entity resolution, XXE-safe parser settings) and `kamelet:mw-soap-call`.
- **Errors:**
  - a SOAP fault maps through `mw-classify` (fault code → `errorMap`);
  - a transport error or timeout → UNKNOWN.
- **Auth:** WS-Security UsernameToken as a property of the Kamelet (password by `secretRef`). Basic/mTLS reuse 87's Kamelets.
- **Rules from 82:**
  - **Kamelet:** `mw-soap-call` follows the Kamelet rules: the `action` type label, documented properties, secret refs marked as secrets, a static `address`
    (operation and SOAP action are properties or headers, not built into the URI), and no redelivery for PAY.
  - **Lint:** `KameletCatalogueTest` allows `cxf` only inside `mw-soap-call`.
  - **Tests:** an AdviceWith/MockEndpoint unit test plus the IT below.
  - **Docs:** check each Camel feature (cxf PAYLOAD mode, xslt-saxon secure processing) against the current Camel 4.x docs (Context7) before use.
- **mw-simulators:** a SOAP stub biller (fetch and pay) with a configurable fault.
- **Example:** `deploy/partner-routes/examples/soap-biller.yaml`.
- **Docs:** add the SOAP case to the onboarding runbook.

**Blocked by:** 84

**Status:** done (0926e161, 0fd772a6)

- [ ] `SoapBillerRouteIT`: a SOAP biller added by a config-changed record does a fetch and a pay against the simulator stub with no Java change; a SOAP fault maps to the configured `MW-PTR-<ID>-*`
- [ ] An XSLT using `document('http://…')` or an external entity is refused by the loader and the admin-api validator
- [ ] The memory limits still pass `./gradlew :composeCheck` (the CXF jar footprint); the three adapters build green
