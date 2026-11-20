# 136 split decisions

Ticket 136 is built in two slices. Slice 136a (branch `ticket/136-developer-guide`) is done; 136b is open.

## 136a (done)

- `docs/connectors/developer-guide.md`, `docs/mocks/README.md`, `docs/api-catalogue.md`; all three in
  `scripts/test/test_repo_docs.sh`'s `op_docs`, plus `scripts/test/test_connector_docs.sh` (README block = the
  registry rendering, one section per EXT-01..18, every `contracts/api/` file and published WSDL in the catalogue,
  only shipped Kamelets in the guide, billpay's copy of the sample identical).
- Sample connector `deploy/partner-routes/examples/sample-rest-biller.yaml` (route, JSONata mappings, error map,
  endpoints MOCK/REAL, resilience) and the `samplebiller` profile in mw-simulators (mock party on the engine,
  seeded bills, health). Tests `SampleConnectorRouteIT` (billpay), `SampleBillerMockIT`, `MockTriggerDocsTest`.

Decisions taken (no user available):

- **Trigger docs vs module isolation.** The ticket wants a mw-simulators unit test to read `docs/mocks/README.md`,
  but a module may read no path outside itself except `contracts/` (`./gradlew :checkIsolation`). So the check is
  a chain: `MockTriggerDocsTest` proves `services/mw-simulators/src/main/resources/mock-triggers.md` is the
  rendering of the registry `MockTriggers` (every party of `MockParties.ALL` in one section), and
  `scripts/test/test_connector_docs.sh` proves the README carries that file between its markers. Both run in the gate.
- **The registry is data, not the code path.** `MockTriggers` lists the triggers the simulators implement; only the
  amount fractions are cross-checked against `MagicAmounts`. Moving each simulator's trigger decision onto the
  registry would touch every mock service; not done.
- **EXT-03 (GreenMail), EXT-07 (EC NID, ticket 124b, not built) and EXT-18 (mw-core-mock)** are hand-written README
  sections below the generated block (not mw-simulators parties). When 124b adds the EC mock as a party,
  `MockTriggerDocsTest` forces its section into the registry.
- **`samplebiller` is a fifth `AggregatorProfile`** (auth `API_KEY`, header `X-Api-Key`) rather than a new
  controller: the same stateful ledger, scenarios, callbacks and reset as the ticket 114 aggregators.
- **No `dryRun` in the sample**: the console adds it at submission; the sample shows `resilience` instead.

## 136b (open): the console-alone walkthrough

Blocked by: 132 (the Billers and Routing screens of the admin console are not built; today steps 4 and 5 are API
calls only) and a seeded integration persona (below).

- [ ] Seeded personas with `integration-maker` / `integration-checker` (mw-seeder `application.yml` personas, Keycloak
      ops realm): today only those roles upload and approve a partner credential, and no seeded persona has them,
      so the walkthrough cannot store the `samplebiller` API key as seeded personas.
- [ ] Playwright `ui/mw-admin-ui/e2e/94-add-biller.spec.ts` (`@partners @billers`): as seeded personas create the
      `samplebiller` route (paste `deploy/partner-routes/examples/sample-rest-biller.yaml`, family billpay), the
      `api-key` credential, the routing rule (BILL_PAY, biller SAMPLEWATER -> samplebiller) and the catalogue entry
      SAMPLEWATER; approve each as the checker; then one more partner change (SET_TIMEOUTS) so a version loads with
      the key; the route reports LIVE; a bill fetch (SAMPLEWATER / 1360000001, 845.00) and a pay through the app API
      complete against mw-simulators, with no restart. Reset the party after (`DELETE /mock-control/samplebiller/journal`).
- [ ] API suite folder `tests/api/src/70-sample-connector` (same flow over the admin API and the app API), its
      requests mapped in `tests/api/TRACEABILITY.md`, collection regenerated (`node tests/api/build_collection.mjs`),
      `scripts/api-test.sh 70` green on a fresh stack.
- [ ] Then CON-06 to `done` in `docs/frs-traceability.md`.
