# tests/api: the Newman API suite (ticket 58)

A Postman collection run with Newman against the running stack, through APISIX only. It replaced
the Cucumber suite of `tests/e2e` (`PARITY.md` maps every Cucumber scenario to its request) and
is modelled on BitsPay's `tools/postman`. No request needs Docker; only `run.sh` uses it, to
re-apply the test-only gateway routes.

```
make reset && make build && make up TIMERS=fast && make seed   # a fresh stack on short timers, seeded
make api-test                       # every folder except 90 Slow (and 99 Destructive, if one is added)
make api-test FOLDER="01, 05"       # folders by number (or full name), comma separated
make api-test ALL=1                 # every folder, 90 Slow included
tests/api/run.sh --folder 01        # the same without make
```

Needs Node (npx) and access to cdnjs.cloudflare.com: Newman is `npx newman@6.2.1`, and the PIN
encryption (RSA-OAEP, node-forge 1.3.1) and the device signature (ECDSA P-256, elliptic 6.6.1) are
loaded into the Postman sandbox from cdnjs, each evaluated only when its SHA-256 matches the one
pinned in the collection (`build_collection.mjs`, `PINNED`).

## Files

| File | What |
| --- | --- |
| `build_collection.mjs` | generates `mfs-mw.postman_collection.json` and `local.postman_environment.json` from `src/` and `lib.js` (`node tests/api/build_collection.mjs`; `--check` fails when the committed files are stale, run by `make contracts-lint`) |
| `src/<NN-folder>/folder.json` | the folder's name (Cucumber `Feature:` line) and description |
| `src/<NN-folder>/*.js` | the folder's scenarios: `export default [{ name, run, args? }]`; optional `export const helpers = [fn]` (shared steps, copied into each script of the file) |
| `lib.js` | the shared script library, embedded as the collection variable `lib`: every script starts with `const MW = eval(pm.collectionVariables.get('lib'))` |
| `fixtures/*.json` | data embedded as the collection variable `fixtures` (`MW.fixture('main-e2e')`: the USSD main menu the admin-content scenario republishes) |
| `local.postman_environment.json` | the environment: `baseUrl` and the secret names (blank; run.sh fills them from `deploy/.env`) |
| `run.sh` | the runner (options in its header); run state in `.local.postman_environment.json` (gitignored, secrets blanked after every run), JUnit report in `build/newman-report.xml` |
| `TRACEABILITY.md` | every request -> MVP acceptance criteria and coverage items; checked by `scripts/check-api-traceability.sh` |
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
  `callbacks` (billagg HMAC), `testApi` (the test-only orchestrator and audit-dw endpoints).
- `S` (one per request): the Cucumber steps as checks; each check is a `pm.test`, the first failing
  one stops the scenario, `S.after(fn)` clean-ups always run (core faults, simulator scenarios,
  partner restore). Money is always compared before/after (`S.note`, `S.changedBy`, `S.noChange`,
  `S.noHold`), never absolute; idempotency keys, references and markers are fresh per run, so a
  second `make api-test` on the same stack passes without a reset.

Request names start with their requirement tags (`[p2p tsq] a transfer whose core reply is lost
...`), the Cucumber flow tags; `TRACEABILITY.md` lists the vocabulary and the rule.

## What the suite reaches through APISIX

| What | Path | Notes |
| --- | --- | --- |
| App API | `/app/v1/*` | real route; customers-realm token (`customer01`, `agent01`, `merchant01`, ... of the realm import) |
| Org portal, admin API, checkout, partner callbacks, USSD | `/portal/org/v1/*`, `/admin/v1/*`, `/checkout/*`, `/partner/*`, `/ussd/*` | real routes |
| Tokens | `/auth/realms/{customers,portals}/...`; `/auth/realms/ops/...` (test-only, key-auth) | ops: dev-only client `mw-e2e-ops`, users `ops-e2e-maker01` / `ops-e2e-checker01` |
| core-mock test API | `/test/core-mock/v1/*` | test-only (`MW_E2E_ROUTES=1`), key-auth `MW_E2E_API_KEY` |
| simulators, mail | `/test/sim/*`, `/test/mail/*` | test-only, key-auth |
| REVERSAL, handled request | `/test/orchestrator/v1/reversals`, `/test/orchestrator/v1/txn-requests/{key}` | test-only mw-orchestrator API (`contracts/api/mw-orchestrator/orchestrator-test.v1.openapi.yaml`); replaces kafka-console-producer and psql |
| batch audit, hash chain | `/test/audit-dw/v1/audit/events`, `/test/audit-dw/v1/audit/chains/{month}` | test-only mw-audit-dw API (`contracts/api/mw-audit-dw/audit-dw-test.v1.openapi.yaml`); a transaction's audit trail is read from the admin API detail |

Folder 00 checks the edge, the test routes and the mw-seeder baseline (seeded personas sign in,
seeded wallets exist, partner `ocs` at its seed). The scenarios themselves use the realm import's
users and core-mock's own seed wallets, as the Cucumber suite did, and make their own data on top.

## Timing

The default run is sized for `make up TIMERS=fast` (`deploy/test-timers.env`): core command
deadlines 2 s, first status inquiry after 6 s, app / checkout reply budget 7 s (so a lost core
reply is still answered 202 and resolved by the inquiry), partner limits 4 s (ocs through the
mw-seeder baseline, billagg / c2wbank in the adapters) with the simulators' timeout hold at 7 s,
partner deadline 8 s. `make up TIMERS=fast` records the timers in `build/stack-timers`, so a
later `make seed` keeps them. run.sh passes the seeded ocs limit (`build/seed/manifest.json`) to
the scenarios, which size their partner delays from it, so the suite also runs (slower) on a stack
with production timers. Each request logs `scenario took N s`. Folder 90 holds what stays slow (the
10,000-row payout). APISIX re-resolves service names every 5 s (`dns_resolver_valid`), so a
recreated container is reached at its new address.
