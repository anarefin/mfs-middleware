# D17: A dev/test-only test console for manual testing

Status: Accepted, 2026-11-07. Amended 2026-11-15: the console shows the dev secrets, reads the seed
manifest and offers account tools through test-only routes (below). Amended 2026-11-16: every ops
user has the pre-set one-time code, usernames are copyable, no Reset TOTP tool (below). Amended 2026-11-16: the tester profile starts the
console with the whole stack in one command and turns the test-only routes on by itself (below).
Amended 2026-11-19 by [D30](D30-hashicorp-vault.md): HashiCorp Vault replaces OpenBao (ticket 91); the
OpenBao names below describe the stack before it.

## Context

The middleware has no customer-facing UI: the mobile app, the USSD handsets, the merchants' web
shops, the banks, MNOs and billers are all outside it, and on the laptop stack the partners and the
core are simulators. Every feature could be checked only by the Newman suite (then `make api-test`) or by
hand-written API calls, which need the device signature (EC P-256), RSA-OAEP PIN encryption, Keycloak
tokens, hmac-auth and the test-only key. Testers without that knowledge could not check a flow by
hand, see each step, or switch a partner to fail. The ops console covers only the operations side.

A browser page cannot call the APIs itself: APISIX grants no CORS, the test-only routes need
`MW_E2E_API_KEY`, and the checkout merchant's hmac-auth needs its secret and a `Date` header that
`fetch` may not set.

## Decision

- A separate UI module, `ui/mw-test-console` (Next.js, the admin console's stack), served by APISIX
  at `/tester`. It plays the app (customer, agent, merchant), a USSD handset (robi over HTTP, gp
  over SMPP through the USSD gateway simulator), the partner simulators and core-mock faults, the
  checkout shop and buyer, the org portal, and the SMS / email inboxes. Each action shows every API
  call it made, the final status, the core balances before and after, and the SMS received.
- Every call goes through APISIX, server-side (server actions), with the stack's generated test
  credentials from `deploy/.env`; no token, key or secret reaches the browser. The client
  cryptography is a port of `tests/api/lib.js` on Node's crypto.
- Dev/test only: compose profile `tester` (outside the core profile and its memory budget), and its
  APISIX route is a test-only route (`deploy/apisix/routes-test/test-console.json`, private source
  addresses), present only while `apisix-init` ran with `MW_E2E_ROUTES=1` (`scripts/tester.sh`, originally `make tester`); a
  plain `docker compose up` removes it. It has no sign-in.
- Ops features are not repeated: they stay in the admin console; the test console links each
  transaction to it. `docs/testing-guide.html` walks through every feature with both.

## Consequences

- A tester checks every MVP flow, its failure paths (timeouts, TSQ, UNKNOWN, MANUAL_REVIEW,
  callbacks) and the ops actions from a browser, with no API tooling.
- One more UI module to keep working: its own `pnpm test` (no stack) and `scripts/tester-e2e.sh`
  (Playwright against the running stack). It follows API changes only through the contracts it
  calls, like any client.
- It impersonates customers and holds test credentials: it must never be started on a shared or
  production host. The profile, the test-only route and the README / runbook notes say so.
- Binding a device in the console replaces that user's device from an API suite run (one active
  device per wallet); the suite binds again on its next run.

## Amendment 2026-11-15: dev secrets, seed data and account tools in the console

**Context.** Testers still needed a terminal for most sign-ins: `scripts/totp.sh` for every one-time
code, `scripts/creds.sh` for the keys, `kcadm.sh` in the Keycloak container to unlock a user or reset a
password or a code, and hand-made API calls to see what mw-seeder created. The seeded people
had formulaic names ("Seed Maker"), so screens and SMS were hard to tell apart. The original decision
kept every secret away from the browser, which made these tasks impossible in the console.

**Decision.**

- New page `/tester/credentials` ("Logins & secrets"): every ops and portal login with its password
  and a live TOTP code (computed server side from the pre-set secret, with a countdown), the app PIN
  and a wrong PIN, a PIN to ciphertext helper, the latest OTP an MSISDN received, and the platform
  secrets (Keycloak admin, the OpenBao tester token `OPENBAO_DEV_ROOT_TOKEN`, the APISIX keys, the
  partner HMAC secrets, the USSD gateway passwords, the SoftHSM PINs). These are exactly the fixed fake
  values of `deploy/dev.env.defaults` (ADR D9 amendment 2026-11-13), passed to the container by its
  compose block; this deliberately relaxes "no secret reaches the browser" for them, and only for them.
  Tokens, device keys and anything not in `deploy/dev.env.defaults` still stay server side.
