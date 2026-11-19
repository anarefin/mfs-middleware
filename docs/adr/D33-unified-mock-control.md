# D33: One mock control API for every mock

Status: Accepted, 2026-11-19 (user decision, FRS planning, ticket 89). Amends spec 22.2 (the
`/sim/{partner}/scenario` control API) and M5; built by tickets 102 (engine in mw-simulators) and
103 (engine in mw-core-mock, global reset, seeds, production exclusion).

## Context

The simulators grew one control surface per party: `/sim/{partner}/scenario` and
`/sim/{partner}/requests`, `/sim/payshield/scenario`, `/sim/ussdgw/smpp/scenario`, the stub
journal `/sim/stubs/{partnerId}/requests`, the SMSC inbox, and mw-core-mock's own
`/core-mock/v1/faults`. Tests and the test console must know each one. The FRS (10.1, MCT-01 to
MCT-08; 6.3, MCK-01 to MCK-09) asks for one API across all mocks and the mock core wallet: scenarios
per operation with scoping, party-level DOWN / MAINTENANCE / SLOW switches, a request journal and
one reset.

## Decision

- **One API, the FRS's paths**, served by every mock process:
  - `POST /mock-control/{party}/scenarios`: the active behaviour of an operation (`SUCCESS`,
    `BUSINESS_ERROR(code)`, `TIMEOUT`, `DELAY(ms)`, `HTTP_5XX`, `MALFORMED_RESPONSE`,
    `CONNECTION_RESET`, `DUPLICATE_CALLBACK`, `LATE_CALLBACK(ms)`, `PARTIAL_SUCCESS`), scoped
    globally, per MSISDN / account / biller code, or for the next N calls (MCT-01, MCT-02); party
    switches DOWN, MAINTENANCE, SLOW (MCT-03); SMPP and file faults for the parties that have them
    (MCT-04, MCT-05);
  - `GET /mock-control/{party}/journal` and `DELETE` to clear the journal and the party's state
    (MCT-06);
  - `POST /mock-control/reset`: every mock and the mock core wallet reload their versioned seed
    files (MCT-07, MCK-08). mw-simulators fans the reset out to mw-core-mock.
- **Magic values stay** the default data triggers (amount ending `.13` = timeout and the rest of
  spec 22.2, MCK-03); a scenario set through the API wins over a magic value while it is active.
- **The engine is copied, not shared** (D14): one implementation in mw-simulators and a copy in
  mw-core-mock, kept identical apart from the package. Every new partner mock (card, remittance,
  ATM, NPSB/BanglaQR, EC, AML, trust bank, DW, push, merchant SOAP) lives in mw-simulators.
- **The old `/sim/*` control paths stay as aliases for one release** and then go; `/core-mock/v1/faults`
  likewise. The test console and the API suite move to `/mock-control` in tickets 102 and 103.
- **Never in production** (MCT-08): the controllers load only under the dev/test profile; a build
  check fails when a production profile would expose them. mw-simulators and mw-core-mock are
  test-only services anyway (spec 4.1).

## Consequences

- One Newman helper and one test console screen drive every mock (ticket 104, 135 ADM-16).
- A scenario is visible state; `POST /mock-control/reset` before each suite makes runs repeatable.
- Scoped scenarios need a matcher on every simulated operation; each party declares its operations
  and scope keys.

## Where in the repo

- `services/mw-simulators`, `services/mw-core-mock`
- `ui/mw-test-console` (the controls it calls)
- spec decision table (D33), section 22.2
