# mw-admin-ui

Operations console (spec 15.1, WP24, ticket 40): Next.js 16.3.8 App Router, React Server
Components, TypeScript strict, pnpm. A UI module, not a Gradle build: it lives under `ui/` (spec
4.2), so the Gradle scripts (`make verify`, `check-isolation`, `changed-modules`, `e2e-changed`)
do not see it. Module isolation still holds: nothing outside this folder is referenced except the
root `contracts/` (read by `src/lib/contract.test.ts` only, `MW_CONTRACTS_DIR`, default
`../../contracts`).

## Responsibilities

- Login (`src/auth.ts`, Auth.js / next-auth 5 beta): Keycloak realm `ops`, client `mw-admin-ui`
  (public client, Authorization Code + PKCE S256, `state` and `nonce`; direct access grants off,
  security review: the e2e password grant uses the dev-only client `mw-e2e-ops`). MFA is the realm's (browser
  flow password + TOTP, `CONFIGURE_TOTP` on every user); the console does not check `acr`.
  The session is a JWT in an encrypted (JWE with `AUTH_SECRET`), HTTP-only, SameSite=Lax cookie
  holding access token, refresh token, expiry and roles. The session object the browser can read
  (`/console/api/auth/session`, `session` callback) is `{user.name, roles, expires, error?}`, never
  a token. Expiring tokens are refreshed (30 s skew) by the proxy (persisted in the cookie) and, for
  the same request, in memory by `lib/server/admin.ts` `operatorToken()`. Sign-out also ends the
  Keycloak session (back-channel logout with the refresh token).
- `src/proxy.ts` (Next 16 "proxy", ex-middleware): every request; no or failed session -> `/login`
  (`callbackUrl` checked by `lib/redirect.ts` `safeCallback`, no open redirect); per-request CSP
  nonce (`lib/security-headers.ts`: `script-src 'self' 'nonce-…' 'strict-dynamic'`,
  `frame-ancestors 'none'`, `form-action 'self' <Keycloak origin>`); `cache-control: no-store`.
  `next.config.ts` adds HSTS, X-Frame-Options DENY, nosniff, Referrer-Policy no-referrer,
  Permissions-Policy, COOP to every response.
- Data calls only server-side (`lib/server/admin.ts`, `import "server-only"`) to mw-admin-api
  (`ADMIN_API_URL`, in the stack `http://apisix:9080`, the `/admin/v1/*` route) with the operator's
  bearer token and a fresh `X-Correlation-Id` (`lib/admin-api.ts`). Admin API unreachable or a
  non-envelope answer -> own envelope `MW-SYS-041` (retryable); otherwise the API's envelope is
  shown (code, message, details, correlationId).
- Screens: `/console/transactions` (GET form: txnId, MSISDN in any form, partnerRef, status,
  type, from/to UTC days -> `[from, to)`; `lib/search.ts` checks them as the contract does, an
  empty form does not query; paging 50), `/console/transactions/{txnId}` (summary, saga timeline
  STATE/CORE, partner calls, audit trail; "Reveal MSISDN (audited)" link only for
  compliance-officer / auditor, `?reveal=true`, mw-admin-api decides and audits). Values are shown
  as the API masks them.
- Maker-checker (ticket 41, spec 15.3; wallet unlock screen of ticket 52). Writes are server
  actions (`src/app/(console)/actions.ts`, `"use server"`), posted by the client wrapper
  `action-form.tsx` (`useActionState`: the refusal envelope shows next to the form, buttons
  disabled while pending). Each action checks its input as the contract does (UUID ids, action
  enum, reason 1..500 trimmed, wallet id via `lib/approvals.ts` `parseWalletId`; else own
  `MW-VAL-001`), calls mw-admin-api with the operator's token and on success redirects to the inbox
  (`/approvals?created=<id>`, after a decision `?status=all&decided=<id>`).
  - Transaction detail: an "Actions" panel for ops-maker with one Reason field and the buttons the
    status allows (`txnActionsFor`, ticket 37 rules: REVERSE of a COMPLETED, unreversed,
    non-REVERSAL txn = "Request reversal"; INQUIRE in UNKNOWN / MANUAL_REVIEW = "Request manual
    TSQ"; FORCE_COMMIT / FORCE_RELEASE in MANUAL_REVIEW). While a PENDING request exists for the
    txn (`GET /change-requests?resourceId=<txnId>&status=PENDING`) the panel is replaced by a notice
    `pending-request`.
  - `/console/approvals`: change requests (filter PENDING default, APPROVED, REJECTED, EXPIRED,
    any; first 100), with the diff (status before -> the one the action leads to, e.g. COMPLETED ->
    REVERSING, LOCKED -> ACTIVE), what is sent (amount, reversal / action / command ids), maker,
    reason, expiry. Approve / Reject (with a "Rejection reason") only when `mayDecide`: PENDING,
    the operator holds ops-checker and is not the maker (`makerId` = maker's `preferred_username`
    = session `user.name`); the maker sees "your request: another checker decides", even with
    ops-checker. mw-admin-api enforces all of it (403 MW-AUTH-030 etc.); the UI only hides buttons.
  - `/console/wallets`: ops-maker files UNLOCK for a wallet id (MSISDN in any form, normalised to
    `01XXXXXXXXX`); the request shows (masked) in the inbox for an ops-checker. The UI does not show
    the core's result (WalletUnlocked / UnlockRejected are not tracked by mw-admin-api, ticket 52).
  - Decision (no product owner): the console does not offer a maker withdrawal (the API has none),
    and the server actions rely on Next's built-in Origin/Host check for CSRF (APISIX passes the
    Host through; a proxy that rewrote Host would need `experimental.serverActions.allowedOrigins`).
