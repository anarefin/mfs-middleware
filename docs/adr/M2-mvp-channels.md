# M2: MVP channels

Status: Accepted, 2026-11-03.

## Context

Each channel type has its own trust model (device-bound app, MNO gateway, merchant HMAC, organisation portal).

## Decision

Customer, agent and merchant apps over REST (no SOAP facade) through `mw-app-gateway`; USSD for one MNO through `mw-ussd`; the web checkout API through `mw-checkout`; the org and SP portal API through `mw-portal-api`.

## Consequences

- App requests are signed by a bound device (re-bind needs an OTP); checkout merchants sign with HMAC and receive signed IPNs with retries; bulk files go through maker-checker.
- Front-ends other than the admin console are out of scope.

## Where in the repo

- `services/mw-app-gateway/CLAUDE.md`
- `services/mw-checkout/CLAUDE.md`
- `services/mw-portal-api/CLAUDE.md`
- spec section 9
