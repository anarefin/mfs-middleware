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
| Tokens (realm portals) | `POST /auth/realms/portals/protocol/openid-connect/token` | `auth-portals-token.json` (ticket 33), same pinning; client `mw-portal`, password `KC_PORTALS_TEST_PASSWORD`; `support/Keycloak.portalToken(user)` |
| Org portal API | `/portal/org/v1/*` | the real route (`portal-org-v1.json`); `support/PortalApi` |
| core-mock test/admin API | `/test/core-mock/v1/*` -> `mw-core-mock:8080/core-mock/v1/*` | `deploy/apisix/routes-test/test-core-mock.json`, TEST ONLY: PIN public key, `wallets/{id}` balances, `faults`. Present only after `make e2e` re-ran `apisix-init` with `MW_E2E_ROUTES=1` (a plain `make up` removes it); key-auth header `apikey` = `MW_E2E_API_KEY` from the generated `.env`, private source addresses only |
| Simulator control API | `/test/sim/*` -> `mw-simulators:8080/sim/*` | `deploy/apisix/routes-test/test-simulators.json`, TEST ONLY, same key-auth as core-mock; `support/Simulators.setScenario` / `reset` |
| Audit rows | `docker compose exec -T postgres psql` (superuser, read-only queries) | no API exposes `audit.event` before mw-admin-api (WP23), and the service role cannot read it |
| Internal requests (REVERSAL) | `docker compose exec -T kafka kafka-console-producer.sh` on `mw.txn.request.v1` | `support/TxnRequests`: plays the operations producer (channel SYSTEM) until mw-admin-api's maker-checker reversal exists (ticket 37); the broker is not published |

## Reuse in later flows (TOPUP, cash-in/out, bill pay, ...)

- `support/AppApi`: `request(txnType, creditParty, amountMinor, pin)` (add flow fields to the
  returned node), `submit`, `status`, `pollStatus`, `newIdempotencyKey()`, `FINAL`.
- TOPUP (`features/topup.feature`, `@topup`): step "tops up N poisha <operator> <product> airtime
  on <msisdn>"; money lands on the core's `SETTLEMENT` wallet (fee 200 on `FEE-POOL`); the OCS
  simulator refuses MSISDNs ending in `999` (other magic values: amount fraction .13 timeout,
  .77 5xx, .99 ambiguous, .55 malformed, all UNKNOWN, resolved by status inquiry).
- Unknown outcomes (`@tsq`, ticket 18): the response is 202 `PROCESSING`; assert the end with "the
  app shows the transaction as X [with error "C"] within N seconds" (first inquiry 30 s after
  UNKNOWN; a NOT_FOUND counts only ~60 s after the PAY, so a release takes ~2 min). "the OCS
  answers too late and never applies the top-up" forces a 25 s, not-applied OCS (reset with "the
  OCS behaves normally again" and by an `@After` hook); "the core drops its reply to the next
  PostTransfer" loses a P2P reply that QueryTransaction then finds. "no funds are held on <wallet>"
  compares the core's held amount with the noted one.
- BILL_PAY (`features/billpay.feature`, `@billpay`, ticket 21): "<user> lists the billers" /
  "the billers include DESCO"; "<user> fetches bill <n> of biller <code>" / "the amount due is N
  poisha" (sync fetch through mw-app-gateway -> mw-adapter-billpay internal API -> aggregator
  simulator `billagg`); "<user> pays N poisha on bill <n> of biller <code> with PIN ..." (credit
  party BILLER_ACCOUNT, `partner.billerCode`). Money lands on `SETTLEMENT` (fee 300 on
  `FEE-POOL`). Simulator magic values: amount due = 100 taka + last three digits of the bill
  number (1234567 -> 66700 poisha); bill numbers ending 404 unknown (`MW-PTR-BILLAGG-BILL_NOT_FOUND`),
  ending 999 refused on payment (`MW-PTR-BILLAGG-PAYMENT_REFUSED`); amount fraction .13 times out
  past the adapter's 25 s limit (202, then COMPLETED by status inquiry, `@tsq`).
