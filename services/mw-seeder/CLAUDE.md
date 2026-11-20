# mw-seeder

Standalone module (spec 4.2, 4.3). Package `mfs.mw.seeder`. Follow the root `CLAUDE.md` conventions;
this file says what is specific to this module.

## Responsibilities

- One-shot seeder of the dev/test stack (ticket 57, BitsPay `services/seeder` pattern): it puts one
  known baseline in place so the API suite (Newman, ticket 58) and Playwright (ticket 59) share it;
  tests still make their own unique data on top. A Spring Boot app with no web server
  (`spring.main.web-application-type=none`), no database, Kafka, Camel or Vault.
- `scheduler/SeedRunOnStartup` (`ApplicationRunner`, off with `mw.seeder.run-on-startup=false`) runs
  `scheduler/SeedRunner`: the `service/*Step`s in `@Order`, each on a virtual thread with its timeout
  (`mw.seeder.step-timeout`, default 10 min, per step `mw.seeder.step-timeouts.<id>`); a retryable
  failure (`exception/RemoteCallException` with 503, 504 or no answer) runs the whole step again after a
  back-off (1 s doubling to 15 s) until the timeout; anything else, or the timeout, stops the run
  (`SeedFailedException`, later steps never run). `SeederApplication.run` returns the exit code: 0, or 1
  on failure; `main` hands it to `System.exit`.
