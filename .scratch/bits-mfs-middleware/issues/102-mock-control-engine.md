# 102: Unified mock-control engine in mw-simulators (FRS 6.3, 10.1, ADR D33)

**What to build:** One mock-control API on the FRS shape, replacing the per-simulator controls. Today:
`services/mw-simulators/.../controller/ControlController.java` serves `/sim/{partner}/scenario` for ocs, billagg and
c2wbank only, with `dto/SimMode.java` (SUCCESS, BUSINESS_FAILURE, TIMEOUT, SERVER_ERROR_AFTER_SEND, UNKNOWN_CODE,
MALFORMED), fixed `latencyMs`, `outage`, `errorRate`; SMSC, USSD SMPP and payShield each have their own switches;
journal (`filter/RecordingFilter.java`) covers three partners; no scoping beyond magic data; no reset.

**Principles:**
- Magic values (`service/MagicAmounts`, MSISDN/account endings) stay the **default** trigger (MCK-03); an explicit
  scenario overrides them for its scope.
- Every mock party in mw-simulators uses the one engine, so new mocks (tickets 110–123) get it for free.
- Non-prod only: the engine bean exists only in profile `mock-control` (MCT-08 hardening is ticket 103).

**Scope:**
- Package `service/mockcontrol/`: `ScenarioEngine` (lookup order: next-N scoped → per key (MSISDN/account/biller/txnRef)
  → per operation → party-wide → magic value → SUCCESS), `PartySwitch` (DOWN = close socket/refuse, MAINTENANCE = the
  party's maintenance reply, SLOW = random latency range), `Journal` (bounded, queryable).
- API (`controller/MockControlController`, contract `contracts/api/mw-simulators/mock-control.v1.openapi.yaml`, owner
  mw-simulators): `POST|GET|DELETE /mock-control/{party}/scenarios` (operation, behaviour SUCCESS,
  BUSINESS_ERROR(code), TIMEOUT, DELAY(ms), HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET, DUPLICATE_CALLBACK,
  LATE_CALLBACK(ms), PARTIAL_SUCCESS; scope global/key/nextN), `PUT /mock-control/{party}/switch`,
  `GET|DELETE /mock-control/{party}/journal` (DELETE also resets that party's state, MCT-06),
  `GET /mock-control/parties`.
- Migrate ocs, billagg (REST + SOAP), c2wbank, merchant IPN receiver, SMSC (MCT-04: throttle, no/late DLR, bind refusal),
  USSD gateway (bind refusal, dropped enquire_link, ESME_RTHROTTLED), payShield onto it. `/sim/*` paths remain as
  aliases for one release (log a deprecation line once).
- Callbacks: `CallbackService` modes come from the engine for every party that sends callbacks.
- Test console (`ui/mw-test-console/src/app/simulators/`) and `tests/api` lib switch to the new API.
- Docs: `services/mw-simulators/CLAUDE.md`, `docs/concepts/` test-doubles page (trigger table per mock, acceptance #5).
- Split if > 1,500 lines: 102a engine + REST parties, 102b SMPP parties + test console.

**FRS IDs covered:** MCK-03, MCK-04, MCK-05, MCK-06, MCK-07, MCT-01, MCT-02, MCT-03, MCT-04, MCT-06

**Blocked by:** 89

**Status:** open

- [ ] `ScenarioEngineTest`: lookup order; nextN counts down; key scope beats global; magic value used when nothing set
- [ ] `MockControlControllerIT`: every behaviour of MCT-01 reachable on ocs; CONNECTION_RESET resets the socket;
      PARTIAL_SUCCESS returns the party's partial reply; DOWN refuses connections
- [ ] `SmppMockControlIT`: bind refusal, ESME_RTHROTTLED, missing DLR on SMSC and USSD gateway
- [ ] Alias test: `/sim/ocs/scenario` still works and maps to the engine
- [ ] `./gradlew :mw-simulators:build :contractsLint` green with Docker; `cd ui/mw-test-console && pnpm test`
- [ ] With the stack up: new API folder `39-mock-control`; every existing folder (00–29) still green (magic values unchanged)
