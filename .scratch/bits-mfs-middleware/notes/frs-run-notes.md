# FRS run 90-104: code map (explored 2026-11-19 on upay-main @ ea87ab2d; read-only)

Paths relative to repo root. `S/` = `services/`. `<pkg>` = `src/main/java/mfs/mw/<svc as dots>/`.
MAP.md is stale on build: ONE root Gradle multi-module build, no Makefile, no per-module wrapper/compose.yml/version catalog.

## 0. Global facts every implementer needs
- Build: `./gradlew :mw-x:build` (compile+test+bootJar). `settings.gradle.kts` auto-includes every `services/*/build.gradle.kts` (no edit needed).
  `gradle.properties`: parallel, cache, config-cache, `mwContractsDir=../../contracts`, `mwDockerTests=4`.
- Shared versions (MUST be identical in every module; `./gradlew :checkIsolation` fails on plugin drift):
  - plugin `id("org.springframework.boot") version "4.1.2"` (17 files incl. template). NO dependency-management plugin: BOMs are `platform(...)`.
  - `platform("org.springframework.boot:spring-boot-dependencies:4.1.2")` (also `mockitoAgent(platform(...))`)
  - `platform("org.springframework.cloud:spring-cloud-dependencies:2025.1.4")` (spring-cloud-starter-vault-config)
  - `platform("io.github.resilience4j:resilience4j-bom:2.4.0")` + `io.github.resilience4j:resilience4j-spring-boot4:2.4.0`
  - `platform("org.testcontainers:testcontainers-bom:2.0.5")`, `platform("com.fasterxml.jackson:jackson-bom:2.22.3")`
  - `net.javacrumbs.shedlock:shedlock-spring:7.10.1` + `shedlock-provider-jdbc-template:7.10.1`
  - `com.networknt:json-schema-validator:3.0.8` (tests), `org.wiremock:wiremock-standalone:3.13.1` (7 modules)
  - CVE pins: `org.xerial.snappy:snappy-java:1.1.10.9`, `at.yawk.lz4:lz4-java:1.11.4`
  - Camel (adapters only): `platform("org.apache.camel.springboot:camel-spring-boot-bom:4.22.2")`, `org.apache.camel:camel-cxf-soap:4.22.2`,
    `com.dashjoin:jsonata:0.9.10`, `net.sf.saxon:Saxon-HE:13.0`; `com.nimbusds:nimbus-jose-jwt:10.9.1` (4), `org.jsmpp:jsmpp:3.0.2` (3), BC 1.84 (1)
  - Java toolchain 25; `consistentResolution { useRuntimeClasspathVersions() }`. springdoc: NOT used anywhere yet (101 adds it, same version everywhere).
- Spring Boot 4 / Jackson 3 (`tools.jackson.*`, `JsonMapper`); `-Werror -Xlint:all`.
- ADRs: `docs/adr/D1..D28` + `M1..M13`; latest = `D28-partner-routes-as-config.md`. D29-D33 do NOT exist yet (ticket 89 writes them:
  D29 redis, D30 hashicorp-vault, D31 core-wallet-rest, D32 service-per-party, D33 unified-mock-control). `docs/frs-traceability.md` does not exist yet (89).
  `scripts/test/test_repo_docs.sh` fails when a spec decision row has no ADR, or docs name a missing script/path/task/knob.
- Flyway latest per module (`S/<m>/src/main/resources/db/migration`); next free = +1:
  adapter-bank V5, adapter-billpay V5, adapter-telco V5, admin-api V11, app-gateway V9, audit-dw V6, auth V8, checkout V7,
  core-mock V11, crypto V7, notify V12, orchestrator V12 (V10 gap, never existed: next is V13), portal-api V6, simulators V3, ussd V5, seeder none.
- Kafka topics: `deploy/kafka/topics.txt` (`name partitions retention retry=<consumer groups>`). Contract owners: `contracts/OWNERS.yml`;
  outside-repo sides: `contracts/EXTERNAL.yml`. Each module: `S/<m>/contracts.yml` (+ `contracts/{Produced,Consumed,Api}ContractsTest`, `OpenApiSchemas`).
- Compose budget: `scripts/compose-check.sh:11` `MW_RAM_BUDGET_MIB` default 8192 (FRS plan says 12 GB; change knob + docs if a ticket needs it).

## 1. New module checklist (`scripts/new-service.sh mw-x` -> `templates/mw-service-template/scaffold.sh`)
- Copies template, renames package `mfs.mw.<x.y>`, `<X>Application`, schema `<x_y>`; fails if a template name survives.
- Prints (scaffold.sh:135-165) blocks to PASTE BY HAND:
  - `deploy/docker-compose.yml` service (`<<: *app`, `profiles: *core`, `image: mfs-mw/mw-x:local`, `build: {context: ../services/mw-x, args: *jvm-build-args}`,
    `depends_on: <<: *infra-deps`, volume `<suffix>-approle:/run/mw/approle:ro`, `<<: *jvm-env`, memory `${APP_MEM_LIMIT:-416M}`), top-level volume, and
    `provision:` volume `- <suffix>-approle:/out/<suffix>`. Anchors at compose:100 `x-logging`, 108 `x-app`, 135 `x-infra-deps`, 147 `x-jvm-env`, 164 build args, 167/168 profiles.
  - `deploy/provision/services.sh`: append `mw-x:<suffix>` to `SERVICES` + function `provision_mw_x() { gen_once spring.datasource.password 40; db_role <snake>_rw <snake>; }`.
- Also by hand: `S/mw-x/CLAUDE.md`, `contracts.yml`, `contracts/OWNERS.yml` entries for owned files, `deploy/kafka/topics.txt` retry groups,
  `README.md` (test_repo_docs), mTLS: add to `PKI_SERVICES` in services.sh if it serves/calls mTLS. Template already has
  `config/ServiceCertificateManager`, `TlsProperties`, `MwServiceProperties`, tests `PkiTestAuthority`, `client/MtlsClientIT`, `config/VaultConfigBindingIT`.

## 2. Ticket 90 Redis swap: every Valkey touchpoint
- Compose `deploy/docker-compose.yml`: service `valkey` L344-368 (image `valkey/valkey:8.1.11-alpine@sha256:2602ace...`, port `127.0.0.1:${MW_PORT_VALKEY:-6379}`,
  env `REDISCLI_AUTH: ${VALKEY_PASSWORD:?...}`, cmd `valkey-server --appendonly yes --requirepass`, volume `valkey-data` L184, healthcheck `valkey-cli ping`,
  `VALKEY_MEM:-64M` L367, header table L86). Header comments L33, L38, L75. apisix env `VALKEY_PASSWORD` L516-517; L695 (provision env).
  Service env `VALKEY_HOST: ${VALKEY_HOST:-valkey}`: mw-auth L848, mw-orchestrator L923, mw-app-gateway L994, mw-ussd L1054.
