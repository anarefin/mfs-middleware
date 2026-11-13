# M5: Partners are simulators

Status: Accepted, 2026-11-03.

## Context

Real partner APIs and test accounts are not available yet; the adapter framework must still be proven end to end.

## Decision

`mw-simulators` provides the OCS (top-up), one bill aggregator, the C2W bank, the SMSC and the USSD gateway, each to a generic contract per partner type, with magic values and a control API for scenarios (timeouts, errors, callbacks, replays).

## Consequences

- Real partner mappings are configuration on the adapter framework later (spec 11.3 onboarding checklist).
- The simulators' control API is reachable only through test-only APISIX routes that `scripts/api-test.sh` and `scripts/tester.sh` enable (originally the Cucumber `e2e` target, then `make api-test` and `make tester`).

## Where in the repo

- `services/mw-simulators/CLAUDE.md`
- `deploy/apisix/routes-test`
