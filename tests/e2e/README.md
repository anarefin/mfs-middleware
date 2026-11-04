# tests/e2e: black-box Cucumber suite (spec WP26)

Its own Gradle build (wrapper, `gradle/libs.versions.toml`); never depends on a service module.
It runs on the host against a running MVP stack and talks to it over the network only.

```
make up                       # the stack (fresh: make down DOWN_ARGS=-v first)
make e2e TAGS=@p2p            # every @p2p scenario
make e2e TAGS=@cash           # agent01 cash-in, customer01 cash-out to agent01
make e2e M=mw-audit-dw TAGS=@p2p   # @mw-audit-dw and (@p2p)
```

Per-worktree stacks: export the same `COMPOSE_PROJECT_NAME` (and `MW_HTTPS_PORT=0`,
`MW_OPS_SUBNET`) for `make up` and `make e2e`; the suite finds the APISIX port with
`docker compose port apisix 9443`. Override with `-Dmw.e2e.base-url=` / `MW_E2E_BASE_URL`.

## How the suite reaches the stack

Only APISIX is published, so every call goes through it:

| What | Path | Notes |
| --- | --- | --- |
| App API | `/app/v1/*` | the real channel route (`deploy/apisix/routes/app-v1.json`) |
| Tokens (realm customers) | `POST /auth/realms/customers/protocol/openid-connect/token` | `auth-customers-token.json`; pins Host/X-Forwarded-* to `keycloak:8080` so `iss` = `http://keycloak:8080/realms/customers`, the issuer APISIX's openid-connect discovery expects. Password = `KC_CUSTOMERS_TEST_PASSWORD` from the generated `deploy/compose/platform/.env` |
| core-mock test/admin API | `/test/core-mock/v1/*` -> `mw-core-mock:8080/core-mock/v1/*` | `deploy/apisix/routes-test/test-core-mock.json`, TEST ONLY: PIN public key, `wallets/{id}` balances, `faults`. Present only after `make e2e` re-ran `apisix-init` with `MW_E2E_ROUTES=1` (a plain `make up` removes it); key-auth header `apikey` = `MW_E2E_API_KEY` from the generated `.env`, private source addresses only |
| Audit rows | `docker compose exec -T postgres psql` (superuser, read-only queries) | no API exposes `audit.event` before mw-admin-api (WP23), and the service role cannot read it |

## Reuse in later flows (TOPUP, cash-in/out, bill pay, ...)

- `support/AppApi`: `request(txnType, creditParty, amountMinor, pin)` (add flow fields to the
  returned node), `submit`, `status`, `pollStatus`, `newIdempotencyKey()`, `FINAL`.
- `support/CoreMock`: `encryptPin` (RSA-OAEP SHA-256 with the core key), `balance`,
  `injectFault` / `resetFaults` (reset in an `@After` hook of the scenarios that inject).
- `support/Keycloak.customerToken(user)`; `support/AuditTrail.rowsOfTxn` / `verifyChain`;
  `support/Waits.until` for anything asynchronous.
- Steps (`steps/`, one `ScenarioState` per scenario via PicoContainer): sign in, send P2P,
  `<user> submits <TYPE> of <n> poisha with counterparty <msisdn> and PIN "<pin>"` (any type
  whose body is the plain request), `the core rejects the next <CommandType> with reason
  <REASON>` (one-shot REJECT fault, reset by an `@After` hook),
  status/error/txnId assertions, "the app shows the transaction as X", balances noted / changed
  by / unchanged, audit states and chain. Add flow steps next to them; keep step text generic.
- Stability rule: never rely on leftover state. Fresh Idempotency-Key per request, balances
  compared before/after (never absolute), amounts small (seeded customers hold 10,000 BDT with a
  15,000 BDT daily limit). Tag every scenario with the flow tag and the `@mw-<module>` tags it
  exercises.