- `/console/api/health` -> `{"status":"UP"}` (container healthcheck, public).

## Contracts used

- `api/mw-admin-api/admin.v1` (GET search and detail; change requests: list, create for a
  transaction / a wallet, approve, reject): types in `src/lib/types.ts`. The Playwright stub
  answers (`e2e/mock/admin-api-stub.mjs`, fixtures `e2e/fixtures/admin-api.json`; the change-request
  part applies mw-admin-api's maker-checker rules on an in-memory store and, on an approved REVERSE,
  plays the orchestrator: original REVERSED + a REVERSAL txn) are validated against the contract by
  `src/lib/contract.test.ts`. Owns no contract.

## Deploy

- `Dockerfile`: `output: "standalone"`, node 24 alpine, uid 10001, no dev deps or test doubles.
- `compose.yml`: network `ops` only; read-only root, tmpfs `/tmp` and `/app/.next/cache`; 256M.
  Env: `AUTH_SECRET` (`MW_ADMIN_UI_AUTH_SECRET`, generated by `scripts/gen-dev-env.sh`),
  `AUTH_URL` (`<public>/console/api/auth`, required behind a proxy: the standalone server's own
  URL is its bind address), `AUTH_KEYCLOAK_ISSUER` (`<public>/realms/ops`),
  `AUTH_KEYCLOAK_INTERNAL_URL` (`http://keycloak:8080`), `ADMIN_API_URL`. Public base
  `MW_ADMIN_UI_PUBLIC_URL`, default `https://localhost`. Included in `deploy/compose/mvp`.
- Issuer (decision, ticket 40): the browser reaches Keycloak through APISIX route
  `auth-ops-browser` (`/realms/ops/*`, `/resources/*`, only for Host `MW_PUBLIC_HOST`, default
  `localhost`, which must match `MW_ADMIN_UI_PUBLIC_URL`; X-Forwarded-Host/Proto/Port pinned to it,
  port 443, never the client's Host; its token endpoint takes only authorization_code and
  refresh_token); this server calls Keycloak in the stack with the same X-Forwarded-* values
  (`lib/oidc.ts` `internalRequest`, Auth.js `customFetch`), so ops tokens from the console carry
  `https://localhost/realms/ops`. APISIX `admin-v1` accepts that issuer and the in-stack one
  (`claim_validator.issuer.valid_issuers`); mw-admin-api checks the `/realms/ops` suffix. A
  non-default `MW_HTTPS_PORT` breaks the console login (not the APIs).
- APISIX `admin-ui` routes `/console`, `/console/*` to `mw-admin-ui:3000` (quota 600/min per
  address, 64 KiB bodies). Known gaps: one APISIX (spec wants the internal group), and the edge
  error envelope turns HTML 4xx/5xx of the console and Keycloak into JSON envelopes.

## Commands

| Command | What it does |
| --- | --- |
| `pnpm install` | dependencies (pinned, `pnpm-lock.yaml`) |
| `pnpm test` | `tsc --noEmit` + Vitest unit and contract tests (`src/**/*.test.ts`) |
| `pnpm test:e2e` | Playwright (Chromium): builds, runs the standalone server against `e2e/mock/server.mjs` (fake Keycloak realm ops with PKCE + TOTP form, stub mw-admin-api); no stack. Users `ops-viewer01`, `ops-compliance01`, `ops-maker01`, `ops-checker01`, `ops-lead01` (maker + checker). `e2e/console.spec.ts` (ticket 40), `e2e/approvals.spec.ts` (ticket 41 + 52 screen; `resetStub` before each). Ports 3940/4910/4920 (`E2E_*_PORT`); `E2E_SKIP_BUILD=1` reuses `.next` |
| `pnpm dev` | dev server (needs the env above) |
| `docker build -t mw-admin-ui:dev .` | image |

Test fixtures use fake values only (`fake-test-password`, OTP `123456`, fake secret).