- New page `/tester/seed-data`: mw-seeder's manifest (the `seed-manifest` volume mounted read-only at
  `/run/mw/seed`, env `SEED_MANIFEST`) with the logins resolved inline per row and live balances. The
  manifest itself still holds no secret: it names the env variables, the console resolves them.
- Account tools on its rows, a fixed list of actions (no free-form proxy): wallets Status, Unlock,
  Reset PIN, Unbind, Set balance (mw-auth's dev-only account test API `/test/v1/accounts/{walletId}`,
  `contracts/api/mw-auth/auth-test.v1.openapi.yaml`, and core-mock's seed PUT); ops and portal users
  Unlock, Reset password, Reset TOTP (the Keycloak admin API as the bootstrap admin, realms `ops` and
  `portals` only). They go through the test-only routes `test-auth`, `test-kc-admin-token` and
  `test-kc-admin` (`deploy/apisix/routes-test/`, `apikey` required), which exist only while
  `apisix-init` ran with `MW_E2E_ROUTES=1`.
- New page `/tester/transactions`: a read-only transaction explorer through mw-admin-api as the
  operator `ops-e2e-maker01` (MSISDNs stay masked); every txnId in a result links there. Ops actions
  stay in the admin console.
- Seeded users and wallets get fake Bangladeshi display names (Keycloak first and last names, core-mock
  `holderName`); usernames and MSISDNs are unchanged.

**Consequences.**

- A tester signs in to both consoles, fixes a locked user or wallet and follows a transaction without a
  terminal; `scripts/creds.sh`, `scripts/totp.sh` and kcadm stay for terminal users and as a fallback.
- The console now displays credentials and can change accounts: it must **never** run anywhere but a
  laptop / dev test stack, and never with real values in `deploy/.env`. The `tester` profile, the
  private-source test-only routes and the README / runbook notes keep saying so.
- Reset TOTP cannot restore a pre-set secret (the Keycloak admin API cannot set one): after it that
  user has no live code and the scripted suites cannot sign in as it until `deploy/reset.sh`.
- The realm-import names show only on a fresh Keycloak volume (`deploy/reset.sh`).
- No value may be logged by the console; `scripts/log-scan.sh` keeps checking the container logs.

## Amendment 2026-11-16: a pre-set one-time code for every ops user, no Reset TOTP

**Context.** The realm import's `ops-*01` users had no OTP credential and the `CONFIGURE_TOTP` required
action: a tester needed an authenticator app (FreeOTP or Aegis, SHA-256) for them, while every other
ops and portal login had a live code in the console. Reset TOTP could only delete a code, never put the
pre-set secret back.

**Decision.**

- The ops realm import gives the `ops-*01` users the same pre-set OTP credential as `ops-e2e-*`
  (`KC_OPS_TEST_TOTP_SECRET`, HmacSHA256, 6 digits, 30 s) and no required action; mw-seeder lists
  them with `preset-totp: true`, so the manifest names the secret and both console pages show their
  live code. `scripts/totp.sh` prints it too. Nobody enrols an authenticator app on the dev stack.
- Every username on Logins & secrets and Seed data has a copy button, like the password.
- The Reset TOTP account tool is removed; Unlock and Reset password stay.

**Consequences.**

- The change shows on a fresh Keycloak volume only (`deploy/reset.sh`), as any realm-import change.
- The superseded consequence above (Reset TOTP leaves a user without a live code) no longer applies.

## Amendment 2026-11-16: one command starts everything, the tester profile included

Starting the stack for manual testing took four steps: `up` with core and seed, `wait mw-seeder`,
`--profile tester build mw-test-console` and `scripts/tester.sh` (the test-only routes). Now one
command does all of it, from `deploy/`:

    docker compose --profile core --profile seed --profile tester up -d --build --wait

- The `tester` profile also has a one-shot, `apisix-init-tester`: `apisix-init` once more, with
  `MW_E2E_ROUTES=1`, after `apisix-init`. The test-only routes (and `/tester`) are on whenever the
  profile is up; an `up` without the profile runs `apisix-init` alone, which removes them, as before.
  The default of `apisix-init` stays `MW_E2E_ROUTES=0`, so the non-tester edge is unchanged.
- `mw-test-console` waits for `apisix-init-tester` and for `mw-seeder` to exit 0 (`required: false`:
  the tester profile still works without seed). With that dependent, `up --wait` counts the seeder
  in (a one-shot nothing depends on fails `--wait`), so the command returns healthy and seeded.
- `--build` builds the console image with the others; `scripts/tester.sh` stays, for a stack started
  without the tester profile.
