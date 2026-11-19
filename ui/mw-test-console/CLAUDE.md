# mw-test-console

Test console (ADR D17): **dev/test only**. A Next.js 16.4.1 app (App Router, React 19, TypeScript
strict, pnpm) that plays every outside party of the middleware against the local stack, so each
feature can be tested by hand from a browser: the mobile app (customer, agent, merchant), a USSD
handset, the partners (OCS, bill aggregator, C2W bank: their simulators), the checkout merchant and
payer, and the org portal. It also shows every dev login and key (live TOTP codes included), what
mw-seeder created, account tools and a read-only transaction explorer (ADR D17 amendment
2026-11-15). The step-by-step guide that uses it is `docs/testing-guide.html`. Ops features stay in
the admin console (`ui/mw-admin-ui`, `/console`).

A UI module, not a Gradle build (like mw-admin-ui): the Gradle scripts do not see it. Nothing outside
this folder is referenced. It is never deployed outside a laptop / test stack: it impersonates
customers, holds and **shows** the stack's fixed fake dev credentials (`deploy/dev.env.defaults`, ADR
D9 amendment), can unlock and reset accounts, and has no sign-in.

## How it works

- Every call goes through APISIX (`EDGE_URL`, in the stack `http://apisix:9080`), server-side only:
  server actions in `src/app/actions/*.ts` (`"use server"`) call `src/lib/server/*` (`import
  "server-only"`). The browser never sees a token or a device key: APISIX has no CORS, the test routes
  need `MW_E2E_API_KEY`, and checkout hmac-auth needs the merchant secret and a `Date` header. The one
  exception are the dev values the logins and seed data pages display on purpose: only the names
  listed in `lib/secrets.ts`, read through `env.optional()` (`lib/server/env.ts`: a missing value
  renders "not set", never throws), masked until shown (`components/secret-value.tsx`), never logged.
- `lib/server/edge.ts`: the edge client (fresh `X-Correlation-Id`, `apikey` for `/test/*`, no
  redirects followed) and `Trace`, which records each call as a `Step` (label, call, HTTP status,
  answer redacted by `lib/redact.ts`, ms). Every action returns an `Outcome` (`lib/types.ts`): the
  page (`app/components/result.tsx`) shows the headline, final status, core balances before/after,
  the SMS received and every step, expandable.
