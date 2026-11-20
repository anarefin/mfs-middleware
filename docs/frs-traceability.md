# FRS traceability map

Maps every requirement id and every name of the Upay FRS (`spec/FRS-Upay-Middleware.md`, the mock-build
FRS of 2026-11-19) to what this repo calls it, what is built, and the ticket that builds the rest.
Decisions: ADRs D29–D33 ([index](adr/README.md)); spec revision 7 ("FRS scope" note in
`spec/bits-mfs-middleware-spec.md`). Tickets live in `.scratch/bits-mfs-middleware/issues/` and run in
the order of its `DEPENDENCIES.md`.

**Principle.** The repo keeps its own names (transaction types, states, channels, `MW-*` error codes,
UUIDv7 ids, amounts in poisha). The FRS is mapped, not copied; only what the repo lacks is added
(ticket 92, in the contracts: `NPSB_OUT`, `NPSB_IN`, `B2B_TRANSFER`, `vat`, `commission`, `completedAt`,
`partnerStatusCode`, `timestamp` in the error envelope).

**Status** (one per id, checked by `scripts/test/test_frs_traceability.sh`):

- `done`: met by the repo today; the third column names the evidence.
- `ticket NN` / `tickets NN, MM`: not met yet, or met in part (the third column says which part
  exists); the ticket(s) finish it.
- `deviation`: the repo does it differently on purpose; the third column gives the reason and the
  decision.

## 1. Stack (FRS 1.4)

| FRS choice | Repo | Note |
| --- | --- | --- |
| Java 21, Spring Boot 3.x | JDK 25, Spring Boot 4.1.2 | newer LTS lines (D1, spec 3); deviation, no FRS feature depends on the older lines |
| Spring Cloud Gateway | Apache APISIX | D3; the gateway duties of PLT-001..008 are APISIX plugins plus service filters |
| Apache Camel (CXF, SFTP, Kafka, SMTP) | Camel only in `mw-adapter-*` (D1, D28); Kafka on Spring Kafka (D27); SMTP and SMPP in mw-notify without Camel; back-office SFTP through `client/` (D32) | deviation: Camel where partner integration is config, nowhere else |
| jSMPP | jSMPP | as FRS |
| Resilience4j | Resilience4j | as FRS |
| Apache Kafka | Apache Kafka (KRaft), outbox relay | as FRS (D2) |
| PostgreSQL with Flyway | PostgreSQL 18, Flyway 12 | as FRS (D4) |
| Redis | Redis 8.10.3 (D29), pinned by digest in `deploy/docker-compose.yml`, the same release in every Testcontainers IT (`scripts/test/test_redis.sh`) | done (ticket 90); OWN-03 written approval of AGPLv3 open (D29) |
| Keycloak; JWT for apps | Keycloak for console, portals, partners and services; app JWTs issued by mw-auth (D21) | FRS only asks for "JWT for apps" |
| HashiCorp Vault dev mode | HashiCorp Vault 2.2.2 (D30), file-backed and unsealed by `deploy/vault/dev-unseal.sh` instead of `-dev` in-memory mode | done (ticket 91) |
| Prowide Core, Prowide ISO 20022 | Prowide (tickets 121, 126) | as FRS |
| WireMock plus custom mock apps | mw-simulators and mw-core-mock (M5, D33) | deviation: one stateful simulator app with one mock control API instead of WireMock stubs |
| React or Angular console | Next.js (D6) | the FRS leaves the choice to the team |
| Micrometer, OpenTelemetry SDK, JSON logs | Micrometer, W3C trace context, JSON logs (spec 21) | collectors out of scope (D10) |

## 2. Names

### 2.1 Modules (FRS 2.1)

| FRS module | Repo |
| --- | --- |
| `M01` API Gateway | APISIX (`deploy/apisix`) |
| `M02` App Middleware | mw-app-gateway, with mw-auth for login, devices, PIN and the assertion (D20–D22) |
| `M03` USSD Engine | mw-ussd |
| `M04` Checkout Service | mw-checkout |
| `M05` Portal API | mw-portal-api |
| `M06` Orchestrator | mw-orchestrator |
| `M07` Routing Service | a module of mw-orchestrator (D31, ticket 98) |
| `M08` Connector Framework | the `partner/` framework copied into each `mw-adapter-*` (D28) |
| `M09` Connectors | mw-adapter-telco, -billpay, -bank; card, remit, atm, interop, merchant (D32) |
| `M10` Notification Service | mw-notify |
| `M11` Reconciliation Service | mw-recon (D32, tickets 126, 127) |
| `M12` Compliance Service | mw-compliance (D32, tickets 124, 125) |
| `M13` Audit Service | mw-audit-dw |
| `M14` Data Export Service | mw-data-export (D32, ticket 128) |
| `M15` Admin Console | mw-admin-api and `ui/mw-admin-ui` |
| `M16` Mock Core Wallet | mw-core-mock (REST from ticket 93, D31) |
| `M17` Mock Suite | mw-simulators, the mock control API (D33) |

