# D21: mw-auth issues the wallet users' tokens

Status: Accepted, 2026-11-14 (spec revision 6).

## Context

Today the Keycloak realm `customers` issues app tokens through a password grant with a test password; there is no MSISDN + PIN login. A wallet platform has millions of users whose login factor is a PIN the middleware now owns (D20) plus a bound device; storing every wallet user in Keycloak and verifying the PIN through a Keycloak extension adds a stateful copy of the wallet base and a custom plugin to maintain.

## Decision

- `mw-auth` authenticates wallet users with MSISDN + PIN + a bound device and issues an access JWT (5 min) and an opaque refresh token (30 days, stored hashed, device-bound, rotated on every use, reuse revokes the family). Tokens are signed by an `mw-crypto` key and published as JWKS (spec 18.7).
- APISIX and `mw-app-gateway` validate the tokens against that JWKS.
- The Keycloak realm `customers`, its APISIX token route and its test password are removed. Keycloak keeps `ops` (TOTP), `portals` (TOTP added), `partners` (`private_key_jwt` instead of shared secrets) and `services`; every realm gets brute-force detection and a password policy.

## Consequences

- One login path for apps, USSD (no token, PIN per transaction) and checkout (OTP + PIN).
- Device binding, OTP, sessions and nonces move from `services/mw-app-gateway` into `mw-auth` (ticket 68).
- The seeder, test console, Newman suite and Playwright logins change from the Keycloak password grant to `mw-auth` (tickets 73, 76).

## Where in the repo

- spec sections 8.2, 9.1, 18.2, 18.7 and decision D21
- `deploy/keycloak/realms`, `deploy/apisix/routes`
- tickets 68, 69, 73
