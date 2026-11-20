# API catalogue (FRS PLT-050, PLT-052, 10.4 criterion 5)

Every API the middleware exposes, with its contract and where its document is published. The files under
`contracts/api/` are the source of truth (`contracts/README.md`, versioning policy included); springdoc
generates each REST module's document from the code and its `ContractDriftTest` fails on drift. Swagger UI
and `/v3/api-docs` exist only in the Spring profiles `dev` and `test` (`ApiDocsProductionTest`); on the dev
stack the test-only APISIX routes (`MW_E2E_ROUTES=1`, `scripts/tester.sh` or `tests/api/run.sh` apply them)
publish them under `https://localhost/docs/<service>/swagger-ui/index.html`, listed on the page
`https://localhost/docs/` (`deploy/apisix/docs/index.html`). Production exposes neither.

`./gradlew :scriptTests` (`scripts/test/test_connector_docs.sh`) fails when a file under `contracts/api/` or a
WSDL a service publishes is missing here.

Audience: **edge** = through APISIX for apps, partners or staff; **internal** = service to service on the
internal network (mTLS where the module says so), never at the edge; **test** = test doubles and test-only
endpoints, off in production.

## REST (OpenAPI 3.1)

| Service | Contract | Audience | Paths | Swagger UI (dev) |
| --- | --- | --- | --- | --- |
| mw-app-gateway | `contracts/api/mw-app-gateway/app.v1.openapi.yaml` | edge (the app) | `/app/v1/**` | `/docs/mw-app-gateway/swagger-ui/index.html` |
| mw-auth | `contracts/api/mw-auth/auth.v1.openapi.yaml` | edge (the app) | `/auth/v1/**` | `/docs/mw-auth/swagger-ui/index.html` |
| mw-auth | `contracts/api/mw-auth/auth-internal.v1.openapi.yaml` | internal (mTLS) | `/internal/v1/**` | `/docs/mw-auth/swagger-ui/index.html` |
| mw-auth | `contracts/api/mw-auth/auth-test.v1.openapi.yaml` | test | `/test/v1/**` | `/docs/mw-auth/swagger-ui/index.html` |
| mw-checkout | `contracts/api/mw-checkout/checkout.v1.openapi.yaml` | edge (merchants, shoppers) | `/checkout/v1/**` | `/docs/mw-checkout/swagger-ui/index.html` |
| mw-portal-api | `contracts/api/mw-portal-api/portal-org.v1.openapi.yaml` | edge (org portal) | `/portal/org/v1/**` | `/docs/mw-portal-api/swagger-ui/index.html` |
| mw-ussd | `contracts/api/mw-ussd/ussd-http.v1.openapi.yaml` | edge (MNO USSD gateways over HTTP) | `/ussd/{mno}` | `/docs/mw-ussd/swagger-ui/index.html` |
| mw-admin-api | `contracts/api/mw-admin-api/admin.v1.openapi.yaml` | edge (ops console) | `/admin/v1/**` | `/docs/mw-admin-api/swagger-ui/index.html` |
| mw-adapter-remit | `contracts/api/mw-adapter-remit/remit-inbound.v1.openapi.yaml` | edge (UCB IMT, inbound) | `/remit/v1/**` | none (no springdoc in the module) |
| mw-adapter-billpay | `contracts/api/mw-adapter-billpay/billpay.v1.openapi.yaml` | internal | `/billpay/v1/**` | none (no springdoc in the module) |
| mw-adapter-telco | `contracts/api/mw-adapter-telco/telco.v1.openapi.yaml` | internal | `/telco/v1/**` | none (no springdoc in the module) |
| mw-adapter-bank | `contracts/api/mw-adapter-bank/bank.v1.openapi.yaml` | internal | `/bank/v1/**` | none (no springdoc in the module) |
| mw-orchestrator | `contracts/api/mw-orchestrator/txn-query.v1.openapi.yaml` | internal | `/internal/v1/**` | `/docs/mw-orchestrator/swagger-ui/index.html` |
| mw-orchestrator | `contracts/api/mw-orchestrator/orchestrator-test.v1.openapi.yaml` | test | `/test/v1/**` | `/docs/mw-orchestrator/swagger-ui/index.html` |
| mw-audit-dw | `contracts/api/mw-audit-dw/audit-query.v1.openapi.yaml` | internal | `/internal/v1/audit/**` | `/docs/mw-audit-dw/swagger-ui/index.html` |
| mw-audit-dw | `contracts/api/mw-audit-dw/audit-dw-test.v1.openapi.yaml` | test | `/test/v1/audit/**` | `/docs/mw-audit-dw/swagger-ui/index.html` |
| mw-notify | `contracts/api/mw-notify/notify-internal.v1.openapi.yaml` | internal | `/internal/v1/inbox/**` | none (no springdoc in the module) |
| mw-crypto | `contracts/api/mw-crypto/crypto-internal.v1.openapi.yaml` | internal (mTLS) | `/internal/v1/**` | service port only (no APISIX docs route) |
| mw-crypto | `contracts/api/mw-crypto/crypto-test.v1.openapi.yaml` | test | `/test/v1/payshield/**` | service port only (no APISIX docs route) |
| mw-compliance | `contracts/api/mw-compliance/compliance.v1.openapi.yaml` | internal | `/internal/v1/**` | service port only (no APISIX docs route) |
| mw-data-export | `contracts/api/mw-data-export/data-export.v1.openapi.yaml` | internal | `/internal/v1/**` | `/docs/mw-data-export/swagger-ui/index.html` |
| mw-data-export | `contracts/api/mw-data-export/data-export-test.v1.openapi.yaml` | test | `/test/v1/**` | `/docs/mw-data-export/swagger-ui/index.html` |
| mw-recon | `contracts/api/mw-recon/recon.v1.openapi.yaml` | internal | `/internal/v1/recon/**` | `/docs/mw-recon/swagger-ui/index.html` |
| mw-recon | `contracts/api/mw-recon/recon-test.v1.openapi.yaml` | test | `/test/v1/**` | `/docs/mw-recon/swagger-ui/index.html` |
| mw-core-mock | `contracts/api/mw-core-mock/core-wallet.v1.openapi.yaml` | internal (the core wallet, ADR D31) | `/core/v1/**` | `/docs/mw-core-mock/swagger-ui/index.html` |
| mw-core-mock | `contracts/api/mw-core-mock/core-mock-control.v1.openapi.yaml` | test | `/mock-control/**` | `/docs/mw-core-mock/swagger-ui/index.html` |
| mw-core-mock | `contracts/api/mw-core-mock/core-mock-seed.v1.openapi.yaml` | test | `/core-mock/v1/**` | `/docs/mw-core-mock/swagger-ui/index.html` |
| mw-simulators | `contracts/api/mw-simulators/mock-control.v1.openapi.yaml` | test | `/mock-control/**` | `/docs/mw-simulators/swagger-ui/index.html` |

