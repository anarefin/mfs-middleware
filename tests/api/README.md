# tests/api: the Newman API suite (ticket 58)

A Postman collection run with Newman against the running stack, through APISIX only. It replaced
the Cucumber suite of `tests/e2e` (`PARITY.md` maps every Cucumber scenario to its request) and
is modelled on BitsPay's `tools/postman`. No request needs Docker; only `run.sh` uses it, to
re-apply the test-only gateway routes.

```
deploy/reset.sh                     # fresh volumes; then, from deploy/, a stack on short timers (deploy/.env), seeded:
docker compose --profile core --profile seed up -d --build
scripts/api-test.sh                 # every folder except 90 Slow (and 99 Destructive, if one is added)
scripts/api-test.sh "01, 05"        # folders by number (or full name), comma separated
scripts/api-test.sh "" 1            # every folder, 90 Slow included
tests/api/run.sh --folder 01        # the runner itself (scripts/api-test.sh calls it)
```

Needs Node (npx) and access to cdnjs.cloudflare.com: Newman is `npx newman@6.2.1`, and the PIN
encryption (RSA-OAEP, node-forge 1.3.1) and the device signature (ECDSA P-256, elliptic 6.6.1) are
loaded into the Postman sandbox from cdnjs, each evaluated only when its SHA-256 matches the one
pinned in the collection (`build_collection.mjs`, `PINNED`).

## Files

| File | What |
| --- | --- |
| `build_collection.mjs` | generates `mfs-mw.postman_collection.json` and `local.postman_environment.json` from `src/` and `lib.js` (`node tests/api/build_collection.mjs`; `--check` fails when the committed files are stale, run by `./gradlew :contractsLint`) |
| `src/<NN-folder>/folder.json` | the folder's name (Cucumber `Feature:` line) and description |
| `src/<NN-folder>/*.js` | the folder's scenarios: `export default [{ name, run, args? }]`; optional `export const helpers = [fn]` (shared steps, copied into each script of the file) |
| `lib.js` | the shared script library, embedded as the collection variable `lib`: every script starts with `const MW = eval(pm.collectionVariables.get('lib'))` |
| `fixtures/*.json` | data embedded as the collection variable `fixtures` (`MW.fixture('main-e2e')`: the USSD main menu the admin-content scenario republishes) |
| `local.postman_environment.json` | the environment: `baseUrl` and the secret names (blank; run.sh fills them from `deploy/.env`) |
| `run_customer.mjs` | picks the run customer of a run (below) and records it in `build/api-test/run-customers.tsv` (`MW_API_RUN_LEDGER`); self-test `scripts/test/test_api_run_customer.sh` |
| `run.sh` | the runner (options in its header); run state in `.local.postman_environment.json` (gitignored, secrets blanked after every run; `MW_API_STATE`), JUnit report in `build/newman-report.xml` (`MW_API_REPORT`); `--folder` also takes one request by its name or a prefix only it has, so the four `90 Slow` requests can run in parallel, each with its own state and report and `MW_API_SKIP_ROUTES=1` after one run has applied the routes |
| `TRACEABILITY.md` | every request -> MVP acceptance criteria and coverage items, and the FRS UC × T1–T12 matrix (ticket 104); checked by `scripts/check-api-traceability.sh` |
| `PARITY.md` | every Cucumber scenario -> its Newman request, step-level changes noted |

## How a request works

One request per scenario. Its own request is the edge health check (`GET /healthz`); its test
script is the scenario: `run: async (MW, S, args) => { ... }` is serialized into the script, so it
may use only `MW`, `S`, `args` and plain JavaScript (no imports or module variables). Calls go
through `pm.sendRequest` (Newman waits for them; `--timeout-script` 15 min).

