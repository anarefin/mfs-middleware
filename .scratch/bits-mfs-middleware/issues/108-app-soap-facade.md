# 108: SOAP/XML facade on mw-app-gateway (FRS CH-APP-01, RFP 5.1)

**What to build:** A SOAP/XML facade for apps or partners that need SOAP, calling the same internal services as the REST
API. Today SOAP exists only outbound in the adapters (`mw-soap-call` Kamelet). Camel is not allowed outside
`mw-adapter-*` (D1 amendment), so the facade uses Spring Web Services.

**Principles:**
- No business logic in the facade: an `@Endpoint` maps XML ↔ the same DTOs and calls the same `service/` classes
  (`PreviewService`, confirm, balance, status) as the REST controllers.
- Same security as REST: JWT bearer in the HTTP header (validated by APISIX `openid-connect` and the app-gateway
  converter), Idempotency-Key as a SOAP header element, same error codes mapped to a SOAP fault with the envelope fields
  in `<detail>`.

**Scope:**
- XSD `contracts/api/mw-app-gateway/app-soap.v1.xsd` (owner mw-app-gateway; `contracts/OWNERS.yml`); operations:
  Preview, Confirm (send-money, cash-out, payment, bill-pay, top-up groups), GetBalance, GetTransactionStatus.
  WSDL generated from the XSD (`DefaultWsdl11Definition`) at `/app/ws/v1/app.wsdl`.
- mw-app-gateway: `controller/` (SOAP `@Endpoint` classes count as controllers: mapping and validation only),
  `config/WebServiceConfig`, XSD validation interceptor (PLT-006), correlation ID from header, masking of MSISDN in
  logged payloads (payload logging stays off).
- APISIX route `app-ws-v1.json` (`/app/ws/v1/*`, same auth and limits as `app-v1`).
- WSDL appears in ticket 101's `/docs/` listing.
- Open question kept in ticket: FRS 10.6 "SOAP for the apps themselves or only partners?" — built for the app token
  first; partner token support follows ticket 100's client registry.

**FRS IDs covered:** CH-APP-01, PLT-050 (WSDL part), PLT-006 (XSD part)

**Blocked by:** 105

**Status:** open

- [ ] `AppSoapEndpointTest` (Spring-WS `MockWebServiceClient`): Preview/Confirm round trip; invalid XML → SOAP fault with
      `MW-VAL-*`; missing Idempotency-Key header → fault
- [ ] `AppSoapIT`: a SOAP confirm and a REST confirm with the same key give one transaction (idempotency shared)
- [ ] `ContractDriftTest` covers the WSDL operations against the XSD
- [ ] `./gradlew :mw-app-gateway:build :contractsLint :checkIsolation` green with Docker (no Camel dependency)
- [ ] With the stack up: SOAP requests added to folder `41-preview-confirm`; `scripts/edge-test.sh` 401 without token on `/app/ws/v1`
