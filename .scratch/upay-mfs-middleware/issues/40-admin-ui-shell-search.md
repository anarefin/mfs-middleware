# 40: Admin UI shell + txn search / detail

**What to build:** Operators log in to the admin console and find transactions. `mw-admin-ui` is a Next.js 16.3.8 App Router app (TypeScript strict) using Auth.js with Keycloak `ops` (Authorization Code + PKCE, MFA). Tokens are kept server-side in an encrypted HTTP-only cookie, and all data calls go server-side to `mw-admin-api`. It has the layout, transaction search and a detail page with saga timeline and audit trail. It runs as a standalone Docker image behind APISIX with CSP, HSTS and X-Frame-Options DENY. Spec WP24.

**Blocked by:**

- 36: Admin API: RBAC + txn search / timeline

**Status:** ready-for-agent

- [ ] Playwright: login, search by txnId and by MSISDN, open the detail page and see the timeline
- [ ] The browser never receives the access token (checked in Playwright)
- [ ] `pnpm test` and the Playwright suite pass