## SOAP (WSDL)

The WSDLs are generated at run time from the contract XSDs (Spring Web Services `DefaultWsdl11Definition`).

| Service | Contract | Audience | WSDL |
| --- | --- | --- | --- |
| mw-app-gateway | `contracts/api/mw-app-gateway/app-soap.v1.xsd` (namespace `urn:mfs:mw:app:soap:v1`) | edge (app SOAP facade, ticket 108) | `/app/ws/v1/app.wsdl` |
| mw-adapter-atm | `contracts/api/mw-adapter-atm/atm-soap.v1.xsd` (namespace `urn:bits:mw:atm:v1`) | edge (UCB ATM switch, inbound, ticket 120a) | `/partner/ucbatm/ws/v1/atm.wsdl` |

Outbound SOAP (the middleware is the client, the WSDL is the partner's): the merchant system
(`services/mw-adapter-merchant`, namespace `urn:merchant:v1`), the SOAP bank profiles of the bank gateway
(`services/mw-adapter-bank`, namespace `urn:bank:<code>:v1`), aggregator 3 and the billagg SOAP billers
(`services/mw-adapter-billpay`). Their wire is fixed by the partner record's SOAP mapping (XSLT) and played by
mw-simulators (`docs/mocks/README.md`).

## Events and files

Kafka topics are AsyncAPI and JSON Schema under `contracts/events/` (owners in `contracts/OWNERS.yml`); partner
files (AML, MT940/942, DW batches) under `contracts/files/`. They are not endpoints, so they are not listed here.
