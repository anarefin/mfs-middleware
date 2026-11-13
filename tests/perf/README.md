# tests/perf: Gatling 100 TPS mixed load (spec WP28, MVP acceptance criterion 7)

Its own standalone Gradle build (wrapper, `gradle/libs.versions.toml`, Gatling Gradle plugin, Java
DSL), never included in the root build (so `./gradlew clean build` at the root does not touch it); never
depends on a service module and shares no code with `tests/api` (the few helpers it needs were
copied from the former Cucumber suite). It runs on the host against a running MVP stack, through APISIX like the API suite.

Criterion 7: the single MVP host handles at least **100 TPS** of mixed load with **p95 middleware
overhead under 150 ms**, overhead measured excluding core-mock and simulator time; no errors
other than intended business failures.

## Running it (the milestone gate)

```
./gradlew bootJar                     # from the repo root: the service jars
deploy/reset.sh                       # fresh stack (balances, daily limits, Keycloak users)
(cd deploy && docker compose --env-file .env --profile core --profile seed up -d --build)
scripts/suite.sh perf                 # setup (~1-2 min), 60 s ramp, 5 min at 102 TPS, report
```

`scripts/suite.sh perf` re-applies the test-only gateway routes (apisix-init with
`MW_E2E_ROUTES=1`, as `scripts/api-test.sh`), runs `./gradlew gatlingRun` in `tests/perf`, then always
`./gradlew perfOverhead`, and fails when either failed. Extra settings:
`MW_PERF_ARGS="-Dmw.perf.steady=10m" scripts/suite.sh perf`. A module name as the second argument is
accepted but the mix always covers the whole stack.

| Setting (`-D`) | Default | Meaning |
| --- | --- | --- |
| `mw.perf.tps` | 102 | injected transactions/s in the steady phase (2 % over the target so the window count is not short by rounding) |
| `mw.perf.target-tps` / `mw.perf.target-p95-ms` | 100 / 150 | criterion 7 |
| `mw.perf.ramp` / `mw.perf.steady` | 60s / 5m | ramp from 0, then constant arrivals |
| `mw.perf.mix` | the MVP mix below | `FLOW=weight,...` (unlisted flows do not run) |
| `mw.perf.status-share` | 0.2 | share of app transactions read back with `GET /app/v1/transactions/{txnId}` |
| `mw.perf.customers` / `agents` / `ussd-wallets` | 200 / 30 / 100 | user pools |
| `mw.perf.base-url` (or `MW_PERF_BASE_URL`) | APISIX's published 9443 | edge URL; `COMPOSE_PROJECT_NAME` for per-worktree stacks |
| `mw.perf.report-only` | false | `perfOverhead` writes the report but does not fail |

Offline checks (no stack): `cd tests/perf && ./gradlew test compileGatlingJava`.

## Where the results are

- Gatling HTML report: `tests/perf/build/reports/gatling/mixedloadsimulation-<timestamp>/index.html`
  (per request name: the flows, `BILL_FETCH`, `STATUS`; end-to-end response times; KO count). The
  run's assertion: zero KO requests.
- Overhead report: `tests/perf/build/reports/perf/overhead.md` and `overhead.json` (achieved TPS in
  the steady window, overhead p50/p95/p99 overall and per flow, end-to-end p95, USSD session p95,
  unexpected failures by status and code, diagnostics), from `txns.csv` (one line per
  transaction) and `run.properties` (steady window, targets) in the same folder.

Paste `overhead.md` and the Gatling global table into the gate notes / ticket 45. A `GAP` result is
a finding to document (what missed, the diagnostics), not a broken suite.

## Scenario mix (100 TPS)