- Partner callbacks (`features/billpay-callback.feature`, `@billpay @callback`, ticket 22): "the
  aggregator confirms payments only by callback[, sending each callback twice | , out of order |
  , N seconds late]" puts billagg in callback-only mode (202 PENDING, signed callback to
  `http://apisix:9080/partner/billagg/callback/payments`; reset by an `@After` hook); "the
  aggregator replays the transaction's callback" (simulator replay); "the aggregator calls back
  <STATUS> with code <C> for the transaction" signs with `BILLAGG_HMAC_SECRET` from the generated
  `.env` (`support/PartnerCallbacks`), "... with a bad signature" signs with a wrong key; audit
  steps "records a late <OUTCOME> callback", "records a conflicting <OUTCOME> callback", "records
  no late callback", "records the states \"...\" once each" (state rows only). The late
  scenario (`@tsq`) takes about 100 s.
- ADD_MONEY_BANK / C2W (`features/add-money-bank.feature`, `@addmoney @c2w`, ticket 23): "the bank
  confirms debits by callback[, sending each callback twice]" points the c2wbank simulator's
  signed callbacks at `http://apisix:9080/partner/c2wbank/callback/debits` (reset by an `@After`
  hook); "<user> adds N poisha from bank account <acct> at c2wbank with PIN ..." (body
  `debitParty` BANK_ACCOUNT, `partner.partnerId`, no `creditParty`); "the bank replays the
  transaction's callback". Money comes from `SETTLEMENT` (no fee). Magic values: account ending
  999 refused (`MW-PTR-C2WBANK-DEBIT_REFUSED`), amount fraction .13 = no answer within 25 s and no
  callback (`@tsq`, COMPLETED by status inquiry after about 60 s).
- REVERSAL (`features/reversal.feature`, `@reversal`): "operations reverses the transaction"
  publishes a REVERSAL of the scenario's (COMPLETED) transaction; the original then shows
  REVERSED (or MANUAL_REVIEW) in the app view. "the orchestrator has handled the reversal
  request" waits for the request's idempotency row (a repeated reversal leaves no other trace).
  "the core rejects the next <Command> with reason <R>" injects a REJECT fault.
- `support/CoreMock`: `encryptPin` (RSA-OAEP SHA-256 with the core key), `balance`,
  `injectFault` / `resetFaults` (reset in an `@After` hook of the scenarios that inject).
- Org portal bulk (`features/bulk-upload.feature`, `@bulk @mw-portal-api`, ticket 33): "<user>
  uploads a bulk file of N rows" (`PortalApi.file`, 1.00 taka per row, fresh references), "...
  with these lines:", "<user> approves the batch" / "rejects the batch because "..."" / "looks
  at the batch", "the batch status is X", "the batch has N rows totalling M poisha", "the error
  details name these fields:". Users of realm portals: org-maker01, org-checker01,
  org-makerchecker01 (both roles) of ORG001, org-checker02 of ORG002. A stack whose Keycloak
  database predates ticket 33 needs `make down DOWN_ARGS=-v` (realm import does not update).
- `support/Keycloak.customerToken(user)`; `support/AuditTrail.rowsOfTxn` / `verifyChain`;
  `support/Waits.until` for anything asynchronous.
- Steps (`steps/`, one `ScenarioState` per scenario via PicoContainer): sign in, send P2P,
  `<user> submits <TYPE> of <n> poisha with counterparty <msisdn> and PIN "<pin>"` (any type
  whose body is the plain request), status/error/txnId assertions, "the app shows the transaction as X", balances noted / changed
  by / unchanged, audit states and chain. Add flow steps next to them; keep step text generic.
- Stability rule: never rely on leftover state. Fresh Idempotency-Key per request, balances
  compared before/after (never absolute), amounts small (seeded customers hold 10,000 BDT with a
  15,000 BDT daily limit). Tag every scenario with the flow tag and the `@mw-<module>` tags it
  exercises.
