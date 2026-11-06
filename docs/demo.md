# Milestone demo script (M0–M6)

A human approves each milestone by running its demo (spec "Ground rules", M12). Every section
below is a list of commands to run from the repo root plus what you should see. The automated
parts are the API suite's folders (`tests/api`, Newman, one request per scenario, through APISIX
like any client); the manual
parts are walks through the admin console. Commands are cumulative: a later milestone's demo
assumes the earlier ones still pass.

## Before any demo

```sh
make reset                      # fresh volumes: seed balances, daily limits, Keycloak users
make build                      # jars and images
time make up TIMERS=fast        # under 15 minutes on a clean host (README quick start); short timers for the suite
make seed                       # the mw-seeder baseline the suite checks in folder 00
make smoke
```

`make api-test` enables the suite's test-only gateway routes on the running stack by itself (it
re-runs `apisix-init` with `MW_E2E_ROUTES=1`); a later `make up` removes them again. Folders are
picked by number or full name, comma-separated: `make api-test FOLDER="01, 03"`; the folder list
is in `tests/api/README.md`. A failing request prints the check, the call and the envelope it got;
the JUnit report is tests/api/build/newman-report.xml.

## M0 Foundation

What it proves: repo skeleton and quality gate (WP01), platform stack (WP02), service template
(WP03, WP05), contracts (WP04).

| Step | Command | Expect |
| --- | --- | --- |
| 1 | `make test-scripts` | every shell test `ok`, among them `test_check_isolation.sh` (a planted cross-module dependency, `includeBuild` and convention plugin are caught) and `test_service_template.sh` |
| 2 | `make check-isolation` | every module passes |
| 3 | `make contracts-lint` | every schema and its valid/invalid samples pass (needs Node.js 22+) |
| 4 | `cd templates/mw-service-template && ./gradlew test` | the template builds and tests from its own folder alone (masking, error mapping, retry/DLQ routes) |
| 5 | `make smoke` | every platform container healthy, topics with RF 1, Kafka produce/consume, PostgreSQL, Valkey, Keycloak tokens per test user, OpenBao read, APISIX `/healthz` over HTTPS |
| 6 | `make new-service NAME=mw-demo && make check-isolation M=mw-demo` | a scaffolded copy with package `mfs.mw.demo` and no reference back to the template; remove it afterwards with `rm -rf services/mw-demo` |

## M1 Core loop

What it proves: core mock and orchestrator saga (WP06, WP07), app gateway with JWT and the sync
wait (WP08), edge policies (WP09), audit trail (WP10).

| Step | Command | Expect |
| --- | --- | --- |
| 1 | `make api-test FOLDER="01, 02, 03"` | P2P, agent cash-in and customer cash-out complete; insufficient funds and wrong PIN rejected; the same idempotency key resent never moves money twice (core-mock balances compared); lost core replies end COMPLETED or FAILED by status inquiry (requests tagged `tsq`) |
| 2 | `make edge-test` | 401 without, with a garbage or expired token; 400 on a broken body; 413 on an oversized body; 429 past the quota; `X-Mw-*` stripped; correlation ID generated or kept; a second apply is a no-op |
| 3 | `cd services/mw-audit-dw && ./gradlew test --tests mfs.mw.audit.dw.service.HashChainTest` | the hash chain fails at an edited or deleted audit row, and at the next row when an edited row was re-hashed |
| 4 | `make api-test FOLDER=01` | every state change of a P2P lands in the audit trail once and the month's hash chain verifies (`[p2p] a completed P2P leaves a verifiable audit trail`) |

## M2 Partners

What it proves: adapter framework and simulators (WP11, WP12), hold / commit / release, TSQ,
`UNKNOWN`, `MANUAL_REVIEW` and reversal (WP13), top-up, bill pay and C2W add money (WP14–WP16).

| Step | Command | Expect |
| --- | --- | --- |
| 1 | `make api-test FOLDER=05` | top-up succeeds; an OCS refusal releases the hold; an OCS timeout or ambiguous answer goes `UNKNOWN`, then the status inquiry commits or releases; a duplicate request moves money once |
| 2 | `make api-test FOLDER="06, 07"` | biller list, bill fetch and pay; unknown bill and refused payment mapped to `MW-PTR-BILLAGG-*`; callback-only mode with duplicate, out-of-order, late and conflicting callbacks (a conflicting late callback keeps the terminal state and is audited) |
| 3 | `make api-test FOLDER=08` | C2W add money by bank callback, callback replay applied once, refused account, no answer resolved by status inquiry |
| 4 | `make api-test FOLDER=12` | a COMPLETED transaction reversed by the core (full amount, money back on the payer) |

## M3 Notify

What it proves: `mw-notify` SMS over SMPP to the SMSC simulator (GSM-7 / UCS-2, delivery reports)
and email to GreenMail, bn/en templates, per flow (WP17).

| Step | Command | Expect |
| --- | --- | --- |
| 1 | `make api-test FOLDER="10, 11"` | for each flow the payer (and payee where the rules say so) gets the SMS in the right language with masked MSISDNs, and the email lands in GreenMail; a processing SMS on `UNKNOWN` |

## M4 Channels

What it proves: USSD engine and both transports (WP18, WP19), app agent / merchant roles, device
binding and request signature (WP20), web checkout with IPN (WP21), org portal bulk disbursement
and SP query (WP22).