- Every step is idempotent (looks up before it creates) and reports `created / updated / unchanged`
  (`service/StepResult`); the log line `seed.step.completed` carries `outcome`. A second run on a seeded
  stack is `UNCHANGED` for every step and makes no create or change call (only GETs, token requests and
  mw-auth's idempotent seed-PIN call, which answers UNCHANGED).
- Steps (ids, in order):
  1. `await-platform-ready`: GET each `mw.seeder.readiness` URL until 200: APISIX `:7085/status/ready`,
     Keycloak `:9000/health/ready`, `/actuator/health/readiness` of every long-running mw-* service.
  2. `seed-identities`: the personas of `mw.seeder.users` in Keycloak (admin REST as the bootstrap admin
     `KC_BOOTSTRAP_ADMIN_*`, master realm, `admin-cli`). Missing user: created with the realm's test
     password and, in realms ops and portals, a TOTP credential (the realm import's format: HmacSHA256, 6
     digits, 30 s, secret `KC_OPS_TEST_TOTP_SECRET` / `KC_PORTALS_TEST_TOTP_SECRET`, ticket 73), plus its
     realm roles. Existing user: only the missing roles are added; credentials are never touched. The
     realms' clients (`mw.seeder.realms.<r>.clients`) must exist (the realm imports create them) or the
     step fails. A realm with `keycloak: false` is not in Keycloak: `mw-auth`, the app users (ticket 73:
     the Keycloak `customers` realm is gone; they log in at mw-auth with MSISDN + the dev app PIN). Its
     users are only listed in the manifest (realm `mw-auth`, `passwordEnv` `MW_SEED_APP_PIN`, no
     `loginClient`), with no Keycloak call and not counted in the step result.
  3. `seed-core-accounts`: the wallets of `mw.seeder.wallets` in mw-core-mock: GET, and only if unknown
     the test-only `PUT /core-mock/v1/wallets/{walletId}` (create-if-absent, seed PIN, opening balance, no
     ledger entries; an existing wallet keeps whatever balance tests left). A requested `ORG` matches the
     `ORGANISATION` core-mock stores (ticket 94); any other stored type fails the step.
     **Billers (ticket 57 lists them) need no seeding, by design:** (a) core-mock has no biller wallet
     type (its seed PUT takes CUSTOMER|AGENT|MERCHANT|ORG): a bill payment's credit party is a
     `BILLER_ACCOUNT` outside the core, validated payer-side only and committed to the `SETTLEMENT`
     wallet core-mock seeds itself; (b) the biller catalogue (DESCO, DPDC, WASA, TITAS -> `billagg`) is
     static mw-adapter-billpay config (`mw.billers`, served read-only by `GET /billpay/v1/billers`; no
     write API, and mw-admin-api has no biller catalogue yet, spec 11.2); (c) the `billagg` partner is the
     adapter's own dev seed, mirrored as a version-0 seed in mw-admin-api since ticket 84 (with `ocs`
     and `c2wbank`), so nothing needs seeding for it; (d) the simulated aggregator derives every bill from its
     number (due = 100 taka + the last three digits), so there are no bills to create. A bill-pay test
     needs only a seeded customer wallet.
  4. `seed-app-pins` (ticket 69): the app wallets log in at mw-auth with MSISDN + PIN, so every seeded
     CUSTOMER, AGENT and MERCHANT wallet plus `mw.seeder.app-pins.extra-wallet-ids` (the realm imports'
     app users customer01/02, agent01, merchant01/02: core-mock's own seed wallets) gets the dev app PIN
     (`mw.seeder.app-pins.pin`, `MW_SEED_APP_PIN`, fake `13579` in `dev.env.defaults`). The PIN is encrypted
     as an app does (`util/PinCipher`, RSA-OAEP SHA-256 under the key of mw-auth's `GET /auth/v1/pin-key`)
     and set with mw-auth's dev-only `POST /internal/v1/seed/pins` (`client/AuthSeedClient`, endpoint
     `mw.seeder.endpoints.auth` = `http://mw-auth:8080`, plain public port, no token; needs
     `MW_AUTH_SEED_API_ENABLED=true`). mw-auth leaves a wallet with a PIN alone (UNCHANGED); a wallet it
     has not projected yet from the core's wallet events makes the step retry (503). No PIN configured:
     skipped with a WARN.
  5. `seed-partner-configs`: each partner of `mw.seeder.partners` at its baseline in mw-admin-api
     (enabled, `timeoutMs` 20 s, the e2e suite's restore values). Each difference: the seed maker asks
     (ENABLE / DISABLE / SET_TIMEOUTS), the seed checker approves (publishes to the adapters). At its
     baseline (the fresh-stack case): nothing is asked (the admin refuses a change that changes
     nothing). Pending requests of the seed maker are approved first; one of another operator fails the
     step (one open request per resource; the seeder never decides someone else's). A partner unknown to
     mw-admin-api (its dev seeds) fails the step.
  6. `seed-channel-clients` (ticket 100a): today's edge clients (`mw.seeder.channel-clients`: the
     customer/agent/merchant apps, the four MNO USSD gateways, billagg, c2wbank, the three checkout
     merchants, the org portal) as rows of mw-admin-api's channel client registry. A missing or
     different client: the seed maker files UPSERT with the full definition, the seed checker
     approves (mw-admin-api then projects the BASIC ones, the MNO gateways, to APISIX consumers whose
     password Vault holds). At its baseline: nothing is asked. Leftover requests of the seed maker are
     approved first; one of another operator fails the step. The MNO clients carry a rate limit only
     (a daily quota would replace their route's per-MSISDN limit-count).
  7. `seed-admin-content`: the USSD menu `main` (all four MNOs) and the SMS templates
     `TXN_P2P_RECEIVED` bn/en, proposed and approved through mw-admin-api, so the console has a first
     admin-made version. The values are copies of what mw-ussd (`menus/main-v4.json` ->
     `src/main/resources/seed-content/menus/main.json`) and mw-notify (V5 rows, in `application.yml`)
     ship, so publishing changes no text a test reads: keep the copies identical. Content with any
     PUBLISHED admin version is left alone; a version the seed maker left PENDING is approved.
  8. `write-manifest`: the manifest (below), replaced atomically only when its content changed.
- Manifest (`dto/SeedManifest`, `schemaVersion` 1): written to `mw.seeder.manifest-path`
  (`/run/mw/seed/manifest.json`, the `seed-manifest` volume); `scripts/api-test.sh` and `scripts/ui-e2e.sh` copy it to
  `build/seed/manifest.json` (host; read it from Newman and Playwright, or mount the volume read-only
  in a container). It is deterministic (no timestamps or run ids) and holds no secret:
  `{schemaVersion, personas[{persona, realm, username, displayName?, roles, attributes, loginClient?, passwordEnv,
  totpSecretEnv?}], importedUsers[same shape], wallets[{walletId, type, holderName?, openingBalanceMinor}], partners[{partnerId, family, version,
  enabled, timeoutMs}], ussdMenus[{menuId, version, mnos}], templates[{code, channel, locale, version}],
  appPins{pinEnv, walletIds[]}}` (`appPins`: the wallets with the dev app PIN and the variable that holds it).
  Fields are only added, never renamed (schemaVersion stays 1; null fields are left out). `displayName`
  is the persona's `first-name` + `last-name` from `mw.seeder.users` (realistic fake Bangladeshi names,
  also the Keycloak profile of a new user; "Seed" + persona when unset), `holderName` the wallet's
  `holder-name` (also sent on the core-mock PUT; an existing wallet without one gets it once).
  `importedUsers`: the users the realm imports create (`mw.seeder.imported-users`: `ops-*01`,
  `ops-e2e-*`, `org-*`, `sp-viewer01`), read from Keycloak in seed-identities (profile names, first value
  of each attribute, realm roles without `default-roles-<realm>`, sorted; never written; one missing is
  left out with a WARN); `persona` = username; `totpSecretEnv` only with `preset-totp: true` (every
  imported ops and portals user: the realm imports give them all the pre-set secret, no authenticator app). Names are masked to the
  first initial in the logged copy.
  Passwords and the TOTP secret are named by their `deploy/.env` variable. Every seeded wallet has
  mw-core-mock's seed PIN (`mw.coremock.seed.pin`, the fake `1234`). It is also logged once, masked
  (`seed.manifest`, MSISDNs `0171****001`).
- Seeded data (`application.yml`, fake values only): ops `seed-ops-maker` (viewer, ops-maker),
  `seed-ops-checker` (viewer, ops-checker), `seed-ops-viewer` (viewer), `seed-ops-compliance`
  (compliance-officer, as the realm import's `ops-compliance01`: the console's audited PII reveal);
  all four with password + TOTP; portals `seed-org-maker`,
  `seed-org-checker` (orgId ORG001, password + TOTP); app users (realm `mw-auth`, no Keycloak user)
  `seed-customer01`/`02` (`01712000001`/`2`), `seed-agent01` (`01812000001`), `seed-merchant01` (`01912000001`), `seed-distributor01` (`01612000001`, DISTRIBUTOR,
  ticket 94); wallets for those MSISDNs plus orgs `01312000001`, `01312000002` (sent as ORG; core-mock stores ORGANISATION),
  outside mw-core-mock's own seed ranges (`0171100xxxx`, ...). The API suite's run customers `seed-run-customer01..24` (persona
  `run-customer`, role customer, MSISDN `01714NN0001`: masks as `0171****001` like customer01) with
  CUSTOMER wallets of 10,000,000 BDT: each `scripts/api-test.sh` run plays customer01 as one of them
  (`tests/api/run_customer.mjs`, at most 3 runs per UTC day each), so repeated runs never reach a
  wallet's daily debit limit (`tests/api/README.md`). The realm imports' own users
  (`ops-e2e-maker01`, `org-maker01`, ...) and core-mock's own app wallets (`customer01` = `01711000001`,
  ...; app PIN from `extra-wallet-ids`) stay for the Newman suite (`tests/api`), which still logs in as
  several of them, and for manual use.

## Logging in as the seeded personas (Newman, Playwright)

- Password: `deploy/.env` variable `passwordEnv` of the persona (e.g. `KC_OPS_TEST_PASSWORD`). Realm
  `mw-auth` personas: `passwordEnv` names the app PIN (`MW_SEED_APP_PIN`); log in at mw-auth
  (`scripts/lib/mw-auth-token.mjs`, MSISDN from `attributes.msisdn`).
- Realms ops and portals demand a TOTP code: compute it from the persona's `totpSecretEnv`
  (`KC_OPS_TEST_TOTP_SECRET`, `KC_PORTALS_TEST_TOTP_SECRET`) with RFC 6238, HmacSHA256,
  6 digits, 30 s, **keyed with the secret's UTF-8 bytes as they are** (Keycloak stores the raw string;
  do not base32-decode it). `util/Totp` (and the e2e suite's `support/Totp`) is the reference; RFC 6238
  SHA-256 vectors are in `util/TotpTest`.
- Playwright through the console (`https://localhost/console`, client `mw-admin-ui`, code + PKCE): the
  Keycloak login form takes username + password, then the OTP form takes the code. API calls (Newman):
  password grant of the dev-only direct-grant client `mw-e2e-ops` with form field `totp`, through the
  test-only APISIX route `/auth/realms/ops/...` (key-auth `MW_E2E_API_KEY`; applied with
  `MW_E2E_ROUTES=1`).
- Keycloak refuses a code a user has already used: one login per persona per 30 s step. The seeder
  itself logs in as `seed-ops-maker` / `seed-ops-checker` once per run; a suite logging in as the same
  persona within the same 30 s step must wait for the next one (or use `seed-ops-viewer`).

## Contracts owned

- None: it produces no event and serves no API.

## Contracts used

- `api/mw-admin-api/admin.v1.openapi.yaml` (partners, channel clients (ticket 100a, `dto/ChannelClientChangeRequest`, `ChannelClientDefinition`, `ChannelClientView`), change requests, approve, USSD menu and template
  versions and change requests), own types `dto/PartnerChangeRequest`, `MenuProposal`,
  `TemplateProposal`, `PartnerView`, `ChangeRequestView`, `ContentVersionView`. Called directly
  (`http://mw-admin-api:8080`, not through APISIX) with an ops token whose `iss` is Keycloak's internal
  URL: mw-admin-api checks only the realm path of `iss`.
- `api/mw-core-mock/core-mock-seed.v1.openapi.yaml` (owned by mw-core-mock, test-only; ticket 57): GET and
  PUT `/core-mock/v1/wallets/{walletId}`, own types `dto/WalletSeedRequest`, `WalletView`.
- Keycloak (third party, no contract file): admin REST (`/admin/realms/{realm}/users`, `roles`,
  `role-mappings`, `clients`) and the token endpoint.
- `api/mw-auth/auth.v1.openapi.yaml` (`GET /auth/v1/pin-key`, own type `dto/PinKeyView`) and
  `api/mw-auth/auth-internal.v1.openapi.yaml` (dev-only `POST /internal/v1/seed/pins`, own types
  `dto/SeedPinsRequest`, `dto/SeedPinsResult`; ticket 69).
- `contracts.yml` lists the four APIs; `contracts/ConsumedContractsTest` validates every request body the
  seeder sends against the OpenAPI request schema and reads contract-shaped answers into its types.

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3 (wallet ids and MSISDNs, names, secrets).
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output (free text is
  scrubbed, so an MSISDN in a message is masked).
- `config/MwServiceProperties`: service identity. No Vault: the stack passes the test credentials as
  environment variables interpolated from `deploy/.env` (dev/test only).
- Left out of the template on purpose (one-shot HTTP client): web server and filters, error envelope,
  outbox/inbox, Kafka/Camel routes, Flyway, ShedLock, Resilience4j, Vault, conformance suite.

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-seeder:test` | every test in one JVM, no Docker needed: unit and contract tests (runner, every step against fakes, `ConsumedContractsTest`) and `SeedRunnerIT`: the whole seeder against stateful WireMock stubs, run twice (second run: no create call, same manifest) and once against a platform that never gets ready (exit 1) |
| `./gradlew :mw-seeder:build` | `test`, then the boot jar `build/libs/mw-seeder.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-seeder:bootJar && docker build -t mfs-mw/mw-seeder:local services/mw-seeder` | image, as spec 4.3 rule 1 |
| `./gradlew clean build` | the full gate: every module, plus the repo checks (`:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`) |
| from `deploy/`: `docker compose --profile core --profile seed up -d --build` | build the image (profile `seed`) and run it on the stack; `docker compose --profile core --profile seed ps -a mw-seeder` must show `exited (0)`. `scripts/api-test.sh` and `scripts/ui-e2e.sh` copy its manifest to `build/seed/manifest.json` |