### 2.2 Canonical model fields (FRS 2.2)

| FRS field | Repo | Note |
| --- | --- | --- |
| `txnId` String(26, ULID) | `txnId` UUIDv7 | deviation: time-ordered like a ULID, native PostgreSQL `uuid` type; kept |
| `idempotencyKey` | `idempotencyKey` | as FRS (spec 7.3) |
| `correlationId` | `correlationId` | as FRS |
| `txnType` | `txnType` (2.3 below) | mapped |
| `channel` | `channel` (2.4 below) | mapped |
| `initiator / debitParty / creditParty` | `initiator`, `payer`, `payee` Party `{type, id, name?, walletType?}` | `BILLER` = `BILLER_ACCOUNT`; repo adds `ORG` |
| `amount, fee, vat, commission` Decimal(18,2) | `{valueMinor, currency}` integer poisha | deviation: minor units avoid rounding; optional `vat` and `commission` (`$defs/Charge`) on `mw.txn.status.v1` since ticket 92 (filled once the core reports them) |
| `externalRef` | `partnerRef`, partner id; optional `partnerStatusCode` on `mw.txn.status.v1` since ticket 92 | |
| `status` | state (2.5 below) | mapped |
| `metadata` | `metadata` | MNO (`metadata.mno`, documented on `mw.txn.request.v1` by ticket 92), biller account, bill month and so on |
| `timestamps` created, lastUpdated, completed | `requestedAt`, `updatedAt`; optional `completedAt` on `mw.txn.status.v1` since ticket 92 | |

### 2.3 Transaction types (FRS 2.2 txnType)

| FRS | Repo |
| --- | --- |
| `CASH_IN`, `CASH_OUT` | `CASH_IN`, `CASH_OUT` |
| `SEND_MONEY` | `P2P` |
| `PAYMENT` | `MERCHANT_PAY` |
| `BILL_PAY`, `TOPUP` | `BILL_PAY`, `TOPUP` |
| `ADD_MONEY_CARD`, `ADD_MONEY_BANK`, `W2B` | `ADD_MONEY_CARD`, `ADD_MONEY_BANK` (alias `C2W`), `W2B` |
| `REMITTANCE_IN` | `REMIT_IN` |
| `ATM_CASH_OUT`, `ATM_CASH_IN` | `ATM_CASH_OUT`, `ATM_CASH_IN` |
| `QR_PAY` | `QR_PAY_INTEROP` (off-us); an on-us QR is `MERCHANT_PAY` (ticket 122) |
| `NPSB_OUT`, `NPSB_IN` | `NPSB_OUT`, `NPSB_IN` (in `TxnType` since ticket 92; refused `MW-VAL-010` until their flows exist) |
| `DISBURSEMENT` | `BULK_DISBURSE` |
| `REVERSAL` | `REVERSAL` |
| (UC-07 B2B float) | `B2B_TRANSFER` (in `TxnType` since ticket 92; refused `MW-VAL-010` until its flow exists) |
| (none) | `BALANCE` (D26), repo only |

### 2.4 Channels (FRS 2.2 channel)