- `lib/server/tokens.ts`: Keycloak password grant through APISIX (`portals`/`mw-portal`) with the TOTP
  code of the portals users' pre-set secret `KC_PORTALS_TEST_TOTP_SECRET` (`lib/crypto.ts` `totp`;
  the realm demands TOTP since ticket 73; one code per user per 30 s step), cached per user, refreshed; `walletOf` (async: an app user's MSISDN from `knownAppUsers()`, i.e. `lib/app-users.ts`
  over the manifest, or an MSISDN typed in). `lib/server/device.ts`: the app's bound device and mw-auth session per user
  (tickets 68-69; EC P-256 key; `POST /auth/v1/otp` with the MSISDN, the OTP read from the SMSC
  simulator; `POST /auth/v1/devices` with the app PIN `MW_SEED_APP_PIN` encrypted under mw-auth's
  `GET /auth/v1/pin-key`; login `POST /auth/v1/sessions` (MSISDN + PIN), again after 4 min idle;
  `POST /auth/v1/tokens/refresh` before the access token expires; the mw-auth access token is the app
  API's bearer; signature over method, path, body hash, timestamp, nonce, device id). `lib/crypto.ts`
  (pure, Node crypto): signing, RSA-OAEP-SHA256 PIN encryption (every PIN under the mw-crypto
  transport key from `GET /auth/v1/pin-key`; checkout with the page's `pinKey`; ticket 72), checkout hmac-auth, result
  and IPN checks; ported from `tests/api/lib.js`. State (tokens, devices, last submit, PIN key) is in
  process memory (`globalThis`): a restart forgets it and the next submit binds again. Binding here
  replaces the API suite's device for that user (one active device per wallet).
- Pages: `/` (stack checks, seeded balances, reset), `/app` (bind, the seven app flows, resend with
  the same Idempotency-Key, billers / fetch bill / status / merchant collections; `FEE_INCOME` (core-mock fee income since ticket 94) shown as
  the fee; who is signed in = Category -> Wallet: `lib/app-users.ts` (pure) lists the PIN-ready wallets
  (manifest `appPins.walletIds`; personas, `seed-*` mw-auth personas, run customers last; the personas
  only without a manifest), loaded by the server page, plus "Other MSISDN…"; the signed-in card
  (`app/app/signed-in-card.tsx`, action `walletCard`: core-mock balance / held, `deviceState` bound and
  login time) reloads after every call; flows per role `FLOWS_BY_ROLE` in `lib/personas.ts` (merchant:
  none); counterparty lists, magic numbers and the scenario chips in `lib/app-flow-options.ts` (pure;
  chips fill through `FILL_EVENT`); the bill pay biller picker (`app/app/biller-picker.tsx`, action `listBillers`: the live `GET /app/v1/billers` only while the device has a session, never binding, else the seed catalogue with a note; category chips, search, REST / SOAP badge from `lib/seed-catalogue.ts` `protocolOf`, DESCO the default); block 4 History (`app/app/history-panel.tsx`, `appRead` case
  `history`: `GET /app/v1/transactions` as the signed-in user with the session headers, `lib/app-history.ts`
  builds the query and reads the page defensively; loaded on demand, then again after each submit;
  type / direction filters, More by offset; an error envelope such as 503 `MW-SYS-013` shows as a result)), `/ussd` (handset on the four MNOs of `lib/ussd-mnos.ts` (pure), each with its own code
  that the Code field takes when the MNO is picked: gp `*268#` SMPP, robi `*269#`, bl `*270#`, teletalk `*271#`
  HTTP; HTTP resumes the open session per key press, SMPP replays the session from the dial with every input so
  far, as the simulator cannot resume SMPP; inputs on a PIN screen show as ****; a session is live only while
  open on the MNO, number and code in the form: End (`op=end`, local hang-up) or changing one of them drops it
  and the button goes back to Dial; a refused or non-200 step counts as ended, so no reload is ever needed), `/simulators` (presets in `lib/presets.ts`, per-partner
  scenario forms, recorded requests, callback replay, core-mock faults, wallet lookup, SMPP binds /
  drop / throttle), `/inbox` (SMSC and GreenMail), `/checkout` (merchant picked from `lib/seed-catalogue.ts` `CHECKOUT_MERCHANTS`, kept with the session; create, page, OTP, pay, signed
  redirect, IPNs), `/bulk` (sample CSV, upload, approve / reject / pause / resume, status, result,
  transactions, SP query), `/auth` (ticket 76, wallet authentication at mw-auth on fresh wallets of its
  own: `lib/auth.ts` (pure: OTP texts, fresh candidates 01711000500..999, the new-device window),
  `lib/server/auth.ts`, `actions/auth.ts`: fresh wallet with its first PIN via the LOGIN OTP, PIN login,
  a second device (old one refused, the newDevice cooling window until boundAt + 24 h shown; the flag
  itself travels only in the assertion to the core), PIN change, self-service reset, taking up a care
  reset approved in `/console` on a new phone, three wrong PINs to the lock).
- `/credentials` ("Logins & secrets"): every `seed-ops-*` / `ops-e2e-*` / `ops-*01` and portal login with its
  password and a live TOTP code (server action over `lib/crypto.ts` `totp`, refetched at each 30 s
  boundary, `lib/totp-window.ts`, `components/totp-code.tsx`; every ops and portal user has the pre-set
  secret, `components/no-preset-code.tsx` shows only for a stale manifest; usernames copyable,
  `components/copyable-text.tsx` `Username`, test ids `user-<u>` / `copy-user-<u>`), the app PIN `MW_SEED_APP_PIN`, the wrong PIN 9999, a
  PIN -> ciphertext helper (mw-auth's PIN key), the latest BIND / LOGIN / RESET OTP of an MSISDN
  (`lib/otp-lookup.ts` over the SMSC simulator) and the platform secrets (Keycloak admin, Vault
  tester token, APISIX keys, partner HMAC, USSD gateway passwords, SoftHSM labels and PINs).
- `/seed-data` ("Seed data"): mw-seeder's manifest (`lib/seed-manifest.ts`, pure and defensive;
  `lib/server/seed.ts` reads `SEED_MANIFEST`, resolves the `passwordEnv` / `totpSecretEnv` / PIN env
  names a persona carries against the env) plus `lib/seed-catalogue.ts` (org wallets, billers, ... copied
  from the services' config): ops and portal users (realm imports included, `importedUsers`) with
  display names and inline logins, app wallets with holder name, live balance and PIN, run customers,
  orgs, partners, USSD menus, templates, the core-mock generated ranges with a lookup
  (`wallet-lookup.tsx`), every table filterable (`filter-table.tsx`). Without a manifest: "run the
  seed profile". Row tools (`row-tools.tsx`, `actions/accounts.ts`, `lib/server/accounts.ts`), a FIXED
  action list in `lib/account-tools.ts` behind a confirm dialog: wallets Status / Unlock / Reset PIN /
  Unbind (mw-auth's dev-only account test API through `/test/auth/v1/accounts/{walletId}`, contract
  `contracts/api/mw-auth/auth-test.v1.openapi.yaml`) and Set balance (core-mock seed PUT
  `balanceMinor`); ops / portal users Unlock / Reset password (Keycloak admin REST as the
  bootstrap admin: master token through the test route `test-kc-admin-token`, realms `ops` and
  `portals` only through `test-kc-admin`). No TOTP reset: every user keeps the pre-set one-time code
  (the admin API could not put a deleted one back; ADR D17 amendment 2026-11-16).
- `/transactions`: the transaction explorer through mw-admin-api (`actions/txn.ts`,
  `lib/txn-query.ts` validates first) with a realm ops token of `ops-e2e-maker01`
  (`lib/server/ops-token.ts`, cache rules in `lib/token-cache.ts`): search by txnId or MSISDN with
  status / type, detail with saga timeline, partner calls, audit trail and anchor badge; MSISDNs
  masked. Every txnId in a result links there (`TxnLink` in `app/components/result.tsx`, `?txnId=`).
- Every page has a collapsible How to test panel (`components/how-to-test.tsx`, content in
  `lib/how-to.ts`, pure and unit-tested, written from `docs/testing-guide.html`) with fill buttons
  that pre-fill the page's form (`components/fill-scope.tsx`: `name=` / `data-fill=` controls).
- Personas: `lib/personas.ts` (core-mock's app wallets with their holder names, e.g. "Rahim Uddin
  (customer01)", the portals realm import's users with names; any other app wallet through "Other MSISDN…").
  Display names only: user names and MSISDNs are what the forms and the e2e specs select (App flows:
  `app-category` then `app-user`, whose option values are the user names).
- An add money from the bank first sets c2wbank callbacks ONCE when the simulator has none (a step
  says so), as every API suite scenario does.
- `src/proxy.ts`: per-request CSP nonce (`lib/security-headers.ts`), `cache-control: no-store`;
  `next.config.ts`: basePath `/tester`, standalone output, static security headers, `agentRules: false`.

## UI stack

Same design system as `ui/mw-admin-ui` (copied, not shared): Tailwind CSS v4 (`postcss.config.mjs`,
`src/app/globals.css`; root font 100% = 16px, the admin UI keeps 14px; labels, table headers and
section text are `text-sm`, only badges, kbd and the DEV / TEST banner `text-xs`; no fixed px sizes), shadcn/ui-style components in `src/components/ui/` (`components.json`; Radix via
`radix-ui`, `lucide-react`, Geist fonts from `geist`), sidebar shell with the DEV / TEST banner
(`src/components/shell/app-shell.tsx`, cookie `mw-tester-sidebar`) and the theme toggle (cookie
`mw-tester-theme`, `src/lib/theme.ts`; `system` follows `prefers-color-scheme` in CSS, no inline script).
Selects stay native (`NativeSelect`, Playwright `selectOption`); every `data-testid` the e2e suite reads is
kept.

## Deploy

- `Dockerfile`: standalone output, node 24 alpine, uid 10001, npm/npx/corepack removed (as mw-admin-ui).
- `deploy/docker-compose.yml` service `mw-test-console`, profile `tester` (not `core`: outside
  `./gradlew :composeCheck`'s budget, about 192 MiB), network `app`, read-only root. Env from
  `deploy/.env`: `EDGE_URL`, `PUBLIC_URL` (links to `/console`), `MW_E2E_API_KEY`, `MW_SEED_APP_PIN`
  (ticket 69), `KC_PORTALS_TEST_PASSWORD`, `KC_PORTALS_TEST_TOTP_SECRET` (ticket 73),
  `CHECKOUT_DEMO_MERCHANT_SECRET`, `CHECKOUT_PHARMACY_MERCHANT_SECRET`, `CHECKOUT_FASHION_MERCHANT_SECRET`; for the logins, seed data and account tools also
  `KC_OPS_TEST_PASSWORD`, `KC_OPS_TEST_TOTP_SECRET`, `KC_BOOTSTRAP_ADMIN_USERNAME`,
  `KC_BOOTSTRAP_ADMIN_PASSWORD`, `APISIX_ADMIN_KEY`, `BILLAGG_HMAC_SECRET`, `C2WBANK_HMAC_SECRET`,
  `USSD_{GP,ROBI,BL,TELETALK}_GATEWAY_SECRET`, `SOFTHSM_{MWCRYPTO,PAYSIM}_{LABEL,SO_PIN,USER_PIN}`,
  `VAULT_DEV_ROOT_TOKEN`, and `SEED_MANIFEST=/run/mw/seed/manifest.json`.
- Volume: mw-seeder's `seed-manifest` volume mounted read-only at `/run/mw/seed` (the manifest
  holds no secret; it is empty until the seed profile ran).
- APISIX `deploy/apisix/routes-test/test-console.json`: `/tester`, `/tester/*` to
  `mw-test-console:3000`, private source addresses only, read timeout 180 s (a flow with a status
  inquiry takes up to a minute). A test-only route: it exists only while `apisix-init` or
  `apisix-init-tester` (profile `tester`) ran with `MW_E2E_ROUTES=1` (`--profile tester up`,
  `scripts/tester.sh`, `scripts/api-test.sh`); an `up` without `--profile tester` removes it.
- Start order (compose): `mw-test-console` waits for `apisix-init-tester` and, when the seed profile
  is on, for `mw-seeder` to exit 0 (`required: false`), so the one-command start
  `docker compose --profile core --profile seed --profile tester up -d --build --wait` returns
  healthy and seeded. The account tools use the test-only routes `test-auth.json`, `test-kc-admin-token.json` and
  `test-kc-admin.json` (same conditions; `deploy/apisix/README.md`).

## Commands

| Command | What it does |
| --- | --- |
| `pnpm install` | dependencies (pinned, `pnpm-lock.yaml`) |
| `pnpm test` | `tsc --noEmit` + Vitest (`src/**/*.test.ts`: crypto, formats, redaction, headers, `env.optional`, TOTP window, token cache, seed manifest and catalogue, OTP lookup, account-tool lists, txn query, how-to (every page, every fill target), personas and role flows, app users, app flow options, app history; no stack) |
| from `deploy/`: `docker compose --profile core --profile seed --profile tester up -d --build --wait` | the whole stack in one command: builds this image (`mfs-mw/mw-test-console:local`) with the others, turns the test routes on, starts the console after the seed |
| `scripts/tester.sh` (repo root) | on a stack started without `--profile tester`: test routes on, start the container (never builds), print https://localhost/tester |
| `scripts/tester-e2e.sh [GREP]` (repo root) | Playwright (`e2e/*.spec.ts`, file order, one worker; tags `@smoke`, `@app`, `@ussd`, `@failures`, `@checkout`, `@bulk`, `@auth` (ticket 76: `e2e/60-auth.spec.ts`; also `e2e/85-account-tools.spec.ts`: a wallet locked by wrong PINs unlocked from the tools, a portal password reset), `@transactions` (`e2e/90-transactions.spec.ts`); `e2e/70-credentials.spec.ts` and `e2e/80-seed-data.spec.ts` are `@smoke` (a live TOTP code signs in, seeded rows with names, balances and PINs)) against the console on the running, seeded stack (from `deploy/`: `docker compose --profile core --profile seed --profile tester up -d --build --wait`); `E2E_BASE_URL` |
| `pnpm dev` | dev server on :3001 against the stack: `EDGE_URL=https://localhost`, `NODE_EXTRA_CA_CERTS=<file>` with the stack's self-signed edge certificate (`docker compose -f deploy/docker-compose.yml cp apisix:/usr/local/apisix/conf/cert/edge.crt build/edge.crt`; TLS verification stays on) plus the env values above (at least the five of the first group) and `SEED_MANIFEST` pointing at a copy of `build/seed/manifest.json`; then `E2E_BASE_URL=http://localhost:3001 pnpm test:e2e` |

The e2e specs check money with deltas (fee included: P2P Tk 5, top-up Tk 2) and reset the simulators
they change; they use the app users customer01 / customer02 / agent01, so a run spends about Tk 700
of customer01's Tk 15,000 daily debit limit. The account-tools spec locks and unlocks a fresh wallet of the auth page (found with the lookup) and
resets a portal user's password to the dev value; it never resets a pre-set TOTP.
