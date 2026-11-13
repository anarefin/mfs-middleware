# D6: Next.js admin console

Status: Accepted, 2026-11-03 (spec revision 1). Version raised from 16.3.8 to 16.4.1 by the ticket 46 security pass (CRITICAL and HIGH CVEs).

## Context

Operators need a web console for transaction search, saga timeline, maker-checker approvals, partner configuration, USSD menus and SMS templates, with Keycloak SSO and MFA.

## Decision

`mw-admin-ui` is a Next.js 16.4.1 App Router application (TypeScript strict, React Server Components, Auth.js with Keycloak realm `ops`, Authorization Code + PKCE). It calls `mw-admin-api` server-side only; the browser never holds a token. It is served under `/console` through APISIX.

## Consequences

- A UI module, not a Gradle build: it lives under `ui/` and is tested with `pnpm test` and Playwright against the running, seeded stack (`scripts/ui-e2e.sh`, originally `make ui-e2e`, ticket 59), outside the Gradle build.
- `scripts/security.sh mw-admin-ui` (originally `make security M=mw-admin-ui`) scans its lockfile and image.
- The console's login needs the default HTTPS port 443 (the public issuer is `https://localhost/realms/ops`).

## Where in the repo

- `ui/mw-admin-ui/CLAUDE.md`
- `docs/security-findings.md` (the 16.4.1 upgrade)
- spec section 15