- `MW` (lib.js): `http`/`must` (edge client; `apikey: true` adds the test-only key-auth header),
  `poll(fn, until, timeoutMs, delayMs)`, `token(realm, user)` (Keycloak through APISIX, cached per user,
  refreshed with the refresh token; realm ops adds the RFC 6238 TOTP code, HmacSHA256 over the
  secret's UTF-8 bytes, and never reuses a 30 s step for one user), `app` (bound device per user:
  EC P-256 key, binding OTP read from the SMSC simulator, every submit signed), `encryptPin`,
  `core` (core-mock balances, faults), `sim` (simulators, SMSC, mail, USSD gateway, merchant IPN),
  `admin`, `portal`, `checkout` (APISIX hmac-auth signing, result and IPN verification),
  `callbacks` (billagg HMAC), `testApi` (the test-only orchestrator and audit-dw endpoints),
  `auth` (ticket 76: mw-auth's public API step by step: OTP per purpose with the code read from the
  SMSC simulator, binding, OTP or PIN login, refresh, logout, first PIN, PIN change, the resets; and
  `auth.fresh(S, pin)`, a fresh wallet of the scenario's own, below).
- `S` (one per request): the Cucumber steps as checks; each check is a `pm.test`, the first failing
  one stops the scenario, `S.after(fn)` clean-ups always run (core faults, simulator scenarios,
  partner restore). Money is always compared before/after (`S.note`, `S.changedBy`, `S.noChange`,
  `S.noHold`), never absolute; idempotency keys, references and markers are fresh per run, so a
  second `scripts/api-test.sh` run on the same stack passes without a reset.

## Repeated runs and the daily limit

Most scenarios pay from customer01 (01711000001), and one run spends about 4,000 BDT of the
15,000 BDT a core-mock wallet may debit per UTC day: on one wallet the 4th run of a day would fail
with `MW-CORE-LIMIT_EXCEEDED_DAILY`. So run.sh gives every run a **run customer** from mw-seeder's
pool (`seed-run-customer01..24`, MSISDN `01714NN0001`, 10,000,000 BDT each, persona `run-customer`
in the seed manifest, realm `mw-auth`: an app login, no Keycloak user): `run_customer.mjs` takes the one used least on this UTC day, at most 3 runs a
day each (12,000 BDT), and refuses with the fix when the pool is used up (72 runs a day). lib.js then
plays customer01 as it: `MW.forRun` swaps customer01's number (`1711000001`, so the `0...` and `880...`
forms) in the scenario source before it runs, so everything sent, signed and expected uses the run
customer, and `MW.loginUser` logs customer01 in at mw-auth as the run customer. Its number masks as
`0171****001` like customer01's, and mw-notify's dev contacts give it customer01's email address. A
run without `runCustomerMsisdn` (Newman by hand) plays customer01 itself. The 10,000-row payout
(folder 90) pays the real 01711000001, so it checks 01711000003 instead (01711000002 is paid by the folder's assertion scenario, which may run beside it).

**Fresh wallets (ticket 76).** The security folders 24-26 and two folder 90 scenarios bind second
devices, lock PINs, reset and change them: they never use a shared app user but `MW.auth.fresh`, one
of core-mock's seeded customers `01711000500..01711000999` that has no app PIN yet (mw-seeder sets one
only on its own wallets and customer01/02, agent01, merchant01/02). mw-auth tells: a `LOGIN` OTP goes
only to a wallet without a PIN, so `fresh` tries random ones until one answers 202, binds a new
device, logs in with the OTP and sets the first PIN. Each run uses up about a dozen of the 500 wallets, so
a stack takes some 40 runs before `deploy/reset.sh` is needed (the scenario then fails on "a fresh
wallet without an app PIN"). The edge's own quota on `/auth/v1/*` (20 per minute per wallet, 5 per
second per address) is waited out by `MW.http` (`MW-SYS-RATE_LIMITED` only; mw-auth's own 429 is an
answer).

## FRS harness (ticket 104)

`MW.mockScenario(party, op, behaviour, scope)`, `MW.mockSwitch(party, stateOrBody)`, `MW.mockReset(party)`, `MW.mockJournal(party, query)`, `MW.coreBalance(walletId)` and `MW.waitForState(txnId, states, timeoutMs)` are the helpers later use cases call. `scope` is an MSISDN, account or biller code, or `{code, key, nextN, delayMs, httpStatus}`. `mockReset(party)` puts that party's scenarios and switch back to normal and keeps its ledger; `mockReset()` with no party is `POST /mock-control/reset` (the ledger returns to the seed, so a scenario uses it only when it means to). `coreBalance` is the core wallet record (`balanceMinor`, `heldMinor`, `availableMinor`). `waitForState` polls the app status of the user that last submitted or confirmed, until the status is one of `states`.