| FRS | Repo |
| --- | --- |
| `APP_CUSTOMER`, `APP_AGENT`, `APP_MERCHANT` | `CUSTOMER_APP`, `AGENT_APP`, `MERCHANT_APP` |
| `APP_DISTRIBUTOR`, `APP_DSO`, `APP_PARTNER` | `DISTRIBUTOR_APP`, `DSO_APP`, `PARTNER_APP` |
| `USSD_GP`, `USSD_ROBI`, `USSD_TELETALK`, `USSD_BL` | `USSD` plus `metadata.mno` (`gp`, `robi`, `teletalk`, `bl`: mw-ussd's MNO keys) |
| `WEB_CHECKOUT`, `ORG_PORTAL`, `SP_PORTAL`, `PARTNER_API` | same names |
| (none) | `SYSTEM` (operator actions, reversals), repo only |

### 2.5 Transaction states (FRS 2.3)

| FRS | Repo (spec 7.2) |
| --- | --- |
| `RECEIVED` | `RECEIVED` |
| `AUTHORISED` | `VALIDATING` (left when the core validated limits and fees; the PIN is checked before, by the mw-auth assertion) |
| `DEBIT_HELD` | `HOLDING` (the hold confirmed) |
| `EXTERNAL_PENDING` | `PARTNER_PENDING` |
| `UNKNOWN`, `MANUAL_REVIEW`, `COMPLETED`, `REVERSING`, `REVERSED`, `FAILED` | same names |
| (none) | `POSTING`, `COMMITTING`, `RELEASING` (core steps after the partner call), `REJECTED` (refused by the core before money moved; FRS folds it into `FAILED`) |

### 2.6 Error codes (FRS 3.7)

| FRS | Repo (spec 5.3) |
| --- | --- |
| `VAL-*` | `MW-VAL-*` |
| `AUTH-*` | `MW-AUTH-*` (wrong PIN and lockout from mw-auth) |
| `IDM-001` | `MW-IDEM-001` |
| `LIM-*` (`LIM-001` daily limit, `LIM-002` balance) | `MW-CORE-<reason>` (the core's reason, e.g. `MW-CORE-INSUFFICIENT_BALANCE`) |
| `CMP-001`, `CMP-002` | `MW-CMP-*` (new namespace, D32, tickets 124, 125) |
| `EXT-400` | `MW-PTR-<partner>-*` (the adapter's mapping table) |
| `EXT-503` | `MW-SYS-PARTNER_CIRCUIT_OPEN` / `MW-SYS-PARTNER_UNAVAILABLE` |
| `EXT-504` | `MW-TMO-*` |
| `SYS-500` | `MW-SYS-*` |
| envelope `code, message, messageBn, txnId, correlationId, timestamp` | `{code, message, messageBn?, txnId?, correlationId, retryable, details[], timestamp}` (`timestamp` since ticket 92, UTC ISO-8601, every service) |

### 2.7 Use cases (FRS 5.1)

| FRS | Repo / tickets |
| --- | --- |
| `UC-01` registration with eKYC | ticket 124 |
| `UC-02` login, device binding, PIN change | mw-auth (D20–D22); app types ticket 106, USSD ticket 109 |
| `UC-03` balance and statement | balance done (D26); statement tickets 107, 109 |
| `UC-04` P2P | done (`P2P`); FRS T1–T12 ticket 104 |
| `UC-05`, `UC-06` cash-in, cash-out | done |
| `UC-07` B2B float | ticket 106 |
| `UC-08` top-up | prepaid done (one OCS); ticket 112 |
| `UC-09`, `UC-10` bills | one aggregator done; tickets 113, 114 |
| `UC-11` merchant payment | ticket 115 |
| `UC-12` web checkout and refund | pay done; refund ticket 116 |
| `UC-13` add money from card | ticket 118 |
| `UC-14` add money from bank (C2W) | done (one bank); ticket 117 |
| `UC-15` W2B with AVL | ticket 117 |
| `UC-16` remittance | ticket 119 |
| `UC-17` ATM | ticket 120 |
| `UC-18`, `UC-19` BanglaQR, NPSB | tickets 121, 122 |
| `UC-20` bulk disbursement | CSV done; ticket 129 |
| `UC-21` AML | ticket 125 |
| `UC-22` recon and DW export | tickets 126, 127, 128 |

## 3. Common platform (FRS 3)

| ID | Status | Repo / evidence or reason |
| --- | --- | --- |
| PLT-001 | done | APISIX is the only port beyond loopback (D3 amendment); production is edge-only |
| PLT-002 | ticket 100 | app JWTs (mw-auth) and merchant API key + HMAC built; the client registry records each client's auth method (ticket 100a); partner client credentials (100b) and inbound mTLS (100c) follow |
| PLT-003 | ticket 132 | client registry built (ticket 100a): mw-admin-api `admin.channel_client` (V12), `/admin/v1/channel-clients` maker-checker (`ChannelClientChangeRequestIT`), projected to APISIX consumers + consumer groups after the approval commits and on a ShedLock reconcile (`service/ApisixConsumerSync`, `ApisixConsumerSyncTest`), secrets as `$secret://vault/edge/...` on the Vault KV v1 mount `apisix/`; today's clients seeded by mw-seeder (`seed-channel-clients`); console in 132 |
| PLT-004 | ticket 100 | 429 with `Retry-After` on every route, one bucket per route group keyed per client (ticket 99, `lua/edge-error-headers.lua`, `test_apisix_edge.sh`, `scripts/edge-test.sh`); counters in Redis (ticket 90, D29); per registered client with an APISIX consumer: `limit-req` rate and `limit-count` daily quota in its consumer group (ticket 100a, `ApisixConsumerSyncTest`); consumer-less clients keyed by token client id in 100b |
| PLT-005 | done | `CorrelationIdFilter` in every service, `MwHeaders` on Kafka, propagated on outbound calls (root `CLAUDE.md`) |
| PLT-006 | ticket 108 | Bean Validation and body JSON Schema (`MW-VAL-001`) built; XSD with the SOAP facade |
| PLT-007 | done | `MaskingPolicy` and `@Sensitive` in every service, `scripts/log-scan.sh`; mask shape `0171****789` (repo rule) |
| PLT-008 | done | app signatures (`MW-AUTH-005`/`006`); checkout HMAC signs `X-Nonce`, mw-checkout `service/RequestReplayGuard` (`MW-AUTH-005`/`006`, `HmacReplayTest`); partner callbacks sign `X-Nonce` (`partner/auth/HmacSha256AuthScheme` + `CallbackNonces`, `CallbackReplayIT`); nonces in PostgreSQL in mw-checkout and the adapters (ticket 99); API folder 36 |
| PLT-010 | done | Idempotency-Key on every financial API, derived for USSD (spec 7.3) |
| PLT-011 | done | key + request hash, `MW-IDEM-001`; mw-app-gateway replays the first 200 body byte for byte (`service/IdempotentReplay`, `IdempotentReplayIT`, ticket 99); API folder 36 |
| PLT-012 | done | in-flight duplicate answered 202 with the current status (`IdempotencyLockIntakeIT`, re-run in ticket 99) |
| PLT-013 | done | mw-orchestrator `service/DuplicateGuard` (60 s, `MW_DUPLICATE_WINDOW`): app/USSD `MW-VAL-024` + `confirmDuplicate` (USSD asks the PIN again), API clients `MW-VAL-025` (`DuplicateGuardTest`, `DuplicateGuardIT`, ticket 99); API folder 36 |
| PLT-014 | tickets 118, 119, 121 | bank and billpay callbacks deduplicated by partner reference (inbox) and signed nonces (ticket 99) built; card, IMT, NPSB in their adapters |
| PLT-020 | done | saga steps with compensation (`saga_step`, spec 7.1) |
| PLT-021 | done | step state persisted per step; resume after restart (`scripts/chaos.sh`); a core REST call starts only after its step commits and its answer re-enters the saga in its own transaction (ticket 95a, `CoreCallDispatcher`, `CoreReplyService`) |
| PLT-022 | ticket 96 | core-side compensation built; the partner leg in reverse order in 96 |
| PLT-023 | ticket 96 | `UNKNOWN` and the inquiry schedule built (spec 7.4); a core call without a definite answer (timeout, 5xx) goes UNKNOWN on its step deadline and WAL-13 settles it (ticket 95a, `CoreRestFlowIT`); FRS default 30 s / 2 min / 10 min / 1 h in 96 |
| PLT-024 | ticket 96 | `MANUAL_REVIEW` after the cut-off built; alert event and metric in 96 |
| PLT-025 | ticket 96 | partner REVERSE, idempotent and retried until acknowledged |
| PLT-026 | ticket 105 | core fee returned at validation; preview with fee, VAT and commission in 105 |
| PLT-030 | done | Resilience4j per partner (spec 19.2), timeouts set from the console; the core connector `client/CoreWalletClient` (connect/read timeouts, breaker `core`, reads retried only; ticket 95a) |
| PLT-031 | done | no automatic redelivery of a PAY (D28); INQUIRY and STATUS retried |
| PLT-032 | ticket 98 | fast failure `MW-SYS-PARTNER_CIRCUIT_OPEN` built; failover routing in 98 |
| PLT-033 | done | bulkhead per partner (`MW-SYS-PARTNER_BUSY`), one adapter per family (D32); bulkhead `core` on the core calls, each on its own virtual thread (ticket 95a) |
| PLT-034 | done | Kafka retry topics and DLQ (D2, D27); the AML and DW feeds use them (tickets 125, 128) |
| PLT-035 | done | transactional outbox in every producing service (spec 6.4) |
| PLT-040 | ticket 98 | routing rules in the mw-orchestrator routing module |
| PLT-041 | ticket 98 | rules in the database, maker-checker, 30 s cache refresh |
| PLT-042 | ticket 98 | prefix to MNO with MNP override |
| PLT-043 | ticket 98 | partner disable built (admin console); maintenance message in 98, biller disable 113 |
| PLT-050 | ticket 108 | `/v1` URI versions; `contracts/api` OpenAPI 3.1 files stay the source of truth, springdoc generates the document from code and `ContractDriftTest` in every REST module fails on drift (ticket 101); no SOAP service is served yet, the app SOAP facade of 108 publishes the first WSDL |
| PLT-051 | done | versioning policy in `contracts/README.md` ("Versioning", ticket 101): breaking change = new `/v2` file and path, `/v1` kept for one release |
| PLT-052 | done | Swagger UI and `/v3/api-docs` only in profiles `dev`/`test` (off in production, `ApiDocsProductionTest`); test-only APISIX page `/docs/` lists them and the WSDLs (none until ticket 108) (ticket 101) |

## 4. Channels (FRS 4)

| ID | Status | Repo / evidence or reason |
| --- | --- | --- |
| CH-APP-01 | ticket 108 | REST built (M2); SOAP facade in 108 |
| CH-APP-02 | ticket 106 | MSISDN + PIN + device login in mw-auth built (D21, the PIN checked by the middleware, D20); 15 min access token in 106 |
| CH-APP-03 | ticket 106 | OTP device binding built; per-app-type device count in 106 |
| CH-APP-04 | ticket 106 | sessions and forced logout in mw-auth; per-app-type limits in 106 |
| CH-APP-05 | ticket 105 | preview and confirm |
| CH-APP-06 | ticket 106 | channel roles for customer, agent, merchant apps built (spec 9.1); distributor, DSO, partner in 106 |
| CH-APP-07 | ticket 106 | PIN lockout built (D20: 3 tries, then 1 h, 24 h); per-app-type throttles in 106 |
| CH-APP-08 | ticket 107 | balance and history built (D25, D26); profile, limits, billers, favourites, inbox, statement in 107 |
| CH-APP-09 | done | `GET /app/v1/transactions/{txnId}` (mw-app-gateway) |
| CH-USSD-01 | ticket 109 | GP over SMPP built; all four MNOs, TX+RX binds in 109 |
| CH-USSD-02 | done | mw-ussd SMPP binds: enquire_link 30 s, reconnect with backoff, window (`UssdSmppProperties`) |
| CH-USSD-03 | ticket 109 | per-MNO TLV adapters |
| CH-USSD-04 | ticket 133 | menu JSON in the database with maker-checker built (M6); visual tree editor in 133 |
| CH-USSD-05 | ticket 109 | sessions in Redis built (ticket 90); 30 s per-screen timeout 109 |
| CH-USSD-06 | tickets 109, 133 | GSM-7 182-character screen limit built (`UssdProperties`); "0. Next" / "00. Back" pagination 109, preview 133 |
| CH-USSD-07 | done | PIN encrypted on receipt, never logged or kept in the session (spec 18.3, `scripts/log-scan.sh`) |
| CH-USSD-08 | ticket 109 | short code to root menu and allowed types |
| CH-USSD-09 | done | a result that arrives after the session ended goes out by SMS (mw-ussd) |
| CH-USSD-10 | ticket 109 | language per customer, default Bangla |
| CH-WEB-01 | done | mw-checkout session create with API key + HMAC |
| CH-WEB-02 | done | checkout page flow with OTP and PIN (mw-checkout, D22) |
| CH-WEB-03 | ticket 116 | redirect and signed IPN with retries built; 24 h retry horizon in 116 |
| CH-WEB-04 | ticket 116 | create and status built; refund and list in 116 |
| CH-WEB-05 | done | session expiry `session-ttl: 15m` (mw-checkout `application.yml`) |
| CH-POR-01 | ticket 129 | CSV upload and row validation built; XLSX and preview with totals in 129 |
| CH-POR-02 | done | bulk maker-checker in mw-portal-api |
| CH-POR-03 | ticket 129 | async execution, throttle, per-row status, result file built; retry of failed rows in 129 |
| CH-POR-04 | ticket 129 | balance check before execution |
| CH-POR-05 | ticket 129 | SP transaction query built; settlements, refunds, reports in 129 |
| CH-POR-06 | done | OAuth2 on the Keycloak `portals` realm, maker and checker roles per organisation |

## 5. Flows (FRS 5.4)

| ID | Status | Repo / evidence or reason |
| --- | --- | --- |
| FL-01 | ticket 124 | eKYC with EC, consent, KYC level |
| FL-02 | ticket 124 | encrypted images through the mw-crypto envelope |
| FL-03 | ticket 112 | postpaid bill fetch |
| FL-04 | ticket 113 | two-step bill fetch then pay built (billpay); amount rules per biller in 113 |
| FL-05 | tickets 113, 132 | biller catalogue in the database |
| FL-06 | ticket 116 | refund bounds |
| FL-07 | ticket 118 | card tokens only (card PAN never enters the middleware, root `CLAUDE.md`) |
| FL-08 | ticket 117 | linked bank accounts with AVL |
| FL-09 | ticket 117 | W2B with AVL and a hold |
| FL-10 | ticket 119 | IMT push and lookup |
| FL-11 | ticket 120 | ATM one-time token |
| FL-12 | tickets 121, 122 | BanglaQR on-us and off-us |
| FL-13 | done | each bulk row is its own transaction linked to the batch (mw-portal-api, `BULK_DISBURSE`) |
| FL-14 | tickets 125, 128 | completion SMS and event built; push next to the SMS (SMS always, push optional) in mw-notify (ticket 110, `PushDispatchIT`); AML feed 125, DW 128 |

## 6. Connectors and mocks (FRS 6)

| ID | Status | Repo / evidence or reason |
| --- | --- | --- |
| CON-01 | done | partner engine (D28) with PAY, INQUIRY, STATUS_QUERY, REVERSE (`reverse:<txnId>`, sent once) and the optional `HEALTH_CHECK` probe (ticket 97; `PartnerReverseRouteIT`, `PartnerRouteHealthTest`) |
| CON-02 | tickets 114, 131, 136 | partner routes as config with maker-checker built (D28); proven per family in 114, by console alone in 131, 136 |
| CON-03 | done | JSONata and XSLT mappings in versioned partner records with maker-checker (D28, mw-admin-api) |
| CON-04 | done | partner audit event and raw masked payload store `partner_payload` with latency and `payloadRef` (ticket 97, `PartnerPayloadIT`); audit side (ticket 130): the adapters send the masked bodies as `payload` with hop PARTNER_CALL and the latency, mw-audit-dw scrubs them again and chains them in `audit.payload` (`PartnerAuditPublisherTest`, `PayloadScrubTest`, `HashChainWithPayloadIT`) |
| CON-05 | done | `PartnerRouteHealth`: UP, DEGRADED, DOWN, MAINTENANCE (+ DISABLED, DELETED), changes on `mw.partner.route.status.v1` `health` (ticket 97) |
| CON-06 | ticket 136 | developer guide and sample connector |
| EXT-01 | ticket 109 | USSD gateway simulator (GP, SMPP) built; four MNOs in 109 |
| EXT-02 | done | SMSC simulator in mw-simulators with inbox and delivery receipts |
| EXT-03 | done | GreenMail SMTP with its web inbox instead of Mailpit (same role) |
| EXT-04 | done | mw-notify `client/PushClient` (FCM / APNs style app-server push API) against mw-simulators' stateful push mock, mock party `push` (ticket 110; `PushSimulatorIT`, `PushDispatchIT`, API suite 43) |
| EXT-05 | tickets 123, 125 | AML SFTP mock and feed |
| EXT-06 | tickets 123, 126 | MT940/942 generator and recon pull |
| EXT-07 | ticket 124 | EC NID mock |
| EXT-08 | ticket 112 | one OCS simulator built; four operator profiles in 112 |
| EXT-09 | ticket 114 | utility aggregator simulator (billagg) built; stateful bills and callbacks in 114 |
| EXT-10 | ticket 114 | four bill aggregator profiles |
| EXT-11 | ticket 115 | merchant SOAP |
| EXT-12 | ticket 117 | C2W bank simulator built; AVL, W2B, bank profiles in 117 |
| EXT-13 | tickets 121, 122 | NPSB and BanglaQR mock switch |
| EXT-14 | ticket 118 | CyberSource mock |
| EXT-15 | ticket 119 | UCB IMT mock |
| EXT-16 | ticket 120 | UCB ATM mock |
| EXT-17 | tickets 123, 128 | DW Kafka and SFTP drop |
| EXT-18 | ticket 95 | core over REST (D31): the mock serves `/core/v1` (`api/mw-core-mock/core-wallet.v1.openapi.yaml`, ticket 93) and the orchestrator calls it (`client/CoreWalletClient`, ticket 95a); mw-auth and mw-admin-api still send Kafka commands until 95b |
| MCK-01 | done | the simulators speak the real protocols (SMPP binds, SOAP, HMAC, mTLS); each new mock keeps the rule in its ticket |
| MCK-02 | tickets 112, 114 | reverse once built in mw-simulators (ocs, billagg, c2wbank; ticket 97, `ReversalServiceTest`); stateful paid bills per aggregator 112, 114 |
| MCK-03 | ticket 136 | magic values are the default trigger of the mock-control engine, an explicit scenario wins (ticket 102, `service/mockcontrol/ScenarioEngine`); trigger table per mock in `docs/concepts/simulators.html`; every new mock in 136 |
| MCK-04 | done | mw-simulators: `/mock-control/{party}` (D33, tickets 102a/b: every mw-simulators party incl. SMSC, USSD gateway, payShield; `contracts/api/mw-simulators/mock-control.v1.openapi.yaml`); mw-core-mock party `core` on the copied engine, Kafka and `/core/v1` (ticket 103, `contracts/api/mw-core-mock/core-mock-control.v1.openapi.yaml`, `CoreMockScenarioIT`) |
| MCK-05 | done | DELAY(ms) per operation and scope, SLOW switch with a random range (ticket 102, `MockControlControllerIT`) |
| MCK-06 | done | LATE_CALLBACK(ms), DUPLICATE_CALLBACK, OUT_OF_ORDER_CALLBACK, NO_CALLBACK and replay `/mock-control/{party}/callbacks/{reference}/replay` (ticket 102) |
| MCK-07 | ticket 135 | queryable: `GET /mock-control/{party}/journal` (ticket 102, `service/mockcontrol/Journal`); the UI view in 135 |
| MCK-08 | done | versioned seed files `seed/v1/` in mw-core-mock (wallets, system wallets, tariff, limits; ticket 94) and mw-simulators (billers), reloaded by the global reset with an optional `seedVersion` (ticket 103, `SeedLoaderTest`, `MockControlResetIT`, `SeedFilesTest`) |
| MCK-09 | done | every mock is a container in `deploy/docker-compose.yml` |

## 7. Mock core wallet (FRS 7.2)

| ID | Status | Repo / evidence or reason |
| --- | --- | --- |
| WAL-01 | done | `GET /core/v1/accounts/{msisdn}` (ticket 93): type, status and KYC level of FRS 7.1 (ticket 94) |
| WAL-02 | deviation | not built: the middleware owns the PIN (D20); mw-auth checks it and the core verifies the mw-auth assertion header instead (D22, D31) |
| WAL-03 | done | `GET /core/v1/balance/{walletId}`: available, held, total (ticket 93) |
| WAL-04 | done | `POST /core/v1/charges/calculate` (tickets 93, 94): the tariff sheet `seed/v1/tariff.csv` (slab, channel, customer type), VAT inside the fee, commission split (`service/Tariff`, `TariffTest`) |
| WAL-05 | done | `POST /core/v1/limits/check` (tickets 93, 94): the limit sheet `seed/v1/limits.csv` by txnType, customer type and KYC level; per txn min/max, daily and monthly amount and count (`service/Limits`, `LimitsTest`) |
| WAL-06 | done | `POST /core/v1/holds` (ticket 93): the `HoldFunds` rules, `X-Txn-Assertion` |
| WAL-07 | done | `POST /core/v1/holds/{id}/commit` (tickets 93, 94): the hold's settlement wallet, FEE_INCOME, VAT_PAYABLE, COMMISSION_PAYABLE in double entries (`LedgerInvariantIT`) |
| WAL-08 | done | `POST /core/v1/holds/{id}/release` (ticket 93) |
| WAL-09 | done | `POST /core/v1/transfers` (ticket 93): the `PostTransfer` rules |
| WAL-10 | done | `POST /core/v1/credits` (tickets 93, 94): from the partner settlement wallet `settlementWallet` names (`SETTLEMENT_NPSB`, `SETTLEMENT_UCB_IMT`, ...), else `SETTLEMENT` |
| WAL-11 | ticket 116 | `POST /core/v1/reversals` built (ticket 93, full, idempotent, once); partial reversal for refunds in 116 |
| WAL-12 | ticket 107 | `GET /core/v1/statements` (tickets 93, 94): entries by wallet and range, paged, with the balance after each entry; the customer statement in 107 |
| WAL-13 | done | `GET /core/v1/transactions/{txnId}` (ticket 93: status and entries); the orchestrator's HOLDING query, inquiry, reversal query and credit check use it (ticket 95a) |

## 8. Supporting services (FRS 8)

| ID | Status | Repo / evidence or reason |
| --- | --- | --- |
| NOT-01 | tickets 111, 133 | transaction notifications on SMS, email and push (ticket 110) built; rule table and system events 111, editor 133 |
| NOT-02 | done | SMS, email and push templates (title + body, `PUSH_TXN_COMPLETED` / `PUSH_TXN_FAILED`, ticket 110) in Bangla and English in `notify.template` |
| NOT-03 | done | mw-notify SMPP: GSM-7 / UCS2, concatenation, sender id (`SmsEncoder`) |
| NOT-04 | done | delivery receipts update the message status (`DeliveryReport`) |
| NOT-05 | done | priority queues, OTP first (`Priority`) |
| NOT-06 | ticket 134 | retry and DLQ for SMS, email and push built (push: client retry with backoff and breaker, then 2/10/30 s, ticket 110); console view 134 |
| NOT-07 | ticket 111 | campaigns with throttle |
| NOT-08 | tickets 111, 134 | notification log search |
| NOT-09 | ticket 111 | opt-out |
| REC-01 | ticket 126 | MT940/942 pull and parse |
| REC-02 | ticket 126 | adapters' SFTP ingest to `mw.partner.recon.v1` built (D28); consumer in 126 |
| REC-03 | ticket 126 | matching with tolerance |
| REC-04 | ticket 126 | FRS result classes |
| REC-05 | tickets 127, 134 | exception queue with maker-checker |
| REC-06 | tickets 127, 134 | reports |
| REC-07 | ticket 127 | trust account position |
| REC-08 | ticket 126 | file register with checksum |
| CMP-01 | ticket 125 | AML file export |
| CMP-02 | tickets 125, 134 | HOLD and BLOCK applied (wallet lock built in mw-auth and the core); release in 134 |
| CMP-03 | ticket 125 | real-time pre-check, fails open with an alert (D32) |
| CMP-04 | ticket 124 | consent log |
| CMP-05 | tickets 124, 134 | encrypted at rest; unmask by permission in 134 |
| AUD-01 | ticket 130 | state changes, operator actions and partner calls audited with masked request/response payloads in the hash chain (`audit.payload`, ticket 130); open: channel request logging (CHANNEL_IN), core-call and notification hops are not sent yet |
| AUD-02 | done | append-only audit with a hash chain (mw-audit-dw `audit_event`) |
| AUD-03 | done | search by txnId, actor, action, resource, outcome, time, and (ticket 130) partner, channel, status, amount range, MSISDN (TOKENISE_MSISDN token) via `audit.txn_index`; 3 s over 30 days proven by `AuditSearchPerfIT` (`slow-wait`) |
| AUD-04 | ticket 135 | `GET /internal/v1/audit/transactions/{txnId}/timeline`: hops in order with latency, gaps and masked payloads (`TimelineIT`); mw-admin-api transaction detail does not call it yet (UI ticket 135) |
| AUD-05 | done | `GET /internal/v1/audit/search/export.csv` with a row cap; every export writes an `audit.search.exported` operator-action record into the chain (`ExportAuditedIT`) |
| DAT-01 | deviation | JSON Schema in Git, no schema registry (M9, D10, D31): the FRS allows JSON Schema; the registry is not run. The FRS topics come with tickets 127 (`recon.exception`) and 128 |
| DAT-02 | ticket 128 | daily DW batch |
| DAT-03 | ticket 128 | PII tokenisation policy |
| DAT-04 | ticket 128 | retention and archival |

## 9. Admin console (FRS 9.1)

| ID | Status | Repo / evidence or reason |
| --- | --- | --- |
| ADM-01 | ticket 131 | partner enable/disable, timeouts and routes built (M6, D28); credential upload and error map API built (131a); maintenance window and mock/real switch in 131b, console tabs in 131c |
| ADM-02 | ticket 131 | API built (131a): write-only credential upload through maker-checker (`partner_credential`, staged in Vault, approved into a field of `secret/mw-adapter-<family>/<partner>`, mw-admin-api's policy write-only there), metadata `GET /admin/v1/credentials`, dashboard feed `/admin/v1/credentials/expiring`, ops email at 30/7/1 days (`CredentialVaultIT`, `CredentialExpiryJobIT`, API folder 45); console tab and card in 131c |
| ADM-03 | ticket 132 | channel clients: API built (ticket 100a, `/admin/v1/channel-clients`, maker-checker resource `channel_client`); console in 132 |
| ADM-04 | tickets 98, 132 | routing rules |
| ADM-05 | tickets 113, 132 | billers |
| ADM-06 | ticket 133 | USSD menu JSON editing built; visual editor and preview in 133 |
| ADM-07 | tickets 111, 133 | SMS template editing built; rule table and test send |
| ADM-08 | ticket 131 | API built (131a): `partner_error_map` change request, published as the partner's next version (`PartnerConfigApiIT`, API folder 45); the screen in 131c |
| ADM-09 | done | transaction search and saga timeline (mw-admin-api, `ui/mw-admin-ui`) |
| ADM-10 | ticket 134 | TSQ, force complete and reversal with maker-checker built; resend notification and release hold in 134 |
| ADM-11 | ticket 134 | recon workbench |
| ADM-12 | ticket 134 | AML holds view and release |
| ADM-13 | ticket 135 | monitoring views |
| ADM-14 | ticket 135 | users and roles |
| ADM-15 | ticket 135 | audit viewer with before/after |
| ADM-16 | ticket 135 | mock control screen |

## 10. Mock control and tests (FRS 10.1, 10.2)

| ID | Status | Repo / evidence or reason |
| --- | --- | --- |
| MCT-01 | done | `POST /mock-control/{party}/scenarios`, every behaviour (ticket 102, `MockControlControllerIT`) |
| MCT-02 | done | global, per key (MSISDN, account, biller, bill number, txn id), next N (ticket 102, `ScenarioEngineTest`) |
| MCT-03 | done | `PUT /mock-control/{party}/switch` DOWN (connection closed), MAINTENANCE (503 5030), SLOW (ticket 102) |
| MCT-04 | done | SMPP faults on the mock-control engine (ticket 102b): parties `smsc` (bind refusal, ESME_RTHROTTLED and any command_status, missing / late / duplicate / out-of-order DLR, DOWN closes the connection) and `ussdgw` (bind refusal, ESME_RTHROTTLED, generic_nack, dropped enquire_link, dial), `payshield` (error codes per command, cut-short answer, reset); `SmppMockControlIT`, API folder 39 |
| MCT-05 | ticket 123 | file faults |
| MCT-06 | done | `GET` and `DELETE /mock-control/{party}/journal`, DELETE resets the party's state (ticket 102) |
| MCT-07 | done | `POST /mock-control/reset` on mw-simulators: every local party plus mw-core-mock's ledger back to the seed, one line per party; test route `/test/mock-control/*` (ticket 103, `GlobalResetIT`, `MockControlResetIT`) |
| MCT-08 | ticket 135 | profile `mock-control` only on the test doubles: start guard with `MW_ENV=prod` (`ProdGuardTest`), `scripts/check-mock-control-prod.sh` under the root `check` (ticket 103); the UI part in 135 |
| T1 | tickets 104, 137 | happy path: P2P in 104, every financial use case in 137 |
| T2 | tickets 104, 137 | partner business rejection |
| T3 | tickets 104, 137 | timeout, success on inquiry |
| T4 | tickets 104, 137 | timeout, failure on inquiry |
| T5 | tickets 96, 104, 137 | never answers, `MANUAL_REVIEW` with alert |
| T6 | tickets 104, 137 | same Idempotency-Key twice |
| T7 | tickets 104, 137 | duplicate partner callback |
| T8 | tickets 98, 114, 137 | circuit open, failover |
| T9 | tickets 104, 137 | insufficient balance / limit |
| T10 | tickets 104, 137 | restart mid-saga (`scripts/chaos.sh` covers the restart today) |
| T11 | tickets 125, 137 | AML HOLD |
| T12 | tickets 127, 137 | reconciliation next day |

## 11. Acceptance (FRS 10.4) and phases (FRS 10.5)

FRS 10.4 criteria 1–7 are run by ticket 138 on a fresh stack. FRS 10.5 phases are milestones M7
(foundation, tickets 89–104) to M13 (hardening, tickets 131–138) in `DEPENDENCIES.md`. Performance
scripts are updated but not run at gates (no load tests on the demo host). Coverage (FRS 10.3, 80 %)
is reported, not gated (D19).