| Flow | Share | What one transaction does |
| --- | --- | --- |
| P2P | 30 % | perf customer -> next perf customer, 10 Tk (fee 5) |
| CASH_IN | 10 % | perf agent -> perf customer, 10 Tk (agent app) |
| CASH_OUT | 10 % | perf customer -> perf agent, 10 Tk (fee 10) |
| MERCHANT_PAY | 13 % | perf customer -> merchant 01911000003..010, 10 Tk |
| TOPUP | 13 % | own number, gp PREPAID, 10 Tk (hold, OCS, commit) |
| TOPUP_REFUSED | 2 % | number 01799000999, refused by the OCS simulator: 502 `MW-PTR-GP-TOPUP_REJECTED` (the intended business failure; hold released) |
| BILL_PAY | 10 % | `BILL_FETCH` then pay the amount due of a DESCO bill ending 001..050 (101-150 Tk, never a magic suffix) |
| ADD_MONEY_BANK | 7 % | 500 Tk from a linked c2wbank account into the customer's wallet |
| USSD_P2P | 5 % | robi USSD session played by the USSD gateway simulator (`/test/sim/ussdgw/sessions`): English, Send Money 10 Tk to a perf customer |

Every app submit is a real app request: customers-realm token, bound device, signed body with
timestamp and nonce, fresh `Idempotency-Key`, PIN `1234` encrypted with the core's key, and the
synchronous answer awaited (200 COMPLETED, or the intended 502). Amounts are whole taka, so no
simulator magic fraction is hit. Open model: arrivals per second, one transaction per virtual user,
one shared keep-alive connection pool (the run measures the middleware, not a TLS handshake per
transaction).

## Users and wallets

Setup (`stack/Provisioning`, in the simulation's `before`) creates the perf users in realm
`customers` with a Keycloak partial import (`docker compose exec keycloak kcadm.sh`, `SKIP` existing,
password `KC_CUSTOMERS_TEST_PASSWORD`): `perf-c001..perf-c200` on wallets `01711000101..300`,
`perf-a01..perf-a30` on agents `01811000011..040`; USSD customers `01711000301..400` need no user.
None is a wallet the e2e suite uses. It then fetches every token (renewed in the background), and
binds one new device per user with the binding OTP read from the SMSC simulator (about 230 bindings,
16 at a time). Pools are sized for APISIX's quotas: 120 app requests/min per token subject, 120 USSD
hops/min per MSISDN.

Balances and limits (default run, about 33,700 transactions): a customer is debited about 3,800 Tk
and credited about 6,500 Tk (P2P in, cash-in, add money), against a 10,000 Tk seed balance and the
15,000 Tk daily debit limit of mw-core-mock; an agent is debited about 1,100 Tk. So about four runs
a day fit one stack; start the gate run on a fresh stack anyway.

## How the overhead is measured

Per app transaction: **overhead = submit response time - core time - partner time** (never below
0), then nearest-rank percentiles (`report/Overhead`). The two subtracted parts come from read-only
psql queries after the run (`report/RunFacts`, as the e2e suite reads the audit trail):

- core time: for every reply mw-core-mock wrote to its outbox (`coremock.outbox`, key = txnId),
  `sent_at - created_at`, i.e. from the start of the core's database transaction for the command
  to the relay's send: the mock's processing plus its own 500 ms-period outbox relay. Kafka transit
  to and from the core stays in the overhead (conservative).
- partner time: `durationMs` of every `partner.call.completed` audit event (adapter -> simulator
  HTTP call; the report waits until mw-audit-dw has stored them).

USSD sessions (seven screens, simulator in the loop) are reported apart and not part of the
overhead percentiles; their transactions count towards the rate. The rate is the transactions that
started in the steady window divided by its length.

## What to look at when the report says GAP

- Diagnostics in `overhead.md`: p95/max outbox relay wait of mw-orchestrator (`txn.outbox`),
  mw-app-gateway and mw-adapter-telco. Every relay polls every 500 ms once its outbox is drained
  (`mw.outbox.relay.period`), and a saga crosses the orchestrator's outbox at least twice (core
  command, status to the gateway), so up to about 1 s of relay wait per transaction is expected at
  this rate: the first suspect for a p95 over 150 ms (fix in the services: a shorter period or a
  wake-up after commit, not in this suite).
- The stack (`deploy/docker-compose.yml`) runs with low-resource limits
  (about 9.6 GB of limits, functional runs only): CPU or memory caps there can cap the rate.
- KOs: a 202 `PROCESSING` (outcome not known within the gateway's 25 s budget), 429 (an APISIX
  quota: raise the pools), 401 `MW-AUTH-007` (a device session that idled out).
