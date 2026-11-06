# mw-admin-ui

Operations console (spec 15.1, WP24, ticket 40): Next.js 16.4.1 App Router (16.3.8 until the ticket 46 security pass: CRITICAL/HIGH CVEs fixed in 16.4.1), React Server
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
    `pending-request`; when that check fails no buttons are shown, only the note
    `pending-check-failed` (`txnActionGate`).
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
- Partner, USSD menu and template editors (ticket 42, spec 15.2 / WP25; API of tickets 38, 39). All
  three file change requests through server actions in `actions.ts` (`requestPartnerChange`,
  `proposeMenu`, `proposeTemplate`) and land in the inbox like the others; an open request of the
  resource (`pending-notice.tsx`, `?resourceType&resourceId&status=PENDING`) replaces the forms by
  the `pending-request` notice. The inbox links each resource to its screen, shows a partner diff as
  the changed fields (`enabled=true -> enabled=false`), a menu / template diff as `v1 -> v2` and,
  once approved, `published vN`. Who decides: `approvals.ts` `mayDecide` (partner: ops-checker or
  integration-checker; menu / template: ops-checker; never the maker).
  - `/console/partners` (every partner: state, timeouts, version, changed / approved by) and
    `/console/partners/{id}` (published config without secrets: credential ref names only). For
    ops-maker / integration-maker (`canMakePartner`): "Request disable" / "Request enable" and a
    timeouts form (`parseTimeouts`: whole ms, connect / call 1..120000, read 1..125000 and >= call;
    empty = unchanged; else own `MW-VAL-001`). UPSERT (full config) is not offered in the console.
  - `/console/ussd?menuId=main[&from=N]`: the versions made in the console, the editor
    (`ussd/menu-editor.tsx`, client) on the newest PUBLISHED version or `from`: tree from `start`
    (`lib/ussd-menu.ts` `tree`, unreachable nodes last), per-node form (type, bn / en texts, options
    with next, validator / store / next, flow / onSuccess / onFailure, lookup source), the six engine
    messages, a raw JSON view ("Apply JSON"), and early warnings (`menuProblems`: dangling references,
    a text without bn or en; warnings only, mw-admin-api's MenuValidator decides, `MW-VAL-040` shown
    with its details). Preview walk (`startPreview` / `stepPreview`): mw-ussd's UssdEngine rules
    (options, validators, notSelf, 3 invalid attempts, language switch, `{input}` interpolation) in the
    browser only; an ACTION runs nothing, its outcome is picked (completed -> onSuccess with
    `txnId` PREVIEW-TXN, failed -> onFailure, processing -> the processing message); a DYNAMIC list
    shows two sample items; the PIN is never stored and shows as `****`. The proposal: MNOs gp / robi
    / bl / teletalk (all ticked by default), optional go-live per MNO (datetime-local read as UTC,
    `parseActivations`), reason. With no version made in the console the editor starts from an empty
    starter (the seeded tree lives in mw-ussd; paste it as JSON).
  - `/console/templates?code&channel&locale`: picker (codes mw-notify seeds as suggestions,
    `lib/templates.ts` `KNOWN_CODES`; any contract-valid code accepted), versions, the live text of the
    newest PUBLISHED version, and the editor (`templates/template-editor.tsx`, client; body with
    `lang`=locale and `dir=auto` for Bangla, subject / HTML body for EMAIL, sender id, valid from UTC).
    Live preview on sample params (fake values, editable per placeholder): `renderPreview` masks each
    param by name exactly as mw-notify's ParamMasker + MaskingPolicy (msisdn / receiver / sender /
    counterparty / agent `0171****678`, account last 4, nid last 3, names initial, pin `****`, a value
    with `****` kept); SMS size GSM-7 / UCS-2 (`smsInfo`); `templateProblems` is a copy of
    mw-admin-api's TemplateValidator and blocks the submit (own `MW-VAL-040`). Anyone may type in the
    editor to preview; only an ops-maker gets the submit.
  - Decisions (no product owner): integration-maker / -checker see Partners like the API allows;
    partner UPSERT / onboarding, the ops-emergency direct change, and maker withdrawal are not offered;
    go-live / valid-from inputs are UTC like every time in the console; the menu check in the UI only
    warns (a stricter UI copy could refuse what the API accepts), the template check blocks (exact copy).
- `/console/api/health` -> `{"status":"UP"}` (container healthcheck, public).

## Contracts used

- `api/mw-admin-api/admin.v1` (GET search and detail; change requests: list, create for a
  transaction / a wallet / a partner, approve, reject; partners list / one; USSD menu and template
  versions list / one and proposals): types in `src/lib/types.ts`. The Playwright stub
  answers (`e2e/mock/admin-api-stub.mjs`, fixtures `e2e/fixtures/admin-api.json`; the change-request
  part applies mw-admin-api's maker-checker rules on an in-memory store and, on an approved REVERSE,
  plays the orchestrator: original REVERSED + a REVERSAL txn; on an approved partner change it
  publishes the config at version + 1, on an approved menu / template the version PUBLISHED, a
  rejected one REJECTED) are validated against the contract by `src/lib/contract.test.ts`. Owns no
  contract.

## Deploy

- `Dockerfile`: `output: "standalone"`, node 24 alpine, uid 10001, no dev deps or test doubles.
- `deploy/docker-compose.yml` service `mw-admin-ui` (profile `core`, image `mfs-mw/mw-admin-ui:local`, `make build M=mw-admin-ui`): network `ops` only; read-only root, tmpfs `/tmp` and `/app/.next/cache`; 256M.
  Env: `AUTH_SECRET` (`MW_ADMIN_UI_AUTH_SECRET`, generated by `scripts/gen-dev-env.sh`),
  `AUTH_URL` (`<public>/console/api/auth`, required behind a proxy: the standalone server's own
  URL is its bind address), `AUTH_KEYCLOAK_ISSUER` (`<public>/realms/ops`),
  `AUTH_KEYCLOAK_INTERNAL_URL` (`http://keycloak:8080`), `ADMIN_API_URL`. Public base
  `MW_ADMIN_UI_PUBLIC_URL`, default `https://localhost`. No `compose.yml` of its own (ADR D16).
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
| `pnpm test:e2e` | Playwright (Chromium): builds, runs the standalone server against `e2e/mock/server.mjs` (fake Keycloak realm ops with PKCE + TOTP form, stub mw-admin-api); no stack. Users `ops-viewer01`, `ops-compliance01`, `ops-maker01`, `ops-checker01`, `ops-lead01` (maker + checker), `ops-integration01` (integration-maker + -checker). `e2e/console.spec.ts` (ticket 40), `e2e/approvals.spec.ts` (ticket 41 + 52 screen; `resetStub` before each), `e2e/partners.spec.ts`, `e2e/ussd.spec.ts`, `e2e/templates.spec.ts` (ticket 42: edit -> approve -> effect visible per screen). Ports 3940/4910/4920 (`E2E_*_PORT`); `E2E_SKIP_BUILD=1` reuses `.next` |
| `pnpm dev` | dev server (needs the env above) |
| `docker build -t mw-admin-ui:dev .` | image |

Test fixtures use fake values only (`fake-test-password`, OTP `123456`, fake secret).

Security pass (ticket 46, `docs/security-findings.md`): the runtime image keeps node only (npm, npx and
corepack removed: their bundled undici / brace-expansion carry HIGH CVEs) and runs `apk upgrade`;
`make security M=mw-admin-ui` scans `pnpm-lock.yaml` and the `mw-admin-ui:dev` image.