- `deploy/dev.env.defaults:40` `VALKEY_PASSWORD=dev-valkey-pass`. `deploy/reset.sh:3` comment.
- `deploy/provision/services.sh`: `sync_env spring.data.redis.password VALKEY_PASSWORD` at L44 (mw-auth), L71 (orchestrator), L113 (app-gateway), L130 (ussd); comments L38, L67, L109, L125.
- Spring config (all `spring.data.redis.host: ${VALKEY_HOST:valkey}`, `port: ${VALKEY_PORT:6379}`; password from vault KV `spring.data.redis.password`;
  `management.health.redis.enabled: false`):
  mw-auth application.yml L43-49, L201-202, comments L230, L287; mw-app-gateway L44-50, L214-215; mw-orchestrator L44-50, L173-174, L210-213 (`mw.orchestrator.idempotency-lock.{enabled,ttl}`, env `MW_IDEMPOTENCY_LOCK_ENABLED`);
  mw-ussd L43-48, L187-188, L251. Test config: `S/mw-app-gateway/src/test/resources/config/application.yml:9`, `S/mw-orchestrator/src/test/resources/config/application.yml:13`.
- build.gradle.kts comment + `spring-boot-starter-data-redis`: mw-auth L64-65, mw-app-gateway L48-49, mw-orchestrator L63-64, mw-ussd L61-62 (+ L149 comment).
- Classes carrying the name: orchestrator `<pkg>config/ValkeyConfig` (beans `valkeyFailFast`, `valkeyIdempotencyLock`, `noIdempotencyLock`), `client/ValkeyIdempotencyLock`
  (impl of `client/IdempotencyLock`), `service/IdempotencyGate` (doc + key hashing L110); ussd `repository/ValkeySessionStore` (impl of `UssdSessionStore`), `config/UssdConfig`;
  app-gateway `repository/DeviceGateStore`, `config/DeviceBindingConfig`, `service/DeviceVerifier`; auth `repository/DeviceSessionStore`, `repository/AssertionCache`,
  `service/WalletLockService`, `service/SignedRequestCheck`; plus doc mentions in ErrorCodes (both), RequestSignatures, `ussd dto/UssdSession`, `transport/SmppUssdTransport`, `service/UssdEngine`.
- Testcontainers image `"valkey/valkey:8.1.11-alpine"`: `S/mw-app-gateway/src/test/java/mfs/mw/app/gateway/ValkeyInfrastructure.java:28` (class `JvmScopedValkey`,
  bean `valkey()` `@ServiceConnection(name="redis")`), `S/mw-auth/src/test/java/mfs/mw/auth/ValkeyInfrastructure.java:28` (same), `S/mw-ussd/src/test/java/mfs/mw/ussd/TestInfrastructure.java:69-75`
  (`VALKEY` static), `S/mw-orchestrator/src/test/java/mfs/mw/orchestrator/client/ValkeyIdempotencyLockIT.java:25`. Tests named Valkey*: `ValkeyIdempotencyLockIT/Test`.
  Other tests mentioning it: auth `DeviceBindingIT`, `LoginIT`, `PinLifecycleIT`, `TxnAuthorisationIT`, `CheckoutOtpIT`, `TestAccountIT`, `WalletLockKafkaIT`, `DeviceSessionStoreTest`...; ussd `UssdHttpFlowIT`, `UssdSmppMenuWalkIT`, `StatusReplyConsumerIT`.
- APISIX: `deploy/apisix/config.yaml:26-29` (`envs: - VALKEY_PASSWORD`). 19 route files with `limit-count` `"policy":"redis","redis_host":"valkey","redis_password":"$env://VALKEY_PASSWORD"`:
  routes/{app-v1,admin-ui,admin-v1,auth-v1,auth-v1-keys,auth-v1-pin-resets,auth-ops-browser,checkout-v1-sessions,checkout-v1-session-get,checkout-pay,portal-org-v1,
  partner-billagg-callback,partner-c2wbank-callback,ussd-http-{gp,robi,bl,teletalk}}.json, routes-test/{auth-ops-token,test-kc-admin-token}.json.
  Lua comments: `lua/auth-rate-key.lua:5`, `lua/edge-limit-degraded.lua:2,25`, `lua/ussd-msisdn-key.lua`. `deploy/apisix/README.md`.
- Scripts: `scripts/smoke.sh:7,23 (LONG_RUNNING list),104-108 (dc exec valkey valkey-cli ping + NOAUTH),270-276`; `scripts/edge-test.sh:5,21,25,39,127,206-210,335-354,379,406,469-486 (dc stop/start valkey, valkey-cli --scan)`;
  `scripts/creds.sh` (VALKEY_PASSWORD line); shell tests `scripts/test/test_apisix_edge.sh:409,483-486,495,653-656`, `test_platform_compose.sh`, `test_provision.sh`.
- Docs: `CLAUDE.md` (dev host ports line "Valkey 6379"), `README.md`, `deploy/RUNNING.html`, `docs/runbook.md`, `docs/demo.md`, `docs/testing-guide.html`, `docs/security-findings.md`,
  `docs/concepts/{apisix,openbao}.html` (no valkey page exists: concepts list = apisix camel camel-partner-routes.md core-mock greenmail hsm index jsonata keycloak openbao resilience4j shedlock simulators testcontainers),
  `docs/tutorial/{02-architecture,03-core-ideas,04-money-flows,glossary}.html`, `spec/bits-mfs-middleware-spec.md`, service CLAUDE.md of adapter-bank, adapter-billpay, app-gateway, auth, orchestrator, ussd.
  Also `contracts/api/mw-auth/auth-test.v1.openapi.yaml`, `tests/api/src/26-txn-assertion/txn-assertion.js` mention Valkey. ADRs D3, D7, D12, M7, M8 = history (leave).
- Trivy: `deploy/trivyignore-third-party.yaml` + `scripts/security.sh` (grep showed no literal valkey entries in trivyignore; check image list in security.sh).

## 3. Ticket 91 Vault swap: every OpenBao touchpoint
- Compose: service `openbao` L419-449 (image `openbao/openbao:2.7.2@sha256:5046911...`, port `${MW_PORT_OPENBAO:-8200}` L424, entrypoint `/openbao/dev/dev-unseal.sh` L427,
  env `OPENBAO_DEV_ROOT_TOKEN:-dev-root-token` L431, mounts `./openbao/config.hcl:/openbao/config/config.hcl`, `./openbao/dev-unseal.sh`, volume `openbao-data:/openbao/file` L185/435,
  healthcheck `bao status && test -f /openbao/file/dev-ready` L440, `OPENBAO_MEM:-128M` L448). `x-infra-deps` L139 `openbao: service_healthy`.
  `x-jvm-env` L147-150: `VAULT_ENABLED: ${VAULT_ENABLED:-true}`, `VAULT_ADDR: ${VAULT_ADDR:-http://openbao:8200}`.
  provision L665-720: depends `openbao` L679, `BAO_ADDR: ${VAULT_ADDR:-http://openbao:8200}` L687, `INIT_FILE: /openbao/file/dev-init.json` L688, volume `openbao-data:/openbao/file:ro` L719.
  test console L1312/L1330 `OPENBAO_DEV_ROOT_TOKEN`. Comments L29, 34, 39, 83, 134, 143, 561, 742, 767, 800.
