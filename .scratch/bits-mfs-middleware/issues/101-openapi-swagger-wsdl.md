# 101: Generated OpenAPI, Swagger UI and WSDL listing in non-prod, versioning policy (FRS 3.6)

**What to build:** No module has springdoc today; the contracts in `contracts/api/*` are hand-written and nothing proves
the code still matches them. Add generated docs for non-production and a drift test against the contract files, keeping
`contracts/` as the source of truth (contract first, root CLAUDE.md).

**Scope:**
- Every REST service (`mw-app-gateway`, `mw-checkout`, `mw-portal-api`, `mw-ussd` HTTP, `mw-orchestrator` query,
  `mw-audit-dw`, `mw-admin-api`, `mw-auth`, `mw-crypto`, `mw-core-mock` (after 93), `mw-simulators`): springdoc-openapi
  (version inline, same in each module), OpenAPI 3.1 output, `/v3/api-docs` and Swagger UI enabled only in profile
  `dev`/`test` (property `springdoc.api-docs.enabled=false` by default, so production builds expose nothing).
- `ContractDriftTest` per module (template first, then copied): starts the web slice, fetches the generated document and
  compares paths, methods, required params and response codes with the module's owned file under `mwContractsDir`;
  a path in code but not in the contract (or the reverse) fails.
- WSDL listing: SOAP endpoints of the adapters' inbound side (and ticket 108's facade) publish `?wsdl`; a dev-only APISIX
  route `/docs/` lists the Swagger UIs and WSDLs (test-only routes, `MW_E2E_ROUTES=1`).
- `docs/adr/` amendment or a short policy in `contracts/README` (create if absent): URI version `/v1`, breaking change →
  `/v2`, old version kept for one release (PLT-051).
- Template: `templates/mw-service-template` gets the dependency, config and test so new services (tickets 115+) inherit it.

**FRS IDs covered:** PLT-050, PLT-051, PLT-052; acceptance 10.4 #5 (OpenAPI part)

**Blocked by:** 89

**Status:** open

- [ ] `ContractDriftTest` green in every REST module; a planted extra endpoint makes it fail (shown once in the PR)
- [ ] Prod profile test: `/v3/api-docs` and `/swagger-ui` return 404
- [ ] `./gradlew build :contractsLint :checkIsolation :scriptTests` green with Docker (every touched module; one plugin version everywhere)
- [ ] With the stack up: `scripts/smoke.sh` check that `/docs/` lists every service; new API folder `38-api-docs`