| Step | Command | Expect |
| --- | --- | --- |
| 1 | `make api-test FOLDER="18, 19, 20"` | the USSD gateway simulator walks every MVP flow's menu: robi over HTTP, gp over SMPP (bind, enquire_link, reconnect), Bangla and English, PIN encrypted on receipt |
| 2 | `cd services/mw-ussd && ./gradlew integrationTest --tests 'mfs.mw.ussd.Ussd*MenuWalkIT'` | the same menu walk passes over SMPP and over HTTP; only `mw.ussd.mno.<mno>.transport` differs (criterion 5) |
| 3 | `make api-test FOLDER="02, 04"` | device binding with OTP, a replayed signature refused, merchant in-app payment and collections view |
| 4 | `make api-test FOLDER=09` | checkout session (merchant HMAC), pay, redirect, signed IPN retried until the merchant answers |
| 5 | `make api-test FOLDER="21, 22, 23, 90"` | a 10,000-row CSV batch through maker-checker (folder 90 Slow), throttled child transactions and the result file; an over-size file refused; the SP sees its transactions as initiator and payee |

## M5 Admin

What it proves: `mw-admin-api` with RBAC, the maker-checker engine, search and timeline, manual
TSQ and reversal, partner config, USSD menus and SMS templates (WP23); the console (WP24, WP25).

Automated:

| Step | Command | Expect |
| --- | --- | --- |
| 1 | `make api-test FOLDER="13, 14, 15, 16, 17"` | search by txnId and MSISDN, timeline and audit, reversal requested by a maker and approved by a different checker (maker = checker refused), partner disable/enable and timeouts, menu and template publish, wallet unlock |
| 2 | `cd ui/mw-admin-ui && pnpm install && pnpm test` | unit, component and contract tests, no stack (Node.js 24 and pnpm) |
| 3 | `make seed && make ui-e2e` | Playwright on the running stack, real Keycloak and mw-admin-api: sign-in with password + TOTP (PKCE) and sign-out, search and timeline of a P2P the suite makes over USSD, reversal approved by the checker (txn REVERSED), own request not approvable and rejected, wallet unlock, partner disable/enable and timeouts, menu and template publish (go-live 2099, so nothing a subscriber sees changes); run it twice, both pass |

Manual walk (acceptance criterion 4), after `make api-test FOLDER=01` so there are transactions:

1. Open https://localhost/console and sign in as `ops-maker01` (password `KC_OPS_TEST_PASSWORD`
   from `deploy/.env`); enrol TOTP with an authenticator app at first sign-in.
2. Transactions: search MSISDN `01711000001`. The list shows masked MSISDNs. Open a COMPLETED
   P2P: summary, saga timeline (STATE and CORE steps), partner calls, audit trail.
3. In "Actions", type a reason and press "Request reversal". The console lands on Approvals with
   the PENDING request (diff COMPLETED -> REVERSING); the transaction page now shows a
   pending-request notice instead of the buttons.
4. In a private window sign in as `ops-checker01` (enrol TOTP too), open Approvals and approve
   the request. As `ops-maker01` the same request says another checker decides.
5. Reload the transaction: REVERSED, with a linked REVERSAL transaction.
6. Partners (`/console/partners`): as `ops-maker01` request "disable" for partner `ocs`;
   approve as `ops-checker01`; the partner shows disabled at version + 1. Enable it again the same
   way (a disabled OCS fails every top-up).
7. USSD menus (`/console/ussd?menuId=main`) and SMS templates (`/console/templates`): edit,
   preview (the preview walk runs in the browser only, PIN shown as `****`), propose, approve as
   the checker; the new version shows PUBLISHED.
8. Wallets (`/console/wallets`): request UNLOCK for a wallet and approve it as the checker. The
   core unlocks only a LOCKED wallet (five wrong PINs in a row; `make api-test FOLDER=16` shows
   the whole path) and the console does not show the core's answer.

## M6 Hardening

What it proves: the acceptance suite (WP26), chaos (WP27), 100 TPS (WP28), the security pass
(WP29) and these docs (WP30). This is the milestone gate; run it on one fresh stack in this order.

| Step | Command | Expect |
| --- | --- | --- |
| 1 | `make verify-changed BASE=<sha of the run start>` (stack down: `make down`) | every changed module and its contract neighbours green: `./gradlew check` per module, isolation, field injection, contracts lint, compose-check; logs in `build/verify-changed/` |
| 2 | `make reset && make build && time make up TIMERS=fast && make seed && make smoke` | the full stack with seed data in under 15 minutes (criterion 6) |
| 3 | `time make api-test` | every request green in under 10 minutes; `tests/api/TRACEABILITY.md` maps them to criteria 1, 2, 4, 5 (`make api-test ALL=1` adds the 10,000-row payout) |
| 4 | `make chaos` | each `mw-*` container, then `kafka` and `postgres`, killed mid-flow under load: no transaction left non-terminal, ledger sums unchanged, nothing posted twice (criterion 3). Long (each target takes several minutes); a short demo: `make chaos TARGETS="mw-orchestrator kafka"`. Report in `build/chaos/` |
| 5 | `make perf` | about 100 TPS mixed load for 5 minutes, p95 middleware overhead under 150 ms, zero KO (criterion 7); the overhead report under tests/perf/build/reports/perf/ (start from a fresh stack: the run spends daily limits) |
| 6 | `SECURITY_LIVE=1 make security` | no secret found, no open HIGH/CRITICAL in dependencies or images, the ZAP baseline on APISIX passes `deploy/zap/decisions.tsv`, the container log scan finds no PIN or unmasked MSISDN/NID (it reads the logs the API suite run left) |
| 7 | the README quick start on a clean host, by someone not on the team | stack up with seed data in under 15 minutes from the repo alone |

Once at the end of M6, the full gate: `make verify` (every module, slow: hours) with the stack
down.