- `deploy/openbao/config.hcl` (17 lines), `deploy/openbao/dev-unseal.sh` (71; writes dev-init.json, dev-token, dev-ready, enables engines).
- `deploy/provision/`: `Dockerfile` (FROM openbao image AS bao; `COPY --from=bao /usr/bin/bao`), `provision.sh` (requires `BAO_ADDR`; `bao_login`; `pki_setup`; `pki_role` per `PKI_SERVICES`;
  per `SERVICES` entry `provision_<svc>` + `approle`; `SECRETS_AHEAD`), `lib.sh` (`bao_login` L11, `has_field/field` L22-23 `bao kv get`, `kv_set` L26 patch/put,
  `gen_once` L32, `sync_env` L42, `db_role` L54, `pki_setup` L81-100 (mounts `pki/` + `pki_int/`, ttl 87600h/720h, root + intermediate CA), `pki_role` L106
  (`pki_int/roles/<svc>` allowed_domains=<svc>), `approle` L123-135 (policy `secret/<svc>` + `secret/<svc>/*` + `pki_int/issue/<svc>`; writes
  `$OUT/spring.cloud.vault.app-role.role-id|secret-id` files), `partner_sim_client_key` L147), `services.sh` (189 lines; SERVICES, PKI_SERVICES, per-service functions).
  KV mount `secret/` (KV v2), per-partner secrets `secret/mw-adapter-<x>/<partnerId>` (ADR D26/D28), smoke secret `secret/mw/smoke`.
- Spring (all 15 non-seeder services, `application.yml` L10-25 same block): `spring.config.import: "optional:configtree:${MW_APPROLE_DIR:/run/mw/approle/},optional:vault://"`,
  `spring.cloud.vault.{enabled: ${VAULT_ENABLED:false}, uri: ${VAULT_ADDR:http://openbao:8200}, authentication: APPROLE, app-role.role-id/secret-id: ${VAULT_ROLE_ID:}/${VAULT_SECRET_ID:}, kv.backend: secret, kv.default-context: ${spring.application.name}}`.
  Comments "OpenBao/Vault" on L2 and L30 ("dynamic from OpenBao's database engine").
- PKI/mTLS (ticket 66, D24): `<pkg>config/ServiceCertificateManager` (VaultOperations `opsForPki(props.pkiMount()).issueCertificate(props.role(), req)` ~L196-202),
  `config/TlsProperties` (`mw.tls.pki-mount: pki_int`, `role: ${spring.application.name}`), in auth, app-gateway, audit-dw, checkout, ussd, crypto, admin-api, template.
  Tests `PkiTestAuthority` (openbao container) in app-gateway, audit-dw, auth, checkout, crypto, ussd, template.
- Testcontainers `"openbao/openbao:2.7.2"` (22 uses): every `config/VaultConfigBindingIT` (adapter-bank/billpay/telco, admin-api, app-gateway, audit-dw, auth, checkout, core-mock, crypto,
  notify, orchestrator, portal-api, simulators, ussd, template) + the PkiTestAuthority files + `S/mw-adapter-billpay/.../partner/route/NewBillerRouteIT`.
  Typical shape: `static final GenericContainer<?> OPENBAO = new GenericContainer<>("openbao/openbao:2.7.2")` + dev root token env + `Wait`, test name `secretFromOpenBaoBindsToServiceProperties`.
- Adapters (3 copies identical): `partner/auth/PartnerCredentials` (Camel `{{hashicorp:secret:<service>/<id>#<field>}}`), `partner/auth/SpringVaultPropertiesFunction`,
  `partner/CredentialRef`, `partner/CredentialResolver`, `partner/auth/DetachedJws`, `partner/config/{PartnerConfig,PartnerTlsProperties,PartnerProperties}`, `partner/route/{PartnerRouteLoader,PartnerRuntime,PartnerBatchRoutes}`;
  application.yml `camel.vault.hashicorp.refresh-enabled: false` (~L125-130); kamelet YAML descriptions `src/main/resources/kamelets/mw-auth-*.kamelet.yaml`, `mw-http-call`, `mw-soap-call`, `mw-sftp-*`;
  `application-dev.yml` comments (billpay L32, L51). Test helper `FakeVaultFunction`, `TestKeystores`, `PartnerAuthKameletsIT`, `SftpReconRouteIT`.
- mw-crypto: `config/CryptoProperties`, `HsmStack`, `Pkcs11Provider`, `SecurityConfig`, `README.md`; `deploy/softhsm/README.md`. mw-simulators: `client/CallbackSigner`, `config/PayShieldProperties`, `client/Pkcs11LmkStore`, `UssdGatewayClient`.
- Scripts: `scripts/creds.sh:5-6,18-25 (dc exec openbao cat /openbao/file/dev-token | dev-init.json),67-68`; `scripts/smoke.sh:10,23,104-140 (bao_sh helper L114: BAO_TOKEN from dev-init.json; checks kv secret/mw/smoke, pki_int roles, issue mw-admin-api),191-196,230`;
  `scripts/security.sh:162`; `deploy/reset.sh:3`; shell tests `test_creds.sh`, `test_platform_compose.sh`, `test_provision.sh`, `test_repo_docs.sh`.
