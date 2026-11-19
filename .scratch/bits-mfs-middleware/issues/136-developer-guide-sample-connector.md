# 136: connector developer guide, sample connector and per-mock trigger docs (FRS CON-06, 10.4 criteria 4 and 5)

**What to build:** Deliverables that let the Upay team add a partner without the vendor:
- a developer guide;
- a runnable sample connector;
- a documented, tested walkthrough that adds a new REST biller **from the console alone**.

Also publish the trigger rules of every mock (MCK-03) and make sure OpenAPI/WSDL is listed for every exposed endpoint
(FRS 10.4 criterion 5).

**Principles:**
- The guide matches the code as built. `scripts/test/test_repo_docs.sh` fails when the guide names a path, script,
  Kamelet or `./gradlew` task that does not exist.
- A new REST biller of an existing category needs **no code and no restart**. It needs only:
  - a route record built from the shipped `mw-*` Kamelets;
  - a JSONata mapping;
  - an error map;
  - a credential;
  - a routing rule;
  - a biller catalogue entry.
  All of these go through maker-checker and dry run (tickets 83, 113, 131, 132).

**Scope:**
- `docs/connectors/developer-guide.md` (or `.html`, matching `docs/tutorial/`) covers:
  - the partner engine layers;
  - the Kamelet catalogue (`mw-map`, `mw-http-call`, `mw-soap-call`, the auth steps, `mw-classify`, `mw-audit`,
    `mw-sftp-*`);
  - operations PAY / INQUIRY / STATUS_QUERY / REVERSE / health;
  - the response-code mapping, resilience policy fields, dry run and rollback;
  - switching between mock and real;
  - adding a new partner *category* (code change: a new `mw-adapter-*` from the template, partner engine copied).
- Sample connector `deploy/partner-routes/examples/sample-rest-biller.yaml` plus mapping and error map, and a matching
  simulator profile `samplebiller` in mw-simulators on the unified mock-control engine.
- `docs/mocks/README.md`: one section per mock party (all of EXT-01..18). Each lists its data triggers (amount
  endings, MSISDN/account endings), the scenarios it supports, the party switches, and its journal path. This is
  generated from or checked against each simulator's trigger registry by a unit test, so it can't go stale.
- `docs/api-catalogue.md`: every exposed REST API (OpenAPI under `contracts/api/`, Swagger UI path in non-prod from
  ticket 101) and every SOAP WSDL (app facade, merchant, ATM, bank profiles).
- Playwright walkthrough of the "add a REST biller" flow.

**FRS IDs covered:** CON-06, CON-02, MCK-03 (documentation), PLT-050/052 (catalogue), 10.4 criteria 4 and 5

**Blocked by:** 114, 131

**Status:** open

- [ ] `MockTriggerDocsTest` (mw-simulators) first: every trigger in each simulator's registry appears in `docs/mocks/README.md` and the doc lists none that doesn't exist
- [ ] `scripts/test/test_repo_docs.sh` extended to the guide and the catalogue; `./gradlew :scriptTests` green
- [ ] Playwright `e2e/94-add-biller.spec.ts` (`@partners @billers`): as seeded personas, create the `samplebiller` route, mapping, credential, routing rule and catalogue entry; approve each; dry run passes; a bill fetch and pay through the app API then completes against the `samplebiller` simulator, all without a restart
- [ ] Every path under `contracts/api/` and every published WSDL appears in `docs/api-catalogue.md` (script check)
- [ ] `./gradlew :mw-simulators:build :mw-adapter-billpay:build` green with Docker
- [ ] API suite folder `70-sample-connector` passes (`scripts/api-test.sh 70`)
