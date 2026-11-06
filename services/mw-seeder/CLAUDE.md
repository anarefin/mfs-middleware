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
  on failure; `main` hands it to `System.exit`. The image's AOT training run
  (`spring.context.exit=onRefresh`) stops before the runner and exits 0.
- Every step is idempotent (looks up before it creates) and reports `created / updated / unchanged`
  (`service/StepResult`); the log line `seed.step.completed` carries `outcome`. A second run on a seeded
  stack is `UNCHANGED` for every step and makes no create or change call (only GETs and token requests).
- Steps (ids, in order):
  1. `await-platform-ready`: GET each `mw.seeder.readiness` URL until 200: APISIX `:7085/status/ready`,
     Keycloak `:9000/health/ready`, `/actuator/health/readiness` of every long-running mw-* service.
  2. `seed-identities`: the personas of `mw.seeder.users` in Keycloak (admin REST as the bootstrap admin
     `KC_BOOTSTRAP_ADMIN_*`, master realm, `admin-cli`). Missing user: created with the realm's test
     password and, in realm ops, a TOTP credential (the realm import's format: HmacSHA256, 6 digits,
     30 s, secret `KC_OPS_TEST_TOTP_SECRET`), plus its realm roles. Existing user: only the missing roles
     are added; credentials are never touched. The realms' clients (`mw.seeder.realms.<r>.clients`) must
     exist (the realm imports create them) or the step fails.
  3. `seed-core-accounts`: the wallets of `mw.seeder.wallets` in mw-core-mock: GET, and only if unknown
     the test-only `PUT /core-mock/v1/wallets/{walletId}` (create-if-absent, seed PIN, opening balance, no
     ledger entries; an existing wallet keeps whatever balance tests left). Billers are not core wallets
     (a BILLER_ACCOUNT is outside the core): they are mw-adapter-billpay partner config.
  4. `seed-partner-configs`: each partner of `mw.seeder.partners` at its baseline in mw-admin-api
     (enabled, `timeoutMs` 20 s, the e2e suite's restore values). Each difference: the seed maker asks
     (ENABLE / DISABLE / SET_TIMEOUTS), the seed checker approves (publishes to the adapters). At its
     baseline (the fresh-stack case): nothing is asked (the admin refuses a change that changes
     nothing). Pending requests of the seed maker are approved first; one of another operator fails the
     step (one open request per resource; the seeder never decides someone else's). A partner unknown to
     mw-admin-api (its dev seeds) fails the step.
  5. `seed-admin-content`: the USSD menu `main` (all four MNOs) and the SMS templates
     `TXN_P2P_RECEIVED` bn/en, proposed and approved through mw-admin-api, so the console has a first
     admin-made version. The values are copies of what mw-ussd (`menus/main-v2.json` ->
     `src/main/resources/seed-content/menus/main.json`) and mw-notify (V5 rows, in `application.yml`)
     ship, so publishing changes no text a test reads: keep the copies identical. Content with any
     PUBLISHED admin version is left alone; a version the seed maker left PENDING is approved.
  6. `write-manifest`: the manifest (below), replaced atomically only when its content changed.
- Manifest (`dto/SeedManifest`, `schemaVersion` 1): written to `mw.seeder.manifest-path`
  (`/run/mw/seed/manifest.json`, the `seed-manifest` volume); `make seed` copies it to
  `build/seed/manifest.json` (host; read it from Newman and Playwright, or mount the volume read-only
  in a container). It is deterministic (no timestamps or run ids) and holds no secret:
  `{schemaVersion, personas[{persona, realm, username, roles, attributes, loginClient, passwordEnv,
  totpSecretEnv?}], wallets[{walletId, type, openingBalanceMinor}], partners[{partnerId, family, version,
  enabled, timeoutMs}], ussdMenus[{menuId, version, mnos}], templates[{code, channel, locale, version}]}`.
  Passwords and the TOTP secret are named by their `deploy/.env` variable. Every seeded wallet has
  mw-core-mock's seed PIN (`mw.coremock.seed.pin`, the fake `1234`). It is also logged once, masked
  (`seed.manifest`, MSISDNs `0171****001`).
- Seeded data (`application.yml`, fake values only): ops `seed-ops-maker` (viewer, ops-maker),
  `seed-ops-checker` (viewer, ops-checker), `seed-ops-viewer` (viewer); portals `seed-org-maker`,
  `seed-org-checker` (orgId ORG001); customers `seed-customer01`/`02` (`01712000001`/`2`), `seed-agent01`
  (`01812000001`), `seed-merchant01` (`01912000001`); wallets for those MSISDNs plus org `01312000001`,
  outside mw-core-mock's own seed ranges (`0171100xxxx`, ...). The realm imports' own users
  (`ops-e2e-maker01`, `customer01`, `org-maker01`, ...) stay for the Cucumber suite.

## Logging in as the seeded personas (Newman, Playwright)

- Password: `deploy/.env` variable `passwordEnv` of the persona (e.g. `KC_OPS_TEST_PASSWORD`).
- Realm ops demands a TOTP code: compute it from `KC_OPS_TEST_TOTP_SECRET` with RFC 6238, HmacSHA256,
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

- `api/mw-admin-api/admin.v1.openapi.yaml` (partners, change requests, approve, USSD menu and template
  versions and change requests), own types `dto/PartnerChangeRequest`, `MenuProposal`,
  `TemplateProposal`, `PartnerView`, `ChangeRequestView`, `ContentVersionView`. Called directly
  (`http://mw-admin-api:8080`, not through APISIX) with an ops token whose `iss` is Keycloak's internal
  URL: mw-admin-api checks only the realm path of `iss`.
- `api/mw-core-mock/core-mock-seed.v1.openapi.yaml` (owned by mw-core-mock, test-only; ticket 57): GET and
  PUT `/core-mock/v1/wallets/{walletId}`, own types `dto/WalletSeedRequest`, `WalletView`.
- Keycloak (third party, no contract file): admin REST (`/admin/realms/{realm}/users`, `roles`,
  `role-mappings`, `clients`) and the token endpoint.
- `contracts.yml` lists the two APIs; `contracts/ConsumedContractsTest` validates every request body the
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

| Command | What it does |
| --- | --- |
| `./gradlew test` | unit and contract tests (runner, every step against fakes, `ConsumedContractsTest`), no Docker |
| `./gradlew integrationTest` | `SeedRunnerIT`: the whole seeder against stateful WireMock stubs, run twice (second run: no create call, same manifest) and once against a platform that never gets ready (exit 1); no Docker |
| `./gradlew check` | both plus the JaCoCo gate (80% lines on `service`/`client`) |
| `./gradlew check bootJar && docker build -t mfs-mw/mw-seeder:local .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-seeder` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make build M=mw-seeder` | `bootJar`, then the image (`deploy/docker-compose.yml`, profile `seed`) |
| `make seed` | run it on the running stack (core profile), fail unless it exits 0, copy the manifest to `build/seed/manifest.json` |