- dev.env.defaults: L7 comment, L50-52 `OPENBAO_DEV_ROOT_TOKEN=dev-root-token`, L71. UI: `ui/mw-test-console/src/lib/secrets.ts:16` (env `OPENBAO_DEV_ROOT_TOKEN`), `account-tools.test.ts`, `ui/mw-test-console/CLAUDE.md`, `ui/mw-admin-ui/src/lib/server/env.ts:2`.
- Other mentions: `.trivyignore.yaml`, `deploy/trivyignore-third-party.yaml`, `deploy/apisix/consumers/ussd_*.json` desc, `deploy/partner-routes/examples/*.yaml` comments,
  contracts `api/mw-admin-api/admin.v1`, `api/mw-auth/auth-internal.v1`, `api/mw-crypto/crypto-internal.v1`, `api/mw-orchestrator/txn-query.v1` (descriptions), orchestrator `V8__query.sql`/`V9__...sql` comments (don't edit applied migrations),
  admin-api `V5__partner_config.sql` comment, docs/concepts/openbao.html (rename -> vault), all docs listed in ticket. ADRs D9/D12/D16/D17/D23/D24/D28/M7/M8/M11: leave text, mark "amended by D30".

## 4. Tickets 92-95: core command path today
- Contract ENVELOPE `contracts/events/mw-orchestrator/core.command.v1.schema.json` (owner orchestrator) is carried on TWO physical topics
  `core.wallet.command.v1` / `core.payment.command.v1`; events `contracts/events/mw-core-mock/core.event.v1.schema.json` (owner core-mock) on `core.wallet.event.v1` / `core.payment.event.v1`.
  (Ticket 95 text names both forms; retire the physical topics in `deploy/kafka/topics.txt` and the `core.command.v1*` contract files.)
- Command payloads `contracts/events/mw-orchestrator/core.command.v1/<Msg>.payload.schema.json`: ApplyComplianceAction, BalanceInquiry, CommitHold, CreditWallet, HoldFunds, PostTransfer,
  QueryTransaction, ReleaseHold, ReverseTransaction, UnlockWallet, ValidateTransaction, VerifyCustomerFactors. Samples `contracts/events/mw-orchestrator/samples/core.command.v1/<Msg>/`.
- Event payloads `contracts/events/mw-core-mock/core.event.v1/`: BalanceInquiryRejected, BalanceReported, CommitFailed, ComplianceActionApplied/Rejected, CreditRejected,
  CustomerFactorsRejected/Verified, FundsHeld, HoldCommitted, HoldRejected, HoldReleased, ReleaseFailed, ReversalRejected, TransactionRejected, TransactionReversed,
  TransactionStatus, TransactionValidated, TransferPosted, TransferRejected, WalletCredited, WalletStateChanged, WalletUnlocked, UnlockRejected. (No HoldExpired yet: 94 adds it.)
- `contracts/common/defs.v1.schema.json` (owner orchestrator): `$defs` SchemaVersion L7, TxnId L12, CorrelationId L17, IdempotencyKey L23, CommandId L29, Amount L35, Msisdn L41, Party L46,
  PartnerRef L57 (required partnerId, productCode), EncryptedPin L66, TxnAssertion L75, Channel L82, TxnType L85-88 (P2P..BALANCE; 92 adds NPSB_OUT, NPSB_IN, B2B_TRANSFER), Locale, StringMap.
- Producers of core commands:
  - mw-orchestrator: `<pkg>service/SagaEngine.perform()` L212-255 -> `send()` L338-345 = `outbox.append(CoreTopics.commandTopic(type), txnId, EventMetadata, cmd)` + `coreCalls.sent(...)`.
    `service/CoreTopics` (HoldFunds/CommitHold/ReleaseHold -> payment topic, rest wallet). Commands built by `mapper/CoreCommandMapper` (validate, balanceInquiry, post, hold, commit(round),
    credit(round), creditCheck, release(round), query, reverse, reversalQuery). DTOs `dto/CoreCommand`, `HoldPayload`, `TransferPayload` (carries `assertion` + `idempotencyKey`), `CreditPayload`, `QueryPayload`, `ReversePayload`.
    Timeline repo `repository/CoreCallRepository`. TTL `mw.orchestrator.command-ttl: 60s`.
  - mw-auth: `dto/VerifyCustomerFactorsCommand`, application.yml L280-281 `command-topic: core.wallet.command.v1`; answers via `messaging/CoreWalletEventListener` (also WalletStateChanged projection:
    `service/WalletProjectionService`, `mapper/WalletEventMapper`, `dto/CustomerFactorsAnswer`, Flyway `V4__auth_model.sql`).
  - mw-admin-api: `service/ChangeRequestService:72` `CORE_COMMAND_TOPIC = "core.wallet.command.v1"`, L442-461 UnlockWallet (+ `auth.wallet-lock.v1` UNLOCK), `dto/UnlockWalletCommand`, `mapper/ChangeRequestMapper.unlock`.
  - ApplyComplianceAction: contract only, no producer in code; core-mock logs and ignores it (`S/mw-core-mock/contracts.yml` header).
- Consumers of replies: orchestrator `messaging/CoreEventListener:28-30` (`topics = {CoreTopics.WALLET_EVENTS, PAYMENT_EVENTS}`) -> `service/CoreEventService` (229 lines) -> `mapper/CoreEventMapper`
  (L27 `ASSERTION_REFUSED = "MW-AUTH-046"`, L143) -> `SagaEngine.fire(rec, SagaEvent, ErrorInfo[, Balance])` L119/128. `service/InquiryService` (194) + `saga/CoreTxnStatus` map core QueryTransaction answers.
  Orchestrator does NOT consume WalletStateChanged today (95 adds HoldExpired + wallet-state listener).
- Assertion carriage today: channel gets assertion from mw-auth -> `mw.txn.request.v1.assertion` (schema L24-26) -> orchestrator `dto/TxnRequest:32` (`@Sensitive(SECRET) String assertion`)
  -> `dto/TransferPayload.of(req)` L23-26 copies assertion + idempotencyKey into ValidateTransaction, HoldFunds, PostTransfer, BalanceInquiry payloads -> core-mock
  `service/AssertionCheck.check(TransferPayload, Wallet, txnId)` L56 (JWKS `client/AssertionJwksClient`, keys `security/AssertionKeys`, refresh `scheduler/AssertionKeyRefreshJob`,
  used jtis `repository/UsedAssertionRepository` + Flyway V8 `used_assertion`, `util/TxnHash` recompute L132) -> `Reason.ASSERTION_INVALID("MW-AUTH-046")`.
  Config `mw.coremock.assertion.{jwks-uri: http://mw-auth:8080/auth/v1/.well-known/assertion-jwks.json, issuer: mw-auth, audience: core, clock-skew 30s, refresh-period 30s, used-retention 1d}`.
- mw-core-mock (`S/mw-core-mock/<pkg>` = `mfs/mw/core/mock/`):
  - `messaging/CoreCommandListener` (`WALLET_TOPIC`/`PAYMENT_TOPIC` L21-22, `handleWallet` L35, `handlePayment` L45) -> `service/CoreCommandService.handle(body, eventTopic)` L119-135;
    `execute()` L208-221 switch: HoldFunds/CommitHold/ReleaseHold -> `HoldOperations.hold/commit/release` (L57/77/106), CreditWallet/ReverseTransaction -> `PostingOperations.credit/reverse` (L46/79),
    QueryTransaction -> `StatusOperations.query` L50, UnlockWallet, VerifyCustomerFactors, BalanceInquiry, default -> transfer (Validate/PostTransfer; `TransferRules`).
    Events appended to outbox with fault handling L195-205 (`EVENT_TOPIC`=`core.wallet.event.v1` L44, `PAYMENT_EVENT_TOPIC` L45). Idempotency by commandId: `repository/CommandLogRepository`.
  - Reasons `service/Reason` (enum, package-private): WALLET_NOT_FOUND, CREDIT_WALLET_NOT_FOUND, ACCOUNT_BLOCKED, ACCOUNT_LOCKED, INVALID_COUNTERPARTY, CREDIT_ACCOUNT_BLOCKED, SAME_WALLET,
    INVALID_AMOUNT, LIMIT_EXCEEDED_PER_TXN, LIMIT_EXCEEDED_DAILY, INSUFFICIENT_BALANCE, HOLD_NOT_FOUND, HOLD_RELEASED, HOLD_ALREADY_COMMITTED, ORIGINAL_NOT_FOUND, ORIGINAL_NOT_REVERSIBLE,
    ALREADY_REVERSED, AMOUNT_MISMATCH, WALLET_NOT_LOCKED, FACTORS_MISMATCH, WALLET_NOT_ACTIVE, ASSERTION_INVALID. Orchestrator maps to `MW-CORE-<reason>`.
  - REST today: `controller/WalletController` `GET /core-mock/v1/wallets/{walletId}` (`service/WalletBalances`), `controller/WalletSeedController` `PUT /core-mock/v1/wallets/{walletId}`
    (`mw.coremock.seed-api.enabled`, env `MW_COREMOCK_SEED_API_ENABLED` compose L758), `controller/FaultController` `/core-mock/v1/faults` POST/GET/DELETE/DELETE{id} (`service/FaultRegistry`).
    Contract `contracts/api/mw-core-mock/core-mock-seed.v1.openapi.yaml`. New `core-wallet.v1.openapi.yaml` needs an OWNERS.yml line + `contracts.yml` `apis:`.
  - Entities `entity/Wallet(id, msisdn, type, status, balanceMinor, heldMinor, holderName)`, `Hold`, `LedgerEntry`, `WalletState`. Schema `coremock`.
    Wallet type CHECK in `V4__ledger.sql:7` ('CUSTOMER','AGENT','MERCHANT','ORG','SYSTEM'); status CHECK in `V6__wallet_types_pin_lockout.sql:4-6` ('ACTIVE','BLOCKED','HOLD','LOCKED').
    Migrations V1 baseline, V2 outbox_inbox, V3 shedlock, V4 ledger, V5 holds_reversals_faults, V6, V7 wallet_version, V8 used_assertion, V9 drop_core_pin, V10 holder_name, V11 balance_adjustment.
  - Config `mw.coremock.*` (application.yml L168-220, `config/CoreMockProperties` record): `fees` flat per txnType (P2P 500, CASH_OUT 1000, TOPUP 200, BILL_PAY 300, others 0),
    `limits.per-txn-minor 500000`, `daily-minor 1500000`, `wallet-types.ORG.daily-minor`, `seed.{enabled, customer/agent/merchant/org-balance-minor}`, `wallet-types` per txnType debit/credit, `wallet-snapshot-on-start` (`MW_COREMOCK_WALLET_SNAPSHOT`).
  - Seed `service/WalletSeeder` (seed L49, reset L72): CUSTOMER 1000 `0171100xxxx` (01711000001..), AGENT 50 `0181100...`, MERCHANT 20 `0191100...`, ORG 2 `0131100...`,
    SYSTEM `FEE-POOL` (L24 `FEE_WALLET`) and `SETTLEMENT` (L27, `SETTLEMENT_SEED_MINOR`). `service/WalletStateEvents` publishes WalletStateChanged.
- Orchestrator has resilience4j already (build.gradle.kts L35, L53) but NO RestClient/client to core yet (`client/` = DlqClient, IdempotencyLock, ValkeyIdempotencyLock). Virtual threads on (`spring.threads.virtual.enabled`).
- Other services with a RestClient + Resilience4j + mTLS pattern to copy: app-gateway/ussd/checkout `client/AuthClient` + `AuthClientProperties` (+ `AuthClientIT`), auth `client/CryptoClient`.

## 5. Tickets 96/98: saga engine
- `<pkg>service/saga/` (orchestrator): `Status` (14 states: RECEIVED VALIDATING HOLDING POSTING PARTNER_PENDING UNKNOWN COMMITTING RELEASING REVERSING COMPLETED FAILED REJECTED REVERSED MANUAL_REVIEW; `terminal()`),
  `Step` (VALIDATE POST HOLD CALL_PARTNER COMMIT RELEASE REVERSE QUERY INQUIRE BALANCE_INQUIRY; `of(Effect)`), `Effect` (NONE SEND_VALIDATE SEND_HOLD SEND_POST SEND_COMMIT SEND_CREDIT SEND_RELEASE
  SEND_REVERSE SEND_REVERSAL_QUERY SEND_QUERY SEND_PARTNER_REQUEST SCHEDULE_INQUIRY SEND_INQUIRY SEND_BALANCE_INQUIRY), `SagaEvent` (sealed records: ValidationStarted, RequestRejected, BalanceInquiryStarted,
  BalanceReported, BalanceInquiryRejected, Validated(needsHold, callPartner), ValidationRejected, HoldPlaced, HoldRejected, HoldQueryResult(held), PostSucceeded, PostRejected, PartnerSucceeded,
  PartnerFailed(held), PartnerUnknown, InquirySucceeded(held), InquiryFailed(held), InquiryExhausted, CommitAcknowledged, CommitRefused, ReleaseAcknowledged, ReleaseRefused, ReversalRequested,
  ReversalAcknowledged, ReversalFailed, ReversalResendsStopped, ReversalUnconfirmed, ReversalQueryResult(reversed), ManualCommit, ManualRelease, ManualInquiry, CreditCheckResult(Credit), DeadlineExpired),
  `SagaMachine.apply(Status, SagaEvent) -> Transition` (pure, L15-131; MANUAL_REVIEW entered from UNKNOWN/InquiryExhausted L79, COMMITTING/CommitRefused L88, CreditCheck CONFLICT L95, RELEASING/ReleaseRefused L101,
  REVERSING/ReversalFailed L108, ReversalQueryResult(false) L113), `Transition`, `Outcome`, `Flow` (interface: txnType, needsHold, partnerCall, creditsWallet, allows, steps), `FlowRegistry` (by txnType, `find`),
  `PartnerCall(family, topic)`, `InquirySchedule` (record delays/repeatEvery/cutOff; DEFAULT 30s,1m,2m,5m,15m,1h then 6h until 24h; `overriddenBy` per partner), `CoreTxnStatus`.
- Flows: `P2pFlow` (validate+post), `CashInFlow`, `CashOutFlow`, `MerchantPayFlow`, `BulkDisburseFlow`, `BalanceFlow`, `TopupFlow` (`TELCO = PartnerCall("telco","mw.partner.request.telco.v1")`),
  `BillPayFlow` (billpay), `AddMoneyBankFlow` (bank, creditsWallet). Reversal today = core-only `ReverseTransaction` (REVERSE step allowed for all flows, `Flow.allows`).
- `service/SagaEngine` (346): `created` L106, `fire` L119/128, `refuseStepOutsideFlow` L157, `publishStatus` L180 (topic `mw.txn.status.v1`), `audit` L189, `rearm` L195 (timers), `perform` L212,
  `scheduleFirstInquiry` L269 (timer step `INQUIRY_TIMER = "UNKNOWN"`), `inquire` L282 (partner STATUS_QUERY vs core query), `callPartner` L319, `sendToPartner` L324-336 (outbox to `call.topic()` + `partnerCalls.sent`).
  Partner id = `rec.request().partner().partnerId()` (L275) -- channel supplies it today (no routing).
- Timers: `repository/SagaTimerRepository` (table `saga_timer(txn_id, step, due_at, attempt)` V4), `scheduler/SagaTimerJob`, `service/SagaTimerService`. Deadlines `mw.orchestrator.{validating,holding,posting}-timeout 5s,
  partner-timeout 30s, retry-timeout 5s, reversing-timeout 60s, reversal-channels SYSTEM, bulk-channels ORG_PORTAL, manual-review-ageing-cron` (application.yml L202-225).
- Inquiry config `config/InquiryProperties` `@ConfigurationProperties("mw.orchestrator.inquiry")` L27-36: `schedule` default {30s,1m,2m,5m,15m,1h}, `repeatEvery 6h`, `cutOff 24h`, `notFoundGrace 30s`, `PartnerOverride`.
  Compose L934 `MW_ORCHESTRATOR_INQUIRY_SCHEDULE:-30s,1m,2m,5m,15m,1h`; dev.env.defaults L95-106 (`=6s,2s,5s,10s,30s,1m`, POSTING/HOLDING 2s, PARTNER 8s, NOT_FOUND_GRACE 2s, REVERSING 5s).
- Alerts: `service/SagaAlerts` (MeterRegistry; `ACK_ALERT_AFTER 60s`, `stillUnacknowledged` L68, `refused` L86, `reversalRetriesExhausted` L100, daily ageing L109). `service/ManualReviewAgeingService` + `scheduler/ManualReviewAgeingJob`.
- Partner request/reply: `mapper/PartnerRequestMapper` (`PAY`, `STATUS_QUERY` consts L22-23; `requestId(txnId, op)` = nameUUID of `<txnId>:<op>` L26; `partnerTxnRef` L31; `pay` L36; statusQuery requestId `<txnId>:STATUS_QUERY:<epochMs>`),
  `messaging/PartnerReplyListener` -> `service/PartnerReplyService` -> `mapper/PartnerReplyMapper`; `messaging/PartnerInboundListener` -> `service/PartnerInboundService`; `repository/PartnerCallRepository`.
  Contract `mw.partner.request.v1/shared.payload.schema.json` operation enum ALREADY has `PAY, INQUIRY, STATUS_QUERY, REVERSE, SCREEN` (L9; REVERSE requires amount L23); reply enum same + outcome SUCCESS/FAILED/UNKNOWN/PENDING/NOT_FOUND.
  Request topics `mw.partner.request.{telco,billpay,bank}.v1`; reply `mw.partner.reply.v1`.
- Intake: `messaging/TxnRequestListener` -> `service/TransactionService` (298) -> `service/IdempotencyGate` -> `repository/IdempotencyRepository`; ops actions `messaging/TxnActionListener` -> `service/OperatorActionService`.
  Errors: `exception/ErrorCodes` (`MW-IDEM-001` L21; MW-VAL-010 = unsupported type per ticket 92), `exception/GlobalExceptionHandler`, `dto/ErrorEnvelope`.
- Test-only API `controller/TestApiController` `/test/v1/**` (`mw.orchestrator.test-api.enabled`, env MW_ORCHESTRATOR_TEST_API_ENABLED): reversals, txn-requests/{key}, poison-requests, dlq-records (APISIX `/test/orchestrator/v1/*`).
- For 96 notify rule: mw-notify `service/RuleEngine` (SpEL over `notify.rule` rows vs `mw.txn.status.v1`), seeds in Flyway `V5__seed_templates_rules.sql` (next V13), templates `TemplateRenderer`.
- For 98 config: `contracts/events/mw-admin-api/mw.config.changed.v1.schema.json` `configType` enum L15 = PARTNER, ROUTE, TEMPLATE, USSD_MENU, LIMIT, FEATURE_FLAG, ERROR_MAP (add ROUTING_RULE etc.; `configKey` = `<configType path>/<id>`).
  mw-admin-api change-request engine: `service/ChangeRequestService` + `service/ResourceChangeHandler` interface (`resourceType()`, `checkers()`, `precheck`, `prepare`, `settle`, `reconcile`, ...);
  implementations `PartnerChangeHandler` (+ `PartnerConfigService`), `TemplateChangeHandler`, `UssdMenuChangeHandler`, `ContentChangeHandler`, `KeyChangeHandler`. mw-orchestrator does not consume `mw.config.changed.v1` today
  (topics.txt retry groups: mw-ussd, mw-notify; add mw-orchestrator). Channel partnerId: `contracts/api/mw-app-gateway/app.v1.openapi.yaml` L506, L548-550 (`required: [partnerId, productCode]`, `[partnerId]`); PartnerRef L59 in defs.

## 6. Ticket 97: adapters partner/ engine today (3 identical copies; billpay is the reference)
- `S/mw-adapter-billpay/src/main/java/mfs/mw/adapter/billpay/partner/`: SPI `AuthScheme, CredentialRef, CredentialResolver, InboundCall, InboundResult, OutboundCall, PartnerCall, PartnerResponse (enum NotSent incl. CIRCUIT_OPEN L90), PartnerFrameworkConfiguration`;
  `config/` PartnerConfig (record: route, mappings, allowedHosts, family, headers, errorMap, ambiguousCodes, sensitiveFields...; `DRY_RUN_OPERATIONS = {PAY, INQUIRY, STATUS_QUERY}` L81; no operations set field since ticket 84 -- ops are `choice` branches on `${header.mwOperation}` in the route YAML),
  PartnerConfigKafkaListener, PartnerConfigStore, PartnerEndpointPolicy, PartnerProperties, PartnerTlsProperties; `route/` PartnerGateway (`call` L82, `dryRun` L107, `inbound` L175), PartnerRouteHealth
  (HealthIndicator; `state()` L37-46: DELETED / DISABLED / UP (route started) / DOWN), PartnerRouteStatus, PartnerRoutes, PartnerRouteLoader (route must start `direct:partner-<id>` L376), PartnerAudit (`MAX_BODY = 4096` L25, `record(...)` L84),
  PartnerDryRun, PartnerHttpComponent (`.disableAutomaticRetries()` L46), PartnerExchange, PartnerResult, PartnerRecon, PartnerBatchRoutes, PartnerHostGuard, PartnerDnsResolver, KameletCatalogue, QueryParams;
  `kamelet/` MwAuditStep, MwAuthStep, MwClassifyStep, MwHttpStep (circuit breaker short-circuit -> `PartnerResult.notSent(CIRCUIT_OPEN)` L165-169), MwMapStep, MwReconStep, MwSftpSinkStep, MwSoapStep;
  `auth/` ApiKey/Hmac/No auth schemes, OAuth2ClientCredentials, PartnerTlsContexts, DetachedJws, PartnerCredentials, SpringVaultPropertiesFunction; `mapping/` JSONata + XSLT.
- Service-side: `messaging/BillpayRequestHandler` (consumes request topic), `messaging/PartnerAuditPublisher`, `PartnerRouteStatusPublisher` (publishes `mw.partner.route.status.v1`, status enum LIVE/REJECTED only, L13; owner billpay),
  `mapper/PartnerReplyMapper` (CIRCUIT_OPEN -> `MW-SYS-PARTNER_CIRCUIT_OPEN` L68), `service/BillPaymentService`, `BillFetchService` (txnId null -> audit dropped: open item), `PartnerCallbackService`, `PartnerReferenceService`.
  Telco/bank equivalents named per family. Kamelet YAML `src/main/resources/kamelets/mw-*.kamelet.yaml`.
- Grep for "reverse" in adapters: no REVERSE handling anywhere (only mapping docs). No existing script diffs the 3 engine copies (file sets are identical today; 97 must add the check in `scripts/test/`).
- Seeds: `S/mw-admin-api/src/main/resources/partner-seeds/dev/{ocs,billagg,c2wbank}.json` (`{partnerId, family, config}`); example routes `deploy/partner-routes/examples/*.yaml` (rest-apikey-biller.yaml = choice on mwOperation PAY/STATUS_QUERY/INQUIRY).
- Adapter Flyway next = V6 (all three at V5) for `partner_payload`.

## 7. Ticket 99: idempotency per channel today
- Orchestrator (the decider): table `txn.idempotency(channel, initiator_id, idem_key, request_hash bytea, txn_id, expires_at)` PK (channel, initiator_id, idem_key) (`V4__saga.sql:60-69`);
  `response` column dropped in `V7__drop_idempotency_response.sql` (repeat answered from live txn row). TTL `mw.orchestrator.idempotency-ttl: 72h`. Valkey double-tap lock `IdempotencyGate.enter(channel, initiatorId, key, txnId, hash)` L67,
  key = SHA-256 of scope L110, value `<txnId> <hash>`, ttl 60s; fail-open. Duplicate guard (PLT-013): none exists.
- mw-app-gateway: header `Idempotency-Key` (`controller/TransactionController:34`), `service/TransactionService.submit(user, key, body)` L71 (gets assertion from mw-auth, publishes, waits on `ReplyWaiters` / `TxnReplyExchange`),
  `repository/AppTransactionRepository`. Device signing `security/DeviceBindingInterceptor` (headers X-Device-Id, X-Timestamp, X-Nonce, X-Signature; nonces in Valkey via mw-auth / DeviceGateStore). Errors `MW-IDEM-001`.
- mw-checkout: `exception/ErrorCodes:39` `IDEM_ORDER_CONFLICT = "MW-IDEM-002"`; `security/` = IpnSigner, IpnUrlPolicy, ResultSigner only (no inbound HMAC check in service: APISIX `hmac-auth` on
  `routes/checkout-v1-sessions.json` with `clock_skew: 300`, signed headers `@request-target, date, digest`, `validate_request_body`; consumer name -> `X-Mw-Merchant-Id` (`controller/MerchantSessionController:27`)). No nonce.
- Partner callbacks: adapters `partner/auth/HmacSha256AuthScheme` (X-Signature, X-Timestamp, X-Key-Id; `max-skew-seconds` default 300; constant-time) -- timestamp window exists, NO nonce.
  Signer `S/mw-simulators/.../client/CallbackSigner.java:20,46` (X-Signature, X-Timestamp). APISIX callback routes: ip-restriction + client-control + limit-count keyed `remote_addr`.
- mw-auth: `MW-IDEM-001` (`exception/ErrorCodes:73`), assertion idempotency `assert:{walletId}:{key}` (`repository/AssertionCache`).
- Edge 429: `deploy/apisix/lua/edge-error-headers.lua` CODES table (429 -> MW-SYS-RATE_LIMITED, retryable) -- no Retry-After. limit-count fixed window per route (`app-v1` keyed per token subject; checkout per `consumer_name`, count 600/60s).

## 8. Ticket 100: APISIX today (`deploy/apisix/`, image apache/apisix:3.19.0-debian, etcd v3.6.16)
- `apply.sh` (one-shot `apisix-init`, compose L538; `apisix-init-tester` L587): PUTs SSL `edge-default`, `global_rules/edge.json`, `consumers/*.json` (only `ussd_{gp,robi,bl,teletalk}` basic-auth, password `@env:USSD_<MNO>_GATEWAY_SECRET`),
  `routes/*.json`; deletes ids in `retired.txt`; `MW_E2E_ROUTES=1` adds `consumers-test/` (`mw_e2e` key-auth `@env:MW_E2E_API_KEY`, `merchant_{demo,fashion,pharmacy}` hmac-auth) and `routes-test/`.
  Placeholders `@lua:<path>`, `@env:<NAME>`, `@list:<NAME>`. `APISIX_DRY_RUN=<dir>` writes bodies offline (used by `scripts/test/test_apisix_edge.sh`). Admin API `apisix:9180`, key `APISIX_ADMIN_KEY`.
- Routes: admin-ui, admin-v1, app-v1 (`openid-connect` on mw-auth discovery + serverless + limit-count), auth-ops-browser, auth-portals-token, auth-v1(-keys, -pin-resets), checkout-pay, checkout-v1-session-get, checkout-v1-sessions,
  healthz, partner-billagg-callback (`/partner/billagg/callback/*`), partner-c2wbank-callback, portal-org-v1, ussd-http-{bl,gp,robi,teletalk}.
  Test routes: auth-ops-token, test-audit-dw, test-auth, test-console, test-core-mock (`/test/core-mock/v1/*` -> `$1`, mw-core-mock:8080), test-crypto, test-kc-admin(-token), test-mail, test-orchestrator (`/test/orchestrator/v1/*` -> `/test/v1/*`), test-simulators (`/test/sim/*` -> `/sim/*`).
  Test routes use ip-restriction + key-auth + consumer-restriction + proxy-rewrite.
- Lua: auth-rate-key, consumer-from-jwt, edge-error-body, edge-error-headers, edge-limit-degraded, edge-request, edge-security-headers, ops-token-grant, ussd-msisdn-key.
- Keycloak realms `deploy/keycloak/realms/{ops,partners,portals,services}-realm.json`; partners realm has client `partner-sim` (`clientAuthenticatorType: client-jwt` = private_key_jwt, L31-39). Smoke checks it (`scripts/smoke.sh:191-196`, key in `secret/mw-simulators` field `sim.partner-sim.private-key`).
- mw-admin-api: no APISIX client today; change-request pattern in §5; contract `contracts/api/mw-admin-api/admin.v1.openapi.yaml`. Live tests: `scripts/edge-test.sh` (+ offline `scripts/test/test_apisix_edge.sh`).

## 9. Ticket 101: OpenAPI today
- No springdoc in any module/template. Contracts `contracts/api/<owner>/*.openapi.yaml` (OpenAPI 3.1): adapter-billpay billpay.v1; admin-api admin.v1; app-gateway app.v1; audit-dw audit-query.v1, audit-dw-test.v1;
  auth auth.v1, auth-internal.v1, auth-test.v1; checkout checkout.v1; core-mock core-mock-seed.v1; crypto crypto-internal.v1, crypto-test.v1; orchestrator txn-query.v1, orchestrator-test.v1; portal-api portal-org.v1; ussd ussd-http.v1.
  No contract for mw-simulators (102 adds mock-control.v1) or telco/bank adapters.
- Existing contract tests to extend: `src/test/java/mfs/mw/<svc>/contracts/{OpenApiSchemas,ApiContractsTest}` in adapter-billpay, admin-api, app-gateway, audit-dw, auth, checkout, core-mock, crypto, orchestrator, portal-api, seeder, simulators, ussd
  (OpenApiSchemas: YAML -> JSON, registered under contracts id, JSON pointer `component()`/`response()`). `contracts/README.md` exists (layout section; add versioning policy there).

## 10. Tickets 102/103: mocks today
- mw-simulators (`S/mw-simulators/src/main/java/mfs/mw/simulators/`): Partner endpoints: `OcsController` `POST /ocs/v1/topup`, `GET /ocs/v1/topup/{transactionId}`; `BillAggregatorController` `GET /billagg/v1/billers`, `POST /billagg/v1/bills/fetch`,
  `POST /billagg/v1/payments`, `GET /billagg/v1/payments/{id}`; `BillAggregatorSoapController` `POST /billagg/v1/soap`; `SoapBillerController` `POST /soapbill/v1` (+ `/sim/soapbill/fault` POST/DELETE);
  `BankC2wController` `POST /c2wbank/v1/debits`, `GET .../{id}`; `MerchantIpnController` `POST /merchant/ipn/**`.
- Control (`/sim/*`): `ControlController` `POST|GET|DELETE /sim/{partner}/scenario` L37-48, `GET|DELETE /sim/{partner}/requests` L54-61, `POST /sim/{partner}/callbacks/{reference}/replay` L68 (partners `sim.partners: ocs,billagg,c2wbank` application.yml L172);
  `PartnerStubController` `/sim/stubs/{partnerId}` POST/GET/DELETE, `/requests`, catch-all `/sim/partners/{partnerId}/**`; `MerchantIpnController` `/sim/merchant/ipn/scenario`, `/received` GET/DELETE;
  `SmscController` `/sim/smsc/messages` GET/DELETE; `UssdGatewayController` `/sim/ussdgw/sessions`, `/smpp/scenario`, `/smpp/drop`, `/smpp/binds`; `PayShieldController` `/sim/payshield/pinblock`, `/scenario` POST/GET/DELETE.
- Scenario model `dto/Scenario(forceMode SimMode, latencyMs, errorRate, outage, timeoutDelayMs, callbackMode, callbackUrl, callbackDelayMs, callbackOnly)`; `dto/SimMode` SUCCESS, BUSINESS_FAILURE, TIMEOUT, SERVER_ERROR_AFTER_SEND, UNKNOWN_CODE, MALFORMED;
  `dto/CallbackMode` NONE, ONCE, DUPLICATE, LATE, (+ out-of-order); stores `service/ScenarioStore`, `PartnerStubStore`, `PayShieldScenarioStore`, `OcsScenarioResolver`; journal `filter/RecordingFilter` + `service/RequestRecorder` (`sim.record-limit 1000`);
  callbacks `service/CallbackService` + `client/CallbackClient` + `CallbackSigner`. SMPP servers `client/SmscServer`, `client/UssdSmppGateway`, `client/PayShieldServer`. Ledgers `OcsLedger`, `BillPaymentLedger`, `BankDebitLedger`.
- Magic values: `service/MagicAmounts` amount fraction (poisha %100): .13 TIMEOUT, .77 SERVER_ERROR_AFTER_SEND, .99 UNKNOWN_CODE, .55 MALFORMED; OCS MSISDN ending 999 = business failure (`OcsScenarioResolver:19`);
  billagg bill ending 404 fetch not found (3001), 000 already paid (3002), 999 payment refused (`BillAggregatorService:100-104,175`); c2wbank account ending 999 refused 4002 (`BankC2wService:100`). No reverse endpoint on any simulator.
  Config `sim.*` (application.yml ~L165-200: timeout-delay 30s, record-limit, partners, smsc, ussd-gateway, payshield). No `mock-control` profile anywhere today; dev profile set via `SPRING_PROFILES_ACTIVE:-dev` on adapters/admin-api.
  Simulators Flyway V3 (next V4); seeds today in code/config, no `src/main/resources/seed/`.
- mw-core-mock faults: `dto/Fault(id, mode, commandType, txnId, delayMs, reasonCode, remaining)` modes DELAY, DROP_REPLY, DUPLICATE_REPLY, REJECT; `service/FaultRegistry`; applied in `CoreCommandService` L195-205 (outbox `availableAt` delay, double append).
  No magic values in core-mock (behaviour from balances/limits/rules). Test console: `ui/mw-test-console/src/app/simulators/{page,forms,preset-button}.tsx`.

## 11. Ticket 104: Newman suite (`tests/api/`)
- Files: `lib.js` (906 lines, NOT src/lib.js -- ticket text is wrong), `build_collection.mjs` (134; `--check` = stale check), `mfs-mw.postman_collection.json` (generated, committed), `run.sh`, `run_customer.mjs`,
  `TRACEABILITY.md` (225), `PARITY.md`, `README.md`, `fixtures/*.json`, `local.postman_environment.json`. Folders `src/00-health .. 29-balance`, `90-slow` (each `folder.json {name, description}` + `*.js`).
- Scenario file shape: `export default [ { name: '[tag tag] text', args?, run: async (MW, S, a) => {...} } ]`; optional `export const helpers`; run source becomes the script: only MW, S, plain JS, no imports/module vars.
- lib.js objects (L159+): `core` {wallet(id), fault(f), resetFaults()} -> `/test/core-mock/v1/*`; `sim` L166 {scenario(partner,s), reset, requests, lastSeq, replay, sms, mail, ussd, smppBinds, smppScenario, smppDrop, ipnFail, ipnAcceptAll, ipnReceived} -> `/test/sim/*`;
  `app` L333, `auth` L403, `admin` L498, `portal` L517, `checkout` L557, `callbacks` L603 (billaggCallbacks/bankCallbacks set sim scenario), `testApi` L619 (reverse, handled, poison, dlqRecord, auditEvents, chain), `MW` L895.
  Low-level: `http` L27, `must` L69, `poll` L77, `token` L202, `device` L242, `signedHeaders` L269. Scenario `S` (`Scenario()` L656): check, eq, status, code, txnStatus, carriesTxnId, note/deltas/changedBy/noChange (core balances),
  fault/dropReply/reject/refusalLost/force (core faults, auto reset L704), submit, p2p, topup, billPay, addMoney, repeat, appShows, sms, mail, audit*, dial/ussd*, cr*/admin*. Add new helpers (mockScenario, mockSwitch, mockReset, mockJournal, coreBalance, waitForState) here.
- Customer rotation: runs play customer01 as a rotated run customer (lib.js ~L597-640, `run_customer.mjs`, ledger `build/api-test/run-customers.tsv`) because of the 15,000 BDT daily debit limit.
- Traceability: `scripts/check-api-traceability.sh` (python inside; `TAGS` set L41, `SECURITY_TAGS` L46 = auth pin lock assertion crypto -> must name requirement ids) checks each request name starts with known tags, is mapped once in
  TRACEABILITY.md `## Requests` table (L45+: `| Folder | Request | Criteria | Covers | Requirements |`), Covers vocabulary, criteria 1-5 or `-`, fifth column ids FR-nn/SEC-nn/NFR-nn; collection not stale. New tags (e.g. frs, routing, mock) must be added to TAGS.
  Self-test `scripts/test/test_api_traceability.sh`. Run via `./gradlew :contractsLint` (scripts/contracts-lint.sh).
- `tests/api/run.sh`: `--folder "01"` or full name, `--all`; re-runs apisix-init with MW_E2E_ROUTES=1 (`MW_API_SKIP_ROUTES=1` skips), copies seed manifest to `build/seed/manifest.json`; JUnit `tests/api/build/newman-report.xml`. Wrapper `scripts/api-test.sh [FOLDER] [ALL]`.
