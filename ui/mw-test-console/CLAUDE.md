# mw-test-console

Test console (ADR D17): **dev/test only**. A Next.js 16.4.1 app (App Router, React 19, TypeScript
strict, pnpm) that plays every outside party of the middleware against the local stack, so each
feature can be tested by hand from a browser: the mobile app (customer, agent, merchant), a USSD
handset, the partners (OCS, bill aggregator, C2W bank: their simulators), the checkout merchant and
payer, and the org portal. The step-by-step guide that uses it is `docs/testing-guide.html`. Ops
features stay in the admin console (`ui/mw-admin-ui`, `/console`).

A UI module, not a Gradle build (like mw-admin-ui): the Gradle scripts do not see it. Nothing outside
this folder is referenced. It is never deployed outside a laptop / test stack: it impersonates
customers, holds the stack's generated test credentials and has no sign-in.

## How it works

- Every call goes through APISIX (`EDGE_URL`, in the stack `http://apisix:9080`), server-side only:
  server actions in `src/app/actions/*.ts` (`"use server"`) call `src/lib/server/*` (`import
  "server-only"`). The browser never sees a token, key or secret: APISIX has no CORS, the test routes
  need `MW_E2E_API_KEY`, and checkout hmac-auth needs the merchant secret and a `Date` header.
- `lib/server/edge.ts`: the edge client (fresh `X-Correlation-Id`, `apikey` for `/test/*`, no
  redirects followed) and `Trace`, which records each call as a `Step` (label, call, HTTP status,
  answer redacted by `lib/redact.ts`, ms). Every action returns an `Outcome` (`lib/types.ts`): the
  page (`app/components/result.tsx`) shows the headline, final status, core balances before/after,
  the SMS received and every step, expandable.
- `lib/server/tokens.ts`: Keycloak password grant through APISIX (`customers`/`mw-app`,
  `portals`/`mw-portal`), cached per user, refreshed. `lib/server/device.ts`: the app's bound device
  per user (EC P-256 key; OTP via `POST /app/v1/devices/otp`, read from the SMSC simulator; `POST
  /app/v1/devices`; `POST /app/v1/sessions` after 4 min idle; signature over method, path, body hash,
  timestamp, nonce, device id). `lib/crypto.ts` (pure, Node crypto): signing, RSA-OAEP-SHA256 PIN
  encryption with the core key from `/test/core-mock/v1/pin-public-key`, checkout hmac-auth, result
  and IPN checks; ported from `tests/api/lib.js`. State (tokens, devices, last submit, PIN key) is in
  process memory (`globalThis`): a restart forgets it and the next submit binds again. Binding here
  replaces the API suite's device for that user (one active device per wallet).
- Pages: `/` (stack checks, seeded balances, reset), `/app` (bind, the seven app flows, resend with
  the same Idempotency-Key, billers / fetch bill / status / merchant collections; `FEE-POOL` shown as
  the fee), `/ussd` (handset: robi over HTTP resumes the open session per key press; gp over SMPP
  replays the session from the dial with every input so far, as the simulator cannot resume SMPP;
  inputs on a PIN screen show as ****), `/simulators` (presets in `lib/presets.ts`, per-partner
  scenario forms, recorded requests, callback replay, core-mock faults, wallet lookup, SMPP binds /
  drop / throttle), `/inbox` (SMSC and GreenMail), `/checkout` (create, page, OTP, pay, signed
  redirect, IPNs), `/bulk` (sample CSV, upload, approve / reject / pause / resume, status, result,
  transactions, SP query). Personas: `lib/personas.ts` (the realm imports' users; any other user can
  be typed in).
- An add money from the bank first sets c2wbank callbacks ONCE when the simulator has none (a step
  says so), as every API suite scenario does.
- `src/proxy.ts`: per-request CSP nonce (`lib/security-headers.ts`), `cache-control: no-store`;
  `next.config.ts`: basePath `/tester`, standalone output, static security headers, `agentRules: false`.

## Deploy

- `Dockerfile`: standalone output, node 24 alpine, uid 10001, npm/npx/corepack removed (as mw-admin-ui).
- `deploy/docker-compose.yml` service `mw-test-console`, profile `tester` (not `core`: outside
  `make compose-check`'s budget, about 192 MiB), network `app`, read-only root. Env: `EDGE_URL`,
  `PUBLIC_URL` (links to `/console`), `MW_E2E_API_KEY`, `KC_CUSTOMERS_TEST_PASSWORD`,
  `KC_PORTALS_TEST_PASSWORD`, `CHECKOUT_DEMO_MERCHANT_SECRET` from `deploy/.env`.
- APISIX `deploy/apisix/routes-test/test-console.json`: `/tester`, `/tester/*` to
  `mw-test-console:3000`, private source addresses only, read timeout 180 s (a flow with a status
  inquiry takes up to a minute). A test-only route: it exists only while `apisix-init` ran with
  `MW_E2E_ROUTES=1` (`make tester`, `make api-test`); a plain `make up` removes it.

## Commands

| Command | What it does |
| --- | --- |
| `pnpm install` | dependencies (pinned, `pnpm-lock.yaml`) |
| `pnpm test` | `tsc --noEmit` + Vitest (`src/**/*.test.ts`: crypto, formats, redaction, headers; no stack) |
| `make build M=mw-test-console` (repo root) | the image `mfs-mw/mw-test-console:local` (`make build` builds it with the rest) |
| `make tester` (repo root) | test routes on, start the container, print https://localhost/tester |
| `make tester-e2e [GREP=]` (repo root) | Playwright (`e2e/*.spec.ts`, file order, one worker) against the console on the running, seeded stack (`make up TIMERS=fast && make seed && make tester`); `E2E_BASE_URL` |
| `pnpm dev` | dev server on :3001 against the stack: `EDGE_URL=https://localhost`, `NODE_EXTRA_CA_CERTS=<file>` with the stack's self-signed edge certificate (`docker compose -f deploy/docker-compose.yml cp apisix:/usr/local/apisix/conf/cert/edge.crt build/edge.crt`; TLS verification stays on) plus the four credentials above in the environment; then `E2E_BASE_URL=http://localhost:3001 pnpm test:e2e` |

The e2e specs check money with deltas (fee included: P2P Tk 5, top-up Tk 2) and reset the simulators
they change; they use the realm users customer01 / customer02 / agent01, so a run spends about Tk 700
of customer01's Tk 15,000 daily debit limit.