Folder `30-saga-frs` reverses a completed top-up (ticket 96c): the operator reverse is in the OCS journal and the core reversal follows; a one-shot `BUSINESS_ERROR` on `ocs` `reverse` ends MANUAL_REVIEW and leaves the posting. Folder `41-p2p-frs` is P2P T1–T4 and T6–T10 (each scenario calls `mockReset('core')` first, the folder's pre-request reset). T5 waits out the inquiry schedule, so it is in `90-slow`. T11 and T12 stay `n/a` until tickets 125 and 127. T10's mid-saga kill is `scripts/chaos.sh` with `CHAOS_TARGETS=mw-orchestrator`; the request checks the ledger identity (debits equal credits) that the chaos run checks. Folder `46-topup-operators` (ticket 112c) is TOPUP T1–T4 and T6–T10 on `gp-ocs` and `robi-ocs`: T3 and T4 POST `/test/orchestrator/v1/inquiries` once the confirm has returned 202, and T8 waits until `GET /test/orchestrator/v1/partner-health/gp-ocs` says DOWN. Its T5 is skipped because it only passes by waiting out the inquiry schedule. Select that folder by its full name; `46` alone is the preview folder. The UC × T1–T12 matrix is in `TRACEABILITY.md`; a cell names a request, or `n/a` and a reason, and `scripts/check-api-traceability.sh` rejects a name the collection does not have.

Folder `55-bank-avl-w2b` (ticket 117e) links bank accounts with AVL (REST `bank-ebl` / `bank-dbbl`, SOAP `bank-sonali`; only the last 4 digits and the holder initial come back) and runs W2B T1-T4 and T6-T10 plus add money from a linked account against mw-simulators' bank gateway: T3 and T4 use the MCK-03 amount triggers `.77` (500 after the credit was applied) and `.99` (ambiguous, not applied) and drive the inquiry with `POST /test/orchestrator/v1/inquiries`; T7 and the linked add money point the bank's `callbackUrl` at the APISIX route `partner-bank-callback` (signed with `BANK_GATEWAY_HMAC_SECRET`). A wallet links at most 5 accounts, so the scenarios reuse the run customer's links.

Request names start with their requirement tags (`[p2p tsq] a transfer whose core reply is lost
...`), the Cucumber flow tags; `TRACEABILITY.md` lists the vocabulary and the rule.

## What the suite reaches through APISIX

| What | Path | Notes |
| --- | --- | --- |
| App API | `/app/v1/*` | real route; mw-auth token (MSISDN + app PIN `MW_SEED_APP_PIN` + bound device, `/auth/v1/*`): `customer01`, `agent01`, `merchant01`, ... on core-mock's seed wallets |
| Org portal, admin API, checkout, partner callbacks, USSD | `/portal/org/v1/*`, `/admin/v1/*`, `/checkout/*`, `/partner/*`, `/ussd/*` | real routes |
| Tokens | `/auth/realms/portals/...`; `/auth/realms/ops/...` (test-only, key-auth) | both realms demand TOTP: portals users (`org-maker01`, ...) with the pre-set `KC_PORTALS_TEST_TOTP_SECRET`, ops: dev-only client `mw-e2e-ops`, users `ops-e2e-maker01` / `ops-e2e-checker01` with `KC_OPS_TEST_TOTP_SECRET` (ticket 73) |
| core-mock test API | `/test/core-mock/v1/*` | test-only (`MW_E2E_ROUTES=1`), key-auth `MW_E2E_API_KEY` |
| simulators, mail | `/test/sim/*`, `/test/mail/*` | test-only, key-auth |
| mock control | `/test/mock-control/*` | test-only, key-auth: mw-simulators' one control API (ADR D33, tickets 102a/b); `lib.js` `MW.mock` (scenario, switchTo, settings, journal, reset, normal), the old `MW.sim.scenario` / `smppScenario` / `ipnFail` bodies are translated onto it; folder 39; the file mocks (ticket 123: `/{party}/files`, `/{party}/run`) folder 44 |
| REVERSAL, handled request, driven inquiry | `/test/orchestrator/v1/reversals`, `/test/orchestrator/v1/txn-requests/{key}`, `/test/orchestrator/v1/inquiries`, `/test/orchestrator/v1/partner-health/{partnerId}` | test-only mw-orchestrator API (`contracts/api/mw-orchestrator/orchestrator-test.v1.openapi.yaml`); replaces kafka-console-producer and psql |
| payShield test API | `/test/crypto/v1/payshield/*` | test-only mw-crypto API (`contracts/api/mw-crypto/crypto-test.v1.openapi.yaml`, ticket 76): a PIN key's LMK blob for `/test/sim/payshield/pinblock`, PVV generate / verify as the test-only client `mw-e2e-crypto`; only with `MW_CRYPTO_TEST_API_ENABLED=true` (dev) |
| batch audit, hash chain | `/test/audit-dw/v1/audit/events`, `/test/audit-dw/v1/audit/chains/{month}` | test-only mw-audit-dw API (`contracts/api/mw-audit-dw/audit-dw-test.v1.openapi.yaml`); a transaction's audit trail is read from the admin API detail |

Folder 00 checks the edge, the test routes and the mw-seeder baseline (seeded personas sign in,
seeded wallets exist, partner `ocs` at its seed). The scenarios themselves use the realm import's
users and core-mock's own seed wallets, as the Cucumber suite did, and make their own data on top.

## Timing

The default run is sized for a stack started with the test timers of `deploy/.env` (the test timers block copied
from `deploy/dev.env.defaults`; Compose reads `deploy/.env` by itself): core command
deadlines 2 s, first status inquiry after 6 s, app / checkout reply budget 7 s (so a lost core
reply is still answered 202 and resolved by the inquiry), partner limits 4 s (ocs through the
mw-seeder baseline, billagg / c2wbank in the adapters) with the simulators' timeout hold at 7 s,
partner deadline 8 s. The same file gives `mw-seeder` the short ocs limit
(`MW_SEED_OCS_TIMEOUT_MS` in `deploy/dev.env.defaults`). run.sh passes the seeded ocs limit (`build/seed/manifest.json`) to
the scenarios, which size their partner delays from it, so the suite also runs (slower) on a stack
with production timers. Each request logs `scenario took N s`. Folder 90 holds what stays slow (the
10,000-row payout; the retry-to-DLQ request, some 11 min with production retry delays; ticket 76: the PIN lockout escalation to level 3, about 4.5 min with the dev lock timers, and the assertion expiry after its 60 s life). APISIX re-resolves service names every 5 s (`dns_resolver_valid`), so a
recreated container is reached at its new address.
