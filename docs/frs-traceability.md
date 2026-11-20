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
| (UC-07 B2B float) | `B2B_TRANSFER` (ticket 106: `B2bTransferFlow`, validate then PostTransfer, like P2P) |
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
| `UC-02` login, device binding, PIN change | mw-auth (D20–D22); app types ticket 106. USSD change PIN stays on the maintenance text (109c) |
| `UC-03` balance and statement | balance done (D26); app statement done (107b); USSD mini statement is core WAL-12 (109c) |
| `UC-04` P2P | done (`P2P`); FRS T1–T12 ticket 104 |
| `UC-05`, `UC-06` cash-in, cash-out | done |
| `UC-07` B2B float | done (`B2B_TRANSFER`, `B2bTransferFlow`, folder 63) |
| `UC-08` top-up | prepaid denominations and postpaid range landed in 112b; T1–T4 and T6–T10 are folder `tests/api/src/46-topup-operators` (112c). T5 waits out the inquiry schedule. T11 is ticket 125, T12 is ticket 127 |
| `UC-09`, `UC-10` bills | five aggregators (billagg + ticket 114's four), catalogue 113; channel end-to-end at the gate |
| `UC-11` merchant payment | done: app (folder 04); USSD menu MERCHANT_PAY (109c); a merchant with its own system is validated over SOAP before the core and notified after COMPLETED, retried then dead-lettered (115b, folder 52) |
| `UC-12` web checkout and refund | pay done; refund: core partial reversal built (116a), orchestrator refund as its own REVERSAL record, owning merchant only (116b), checkout refund API `POST|GET /checkout/v1/payments/{id}/refunds` with the signed refund IPN built (116c) |
| `UC-13` add money from card | ticket 118 |
| `UC-14` add money from bank (C2W) | done (one bank); USSD uses the same ADD_MONEY_BANK flow (109c); ticket 117 |
| `UC-15` W2B with AVL | partial: mw-adapter-bank AVL (INQUIRY) and W2B credit on 20 bank profiles (117a); linked accounts (117b); simulator (117d); orchestrator `W2bFlow` with a hold, bank routing V18, app `transfer-to-bank` preview/confirm and add money from a linked account (`linkedAccountId`, 117c); USSD Transfer to Bank menu and suite open (117c follow-up, 117e) |
| `UC-16` remittance | USSD status by Upay txn id is core WAL-13 (109c); inbound credit is ticket 119 |
| `UC-17` ATM | partial: inbound SOAP (verify, confirm, reversal, cash-in) and UcbAtmMock (120a); token issue, orchestrator flows and the suite remain ticket 120 |
| `UC-18`, `UC-19` BanglaQR, NPSB | tickets 121, 122; 121a built the `mw-adapter-interop` module (family `interop`) and the NPSB participant list mock; the ISO 20022 wire (121b) and the flows and API (121c) remain |
| `UC-20` bulk disbursement | CSV done; ticket 129 |
| `UC-21` AML | ticket 125 |
| `UC-22` recon and DW export | DW export done (ticket 128); matching and classification done (ticket 126); reports, the exception queue and its maker-checker actions done (tickets 127, 127b); the trust position job and API suite folder `61-recon-reports` done (ticket 127c; open item for Upay: the real core must serve the e-money total endpoint, see REC-07) |

## 3. Common platform (FRS 3)

| ID | Status | Repo / evidence or reason |
| --- | --- | --- |
| PLT-001 | done | APISIX is the only port beyond loopback (D3 amendment); production is edge-only |
| PLT-002 | ticket 100 | app JWTs (mw-auth) and merchant API key + HMAC built; the client registry records each client's auth method (ticket 100a); partner client credentials for `OAUTH2_CC` (ticket 100b: Keycloak `partners` `private_key_jwt`, APISIX `openid-connect` and a limit keyed by the token client id on `test-oauth2-cc`, simulator `GET /sim/partner-token`); inbound mTLS (100c) follows |
| PLT-003 | ticket 132 | client registry built (ticket 100a): mw-admin-api `admin.channel_client` (V12), `/admin/v1/channel-clients` maker-checker (`ChannelClientChangeRequestIT`), projected to APISIX consumers + consumer groups after the approval commits and on a ShedLock reconcile (`service/ApisixConsumerSync`, `ApisixConsumerSyncTest`), secrets as `$secret://vault/edge/...` on the Vault KV v1 mount `apisix/`; today's clients seeded by mw-seeder (`seed-channel-clients`); console in 132 |
| PLT-004 | ticket 100 | 429 with `Retry-After` on every route, one bucket per route group keyed per client (ticket 99, `lua/edge-error-headers.lua`, `test_apisix_edge.sh`, `scripts/edge-test.sh`); counters in Redis (ticket 90, D29); per registered client with an APISIX consumer: `limit-req` rate and `limit-count` daily quota in its consumer group (ticket 100a, `ApisixConsumerSyncTest`); an `OAUTH2_CC` client has no consumer and is limited on its route by `mw_client_id` (ticket 100b); mTLS clients in 100c |
| PLT-005 | done | `CorrelationIdFilter` in every service, `MwHeaders` on Kafka, propagated on outbound calls (root `CLAUDE.md`) |
| PLT-006 | done | Bean Validation and body JSON Schema (`MW-VAL-001`); the SOAP XSD is `contracts/api/mw-app-gateway/app-soap.v1.xsd` (ticket 108) |
| PLT-007 | done | `MaskingPolicy` and `@Sensitive` in every service, `scripts/log-scan.sh`; mask shape `0171****789` (repo rule) |
| PLT-008 | done | app signatures (`MW-AUTH-005`/`006`); checkout HMAC signs `X-Nonce`, mw-checkout `service/RequestReplayGuard` (`MW-AUTH-005`/`006`, `HmacReplayTest`); partner callbacks sign `X-Nonce` (`partner/auth/HmacSha256AuthScheme` + `CallbackNonces`, `CallbackReplayIT`); nonces in PostgreSQL in mw-checkout and the adapters (ticket 99); API folder 36 |
| PLT-010 | done | Idempotency-Key on every financial API, derived for USSD (spec 7.3) |
| PLT-011 | done | key + request hash, `MW-IDEM-001`; mw-app-gateway replays the first 200 body byte for byte (`service/IdempotentReplay`, `IdempotentReplayIT`, ticket 99); API folder 36 |
| PLT-012 | done | in-flight duplicate answered 202 with the current status (`IdempotencyLockIntakeIT`, re-run in ticket 99) |
| PLT-013 | done | mw-orchestrator `service/DuplicateGuard` (60 s, `MW_DUPLICATE_WINDOW`): app/USSD `MW-VAL-024` + `confirmDuplicate` (USSD asks the PIN again), API clients `MW-VAL-025` (`DuplicateGuardTest`, `DuplicateGuardIT`, ticket 99); API folder 36 |
| PLT-014 | tickets 118, 119, 121 | bank and billpay callbacks deduplicated by partner reference (inbox) and signed nonces (ticket 99) built; card built (mw-adapter-card 3-D Secure webhook, ticket 118b); IMT, NPSB in their adapters |
| PLT-020 | done | saga steps with compensation (`saga_step`, spec 7.1) |
| PLT-021 | done | step state persisted per step; resume after restart (`scripts/chaos.sh`); a core REST call starts only after its step commits and its answer re-enters the saga in its own transaction (ticket 95a, `CoreCallDispatcher`, `CoreReplyService`) |
| PLT-022 | done | a refused commit compensates the partner first, then releases a hold that remains (`PartnerReversalIT`, ticket 96b); a completed TOPUP or BILL_PAY is reversed at the partner before ReverseTransaction, and a partner refusal ends MANUAL_REVIEW without a core reversal (`CompletedPartnerReversalIT`, ticket 96c); API folder 30 |
| PLT-023 | done | `UNKNOWN` and the inquiry schedule (spec 7.4): default 30 s / 2 min / 10 min / 1 h, `mw.orchestrator.inquiry.schedule`, per-partner list of waits (ticket 96a, `InquiryScheduleTest`, `InquirySagaIT`); a core call without a definite answer (timeout, 5xx) goes UNKNOWN on its step deadline and WAL-13 settles it (ticket 95a, `CoreRestFlowIT`) |
| PLT-024 | done | `MANUAL_REVIEW` once the last inquiry went unanswered (`answer-wait` 30 s, `MW-TMO-004`); entering MANUAL_REVIEW from any state counts `mw_manual_review_entered_total` and logs `txn.manual_review.entered` (`SagaAlerts`), and mw-notify alerts operations by SMS and email from the status (recipient `OPS`, `mw.notify.ops.*`, V16, `FlowNotificationsIT`; ticket 96a) |
| PLT-025 | done | partner REVERSE is idempotent (`reverse:<txnId>`) and retried until acknowledged, after a refused commit (`PartnerReversalIT`, ticket 96b) and when reversing a completed TOPUP or BILL_PAY (`CompletedPartnerReversalIT`, ticket 96c); no answer is MANUAL_REVIEW `MW-TMO-022`; an ATM reversal advice is answered once per atmTxnRef (`AtmSoapRouteTest`, ticket 120a) |
| PLT-026 | done | preview returns the core fee, VAT and commission (`PreviewService`, `POST /app/v1/{group}/preview`, ticket 105) |
| PLT-030 | done | Resilience4j per partner (spec 19.2), timeouts set from the console; the core connector `client/CoreWalletClient` (connect/read timeouts, breaker `core`, reads retried only; ticket 95a) |
| PLT-031 | done | no automatic redelivery of a PAY (D28); INQUIRY and STATUS retried |
| PLT-032 | done | failover only before money leaves (`service/routing`, `RoutingFailoverIT`): an open circuit, DOWN, MAINTENANCE or a definite pre-send refusal tries the next connector; with none, `MW-SYS-PARTNER_CIRCUIT_OPEN` before a hold; an ambiguous PAY timeout stays UNKNOWN |
| PLT-033 | done | bulkhead per partner (`MW-SYS-PARTNER_BUSY`), one adapter per family (D32); bulkhead `core` on the core calls, each on its own virtual thread (ticket 95a) |
| PLT-034 | done | Kafka retry topics and DLQ (D2, D27); the AML and DW feeds use them (tickets 125, 128) |
| PLT-035 | done | transactional outbox in every producing service (spec 6.4) |
| PLT-040 | done | most specific enabled rule wins in mw-orchestrator `service/routing` (`RoutingServiceTest`); a named partner is honoured only when that rule allows it, otherwise `MW-PTR-UNKNOWN` |
| PLT-041 | done | rules in `txn.routing_rule` (V14), maker-checker in mw-admin-api (V15, `RoutingRuleChangeRequestIT`), applied from `mw.config.changed.v1` and re-read every 30 s (`RoutingRuleReloadIT`) |
| PLT-042 | done | prefix to MNO (017/013 gp, 018 robi, 015 teletalk, 019/014 banglalink) with an MNP override winning (`MsisdnRoutingTest`) |
| PLT-043 | done | a maintenance message for a partner or biller is `MW-SYS-PARTNER_DISABLED` with the bn and en text (`MaintenanceMessageIT`, API folder 35); the adapter's own window stays 131b; a disabled catalogue biller is `MW-SYS-BILLER_DISABLED` with its bn/en maintenance text on the bill fetch (ticket 113, `BillerProjectionIT`), and on an app payment and the USSD bill lookup (ticket 113b, mw-app-gateway `BillerProjectionIT`, mw-ussd `UssdMenuWalkTest`) |
| PLT-050 | done | `/v1` URI versions; `contracts/api` OpenAPI 3.1 files stay the source of truth, springdoc generates the document from code and `ContractDriftTest` in every REST module fails on drift (ticket 101); the app SOAP WSDL is generated from `contracts/api/mw-app-gateway/app-soap.v1.xsd` (ticket 108); the ATM SOAP WSDL is generated from `contracts/api/mw-adapter-atm/atm-soap.v1.xsd` (ticket 120a) |
| PLT-051 | done | versioning policy in `contracts/README.md` ("Versioning", ticket 101): breaking change = new `/v2` file and path, `/v1` kept for one release |
| PLT-052 | done | Swagger UI and `/v3/api-docs` only in profiles `dev`/`test` (off in production, `ApiDocsProductionTest`); test-only APISIX page `/docs/` lists them and the app SOAP WSDL (`deploy/apisix/docs/index.html`, ticket 108) |

## 4. Channels (FRS 4)

| ID | Status | Repo / evidence or reason |
| --- | --- | --- |
| CH-APP-01 | done | REST and the SOAP facade on mw-app-gateway (`contracts/api/mw-app-gateway/app-soap.v1.xsd`, `deploy/apisix/routes/app-ws-v1.json`, `tests/api/src/48-app-soap`) |
| CH-APP-02 | done | Access token 15 min for every app type (`mw.auth.app-types`, `AppTypePolicyTest`, `MW_AUTH_ACCESS_TTL`) |
| CH-APP-03 | done | OTP device binding; the per-type device cap replaces the one-device index (`V11__app_types.sql`, `DeviceBindingService`) |
| CH-APP-04 | done | Sessions in Redis; the (n+1)th login ends the oldest (`ConcurrentSessionIT`, `sessidx:{wallet}`) |
| CH-APP-05 | done | `POST /app/v1/{group}/preview` then `/confirm`; `POST /app/v1/transactions` runs both (ticket 105) |
| CH-APP-06 | done | Distributor, DSO and partner resolve at the gateway; B2B_TRANSFER for distributor, DSO and agent; BULK_DISBURSE for the partner app (`RoleScopingTest`, `AppUserResolver`) |
| CH-APP-07 | done | Customer PIN ladder stays 3 tries then 1 h; other app types 5 tries then 24 h (`AppTypePolicy`, `PinVerifier`) |
| CH-APP-08 | done | saved billers, favourites and the inbox (107a); profile, limits and the statement (107b: WAL-01, WAL-05, WAL-12, max 90 days) |
| CH-APP-09 | done | `GET /app/v1/transactions/{txnId}` (mw-app-gateway) |
| CH-USSD-01 | done | four MNO SMPP binds: gp and bl transceiver, robi and teletalk transmitter plus receiver (`UssdSmppMnoIT`, `UssdSmppClientTest`); dev keeps robi, bl and teletalk on HTTP |
| CH-USSD-02 | done | mw-ussd SMPP binds: enquire_link 30 s, reconnect with backoff, window (`UssdSmppProperties`) |
| CH-USSD-03 | done | one TLV adapter per MNO, tags 0x0501 and 0x1383; gp and bl pack the end bit in bit 0, robi and teletalk in bit 7 (`MnoTlvAdapterTest`) |
| CH-USSD-04 | ticket 133b | menu JSON in the database with maker-checker built (M6); visual tree editor built (133a: add child, move, delete, counters); server preview 133b |
| CH-USSD-05 | done | 30 s per-screen timeout beside the overall session timeout; Redis TTL stays the overall timeout plus grace (mw-ussd) |
| CH-USSD-06 | ticket 133b | GSM-7 182 and UCS-2 70 built; "0. Next" / "00. Back" pagination built (mw-ussd); browser preview pages per MNO (133a); server preview `POST /admin/v1/ussd/menus/preview` 133b |
| CH-USSD-07 | done | PIN encrypted on receipt, never logged or kept in the session (spec 18.3, `scripts/log-scan.sh`) |
| CH-USSD-08 | done | ussd.short_code (code, MNO, root menu, allowed types) and the account type from the core (mw-ussd) |
| CH-USSD-09 | done | a result that arrives after the session ended goes out by SMS (mw-ussd) |
| CH-USSD-10 | done | ussd.customer_pref stores the SHA-256 of the MSISDN and the language, default bn (mw-ussd) |
| CH-WEB-01 | done | mw-checkout session create with API key + HMAC |
| CH-WEB-02 | done | checkout page flow with OTP and PIN (mw-checkout, D22) |
| CH-WEB-03 | ticket 116 | redirect and signed IPN with retries built; 24 h retry horizon built (1m..24h, 116) |
| CH-WEB-04 | ticket 116 | create, status and list by date built (116); refund API and refund IPN built (116c) |
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
| FL-03 | ticket 112 | postpaid quote fetches the bill once; confirm pays from the minimum due through the outstanding (112b) |
| FL-04 | ticket 113 | two-step bill fetch then pay (billpay); the catalogue carries the amount rule (FIXED, MINIMUM, OPEN) and bill month, billpay checks field regex and `billMonth` on the fetch (`BillerCatalogTest`); mw-orchestrator projects each BILLER record (`txn.biller_rule` V17) and `BillPayFlow.amountRefusal` refuses MINIMUM below the minimum, OPEN outside its range and FIXED other than the fetched bill with `MW-VAL-032` before a hold (`BillPayFlowTest`, `BillerAmountRuleIT`, API folder `49-biller-catalogue`); FIXED is checked when the channel attaches the fetched bill (`billOutstandingMinor`): mw-app-gateway keeps each fetch in Redis (`BillFetchStore`) and its bill-pay preview attaches it, mw-ussd attaches the `bill` lookup's amount (ticket 113b, `PreviewServiceTest`, `TxnRequestMapperTest`, API folder 49) |
| FL-05 | tickets 113, 132 | biller catalogue in mw-admin-api (`biller` table V16, 57-biller dev seed, `BillerChangeRequestIT`), published as `mw.config.changed.v1` BILLER; mw-adapter-billpay projection (`BillerProjectionIT`); mw-app-gateway projection (in-memory replay, `GET /app/v1/billers` with fields, labels, amount rule; app.v1 1.14.0) and mw-ussd projection (`ussd.biller_catalog` V7, DYNAMIC billers, bill-number prompt from the field label) (ticket 113b) |
| FL-06 | ticket 116 | refund bounds enforced in mw-core-mock (`MW-CORE-REFUND_EXCEEDS`, 116a); mw-orchestrator refunds 1..original per request and ends an overshoot REJECTED (116b); checkout refund API built, refusal passed through as 422 (116c) |
| FL-07 | ticket 118 | card tokens only (card PAN never enters the middleware, root `CLAUDE.md`) |
| FL-08 | ticket 117 | linked bank accounts with AVL: done (117b: `/app/v1/bank-accounts`, AVL API `/bank/v1/accounts/validate`) |
| FL-09 | ticket 117 | W2B with AVL and a hold: partial (117c: `W2bFlow` hold until the bank confirms, app API; USSD menu open) |
| FL-10 | ticket 119 | IMT push and lookup |
| FL-11 | ticket 120 | ATM one-time token: the adapter never stores or logs it (120a); issue, HMAC, expiry and lockout remain ticket 120 |
| FL-12 | tickets 121, 122 | BanglaQR on-us and off-us |
| FL-13 | done | each bulk row is its own transaction linked to the batch (mw-portal-api, `BULK_DISBURSE`) |
| FL-14 | ticket 125 | completion SMS and event built; push next to the SMS (SMS always, push optional) in mw-notify (ticket 110, `PushDispatchIT`); DW stream and daily drop done (ticket 128, `DwStreamIT`); AML feed remains 125 |

## 6. Connectors and mocks (FRS 6)

| ID | Status | Repo / evidence or reason |
| --- | --- | --- |
| CON-01 | done | partner engine (D28) with PAY, INQUIRY, STATUS_QUERY, REVERSE (`reverse:<txnId>`, sent once) and the optional `HEALTH_CHECK` probe (ticket 97; `PartnerReverseRouteIT`, `PartnerRouteHealthTest`) |
| CON-02 | tickets 131, 136 | partner routes as config with maker-checker built (D28); billpay family proven in 114 (four aggregators generated as config only, `BillAggregatorRouteIT`, `NewBillerRouteIT`); by console alone in 131, 136 |
| CON-03 | done | JSONata and XSLT mappings in versioned partner records with maker-checker (D28, mw-admin-api) |
| CON-04 | done | partner audit event and raw masked payload store `partner_payload` with latency and `payloadRef` (ticket 97, `PartnerPayloadIT`); audit side (ticket 130): the adapters send the masked bodies as `payload` with hop PARTNER_CALL and the latency, mw-audit-dw scrubs them again and chains them in `audit.payload` (`PartnerAuditPublisherTest`, `PayloadScrubTest`, `HashChainWithPayloadIT`) |
| CON-05 | done | `PartnerRouteHealth`: UP, DEGRADED, DOWN, MAINTENANCE (+ DISABLED, DELETED), changes on `mw.partner.route.status.v1` `health` (ticket 97) |
| CON-06 | ticket 136 | developer guide and sample connector |
| EXT-01 | ticket 109 | USSD gateway simulator (GP, SMPP) built; four MNOs in 109 |
| EXT-02 | done | SMSC simulator in mw-simulators with inbox and delivery receipts |
| EXT-03 | done | GreenMail SMTP with its web inbox instead of Mailpit (same role) |
| EXT-04 | done | mw-notify `client/PushClient` (FCM / APNs style app-server push API) against mw-simulators' stateful push mock, mock party `push` (ticket 110; `PushSimulatorIT`, `PushDispatchIT`, API suite 43) |
| EXT-05 | ticket 125 | mock side done (ticket 123): mw-simulators party `aml` answers each file of `aml/in` with ALERT / HOLD / BLOCK lines in `aml/out` (rules `seed/v1/aml/rules.json`, draft format `contracts/files/aml/README.md`; `AmlResponderTest`, `SftpMocksIT`, API folder 44); the feed and response handling in 125 |
| EXT-06 | done | trust bank mock (ticket 123) and the pull (ticket 126): `mw-recon` reads MT940 and MT942 from `trustbank/out` (sshj, Prowide) and classifies them (`Mt940ParserTest`, `ReconMatchingIT`, folder 60) |
| EXT-07 | ticket 124 | EC NID mock |
| EXT-08 | ticket 112 | one OCS simulator built; four operator profiles landed in 112a (`gp-ocs`, `robi-ocs`, `teletalk-ocs`, `bl-ocs`); 112b stores the fetched bill on the quote (minimum through outstanding); T1–T4 and T6–T10 are `tests/api/src/46-topup-operators` (112c). T5 waits out the inquiry schedule |
| EXT-09 | done | utility aggregator simulator (billagg): seeded stateful bills `seed/v1/bills/billagg.csv`, reverse, callbacks (ticket 114, `BillAggregatorMockIT`) |
| EXT-10 | done | four bill aggregators sslcommerz, ekpay, agg3 (SOAP), agg4 as generated route records (`deploy/partner-routes/aggregators/generate.mjs`) and mock profiles (ticket 114; agg4 JSON, not form/XML: open item) |
| EXT-11 | done | merchant SOAP (115a, 115b): mw-adapter-merchant `merchant-soap`, mw-simulators `MerchantSoapMock`, orchestrator validate + notify |
| EXT-12 | ticket 117 | partial: 20 bank profiles (REST and SOAP) generated from `deploy/partner-routes/bank/bank-profiles.csv`, AVL/W2B/STATUS/REVERSE routes (117a); mw-simulators bank gateway from the same CSV, seeded accounts, trigger table, mock party `bank-<code>`, health probe (117d); AVL API, W2B flow, suite open |
| EXT-13 | tickets 121, 122 | NPSB and BanglaQR mock switch; partial: mw-simulators party `npsb` with the seeded participant list (`NpsbSimulatorIT`, 121a); ISO 20022 transfers remain 121b |
| EXT-14 | ticket 118 | CyberSource mock |
| EXT-15 | ticket 119 | UCB IMT mock |
| EXT-16 | ticket 120 | UCB ATM mock: dispense status in mw-simulators (`UcbAtmSimulatorIT`, 120a); the test-console page remains ticket 120 |
| EXT-17 | done | mock side (ticket 123) and the export (ticket 128): `mw-data-export` writes the daily drop, party `dw` checks `control_<date>.json` (`DwDailyExportIT`, folder 62) |
| EXT-18 | done | core over REST (D31): the mock serves `/core/v1` (`api/mw-core-mock/core-wallet.v1.openapi.yaml`, ticket 93), the orchestrator calls it (`client/CoreWalletClient`, ticket 95a), mw-auth checks a PIN reset's KYC factors (`client/CoreClient`) and mw-admin-api unlocks an account (`client/CoreAccountClient`) over it (ticket 95b); the core command topics and `core.command.v1` are retired, the core events stay on Kafka |
| MCK-01 | done | the simulators speak the real protocols (SMPP binds, SOAP, HMAC, mTLS); each new mock keeps the rule in its ticket |
| MCK-02 | ticket 112 | reverse once built in mw-simulators (ocs, billagg, c2wbank; ticket 97, `ReversalServiceTest`); stateful paid bills per aggregator done in 114 (`BillAggregatorMockIT`); operators 112 |
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
| WAL-08 | done | `POST /core/v1/holds/{id}/release` (ticket 93). A hold the core auto-releases (FRS 7.3, HoldExpired) is confirmed with that release, or left in MANUAL_REVIEW when the partner already succeeded (ticket 95c, HoldExpirySagaIT) |
| WAL-09 | done | `POST /core/v1/transfers` (ticket 93): the `PostTransfer` rules |
| WAL-10 | done | `POST /core/v1/credits` (tickets 93, 94): from the partner settlement wallet `settlementWallet` names (`SETTLEMENT_NPSB`, `SETTLEMENT_UCB_IMT`, ...), else `SETTLEMENT` |
| WAL-11 | ticket 116 | `POST /core/v1/reversals` built (ticket 93, full, idempotent, once); partial reversal for refunds built (116a), used by the checkout refund API (116c) |
| WAL-12 | done | `GET /core/v1/statements` (tickets 93, 94); the app statement `GET /app/v1/statement` (107b, max 90 days, JSON and CSV). USSD mini statement remains ticket 109 |
| WAL-13 | done | `GET /core/v1/transactions/{txnId}` (ticket 93: status and entries); the orchestrator's HOLDING query, inquiry, reversal query and credit check use it (ticket 95a) |
| (REC-07) | done, open item | not an FRS 7.2 id: `GET /core/v1/reports/emoney-total` (ticket 127c, user decision 2026-11-20), the sum of every e-money wallet (all types but SYSTEM) for mw-recon's trust position, only client mw-recon. **Open item for Upay:** the real core must provide this endpoint |

## 8. Supporting services (FRS 8)

| ID | Status | Repo / evidence or reason |
| --- | --- | --- |
| NOT-01 | tickets 111, 133 | transaction notifications on SMS, email and push (ticket 110) built; customer type on `notify.rule` matched to `debitParty.walletType`, and LOCK/UNLOCK notices from `auth.wallet-lock.v1` (ticket 111); the rule-table editor is 133 |
| NOT-02 | done | SMS, email and push templates (title + body, `PUSH_TXN_COMPLETED` / `PUSH_TXN_FAILED`, ticket 110) in Bangla and English in `notify.template` |
| NOT-03 | done | mw-notify SMPP: GSM-7 / UCS2, concatenation, sender id (`SmsEncoder`) |
| NOT-04 | done | delivery receipts update the message status (`DeliveryReport`) |
| NOT-05 | done | priority queues, OTP first (`Priority`) |
| NOT-06 | ticket 134 | retry and DLQ for SMS, email and push built (push: client retry with backoff and breaker, then 2/10/30 s, ticket 110); console view 134 |
| NOT-07 | done | campaigns with a per-second throttle on the PROMO bind (ticket 111): `POST /admin/v1/notify-campaigns` through maker-checker, `CampaignRunnerIT` |
| NOT-08 | tickets 111, 134 | notification log search API built (ticket 111): `GET /internal/v1/notifications`, paged and masked, OTP body always masked; the console view is 134 |
| NOT-09 | done | promotional opt-out (ticket 111): `POST /app/v1/notifications/opt-out` and SMS keyword STOP, honoured for PROMO only |
| REC-01 | done | MT940/942 pull and parse (`TrustStatementPullJob`, `Mt940ParserTest`, ticket 126) |
| REC-02 | done | adapters publish `mw.partner.recon.v1` (D28); `mw-recon` consumes it (ticket 126) |
| REC-03 | done | matching with a poisha tolerance and a date window (`MatchingServiceTest`, ticket 126) |
| REC-04 | done | classes MATCHED, MISSING_IN_PARTNER, MISSING_IN_UPAY, AMOUNT_MISMATCH, STATUS_MISMATCH (`ReconMatchingIT`, ticket 126) |
| REC-05 | tickets 127, 134 | exception work queue read and explain API built (ticket 127: `GET /internal/v1/recon/exceptions`, suggested action, `POST .../explanations`, `ReconReportsIT`); the `recon_exception` maker-checker action in mw-admin-api (FORCE_COMMIT / REVERSE through the ticket 37 paths, recon-maker / recon-checker) and ACTIONED / RESOLVED in mw-recon built (ticket 127b, `ExceptionActionIT` in both); console 134 |
| REC-06 | tickets 127, 134 | reports built (ticket 127): `GET /internal/v1/recon/reports` CSV, XLSX, PDF, daily report stored in `recon.report` (`ReportServiceTest`); console download 134 |
| REC-07 | done | trust position computed and stored with an `AMOUNT_MISMATCH`/`TRUST_POSITION` exception on a gap (`TrustPositionService`, `TrustPositionTest`, ticket 127); the scheduled `TrustPositionJob` (00:45 Asia/Dhaka, yesterday) reads the core's `GET /core/v1/reports/emoney-total` with mw-recon's realm services token (`CoreEmoneyClient`, `TrustPositionJobIT`, core-mock `CoreWalletRestIT`, API folder `61-recon-reports`; ticket 127c). **Open item for Upay:** the real core must provide `GET /core/v1/reports/emoney-total` (contract `core-wallet.v1`, ideally its end-of-day snapshot); mw-core-mock answers its ledger as it is at the call |
| REC-08 | done | file register, filename plus sha256, processed once (`FileRegisterIT`, ticket 126) |
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
| DAT-01 | deviation | JSON Schema in Git, no schema registry (M9, D10, D31): the FRS allows JSON Schema; the registry is not run. `dw.*` topics are in `contracts/events/mw-data-export/` (ticket 128). `recon.exception` is `contracts/events/mw-recon/mw.recon.exception.v1.schema.json` (ticket 127b; consumers mw-audit-dw, mw-data-export) |
| DAT-02 | done | daily DW batch: Kafka stream and the SFTP drop (`DwStreamIT`, `DwDailyExportIT`) |
| DAT-03 | done | PII policy deny-by-default, tokenise through mw-crypto (`PiiPolicyTest`) |
| DAT-04 | done | retention per data type on the saga and the audit projection; archival of the batch facts (`ArchivalIT`) |

## 9. Admin console (FRS 9.1)

| ID | Status | Repo / evidence or reason |
| --- | --- | --- |
| ADM-01 | ticket 131 | partner enable/disable, timeouts and routes built (M6, D28); credential upload and error map API built (131a); the partner engine honours the maintenance window and the mock/real endpoint profile (131b); console tabs built (131c, `ui/mw-admin-ui`: Maintenance, Endpoint (mock/real), Credentials, Error codes) |
| ADM-02 | ticket 131 | API built (131a): write-only credential upload through maker-checker (`partner_credential`, staged in Vault, approved into a field of `secret/mw-adapter-<family>/<partner>`, mw-admin-api's policy write-only there), metadata `GET /admin/v1/credentials`, dashboard feed `/admin/v1/credentials/expiring`, ops email at 30/7/1 days (`CredentialVaultIT`, `CredentialExpiryJobIT`, API folder 45); console credentials tab and the Credentials expiring card (131c) |
| ADM-03 | ticket 132 | channel clients: API built (ticket 100a, `/admin/v1/channel-clients`, maker-checker resource `channel_client`); console in 132 |
| ADM-04 | tickets 98, 132 | API maker-checker for `ROUTING_RULE`, `MSISDN_PREFIX`, `MNP_OVERRIDE` and `MAINTENANCE_MESSAGE` (ticket 98, `RoutingRuleChangeRequestIT`, API folder 35); the console and testing a rule against a sample request stay 132 |
| ADM-05 | ticket 132 | API done in 113: `GET /admin/v1/billers`, `POST /admin/v1/billers/{code}/change-requests` UPSERT / ENABLE / DISABLE, maker-checker (`BillerChangeRequestIT`) |
| ADM-06 | ticket 133b | visual tree editor and paged browser preview built (133a); server preview and short code -> root menu 133b |
| ADM-07 | tickets 111, 133 | SMS template editing built; campaigns through maker-checker (`POST /admin/v1/notify-campaigns`, ticket 111); the rule-table editor is 133c, the test send 133d |
| ADM-08 | ticket 131 | API built (131a): `partner_error_map` change request, published as the partner's next version (`PartnerConfigApiIT`, API folder 45); the Error codes tab (131c, `ui/mw-admin-ui`) |
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
| MCT-04 | done | SMPP faults on the mock-control engine (ticket 102b): parties `smsc` (bind refusal, ESME_RTHROTTLED and any command_status, missing / late / duplicate / out-of-order DLR, DOWN closes the connection), `ussdgw` (bind refusal, ESME_RTHROTTLED, generic_nack, dropped enquire_link, dial) and the per-MNO ports `ussd-gp`, `ussd-robi`, `ussd-bl`, `ussd-teletalk` (ticket 109a, `UssdSmppPortsTest`; `ussd-gp` falls back to `ussdgw`), `payshield` (error codes per command, cut-short answer, reset); `SmppMockControlIT`, API folder 39 |
| MCT-05 | done | file faults on the mock-control engine (ticket 123): FILE dimension MISSING_FILE, LATE_FILE(delayMs), DUPLICATE_FILE, CORRUPT_FILE, INJECTED_MISMATCH(code, count), scoped by next N files or a key (date, file name), journal READ / WRITE / SKIP / LATE with sha256 (`FileFaultScenarioTest`, `SftpMocksIT`, API folder 44) |
| MCT-06 | done | `GET` and `DELETE /mock-control/{party}/journal`, DELETE resets the party's state (ticket 102) |
| MCT-07 | done | `POST /mock-control/reset` on mw-simulators: every local party plus mw-core-mock's ledger back to the seed, one line per party; test route `/test/mock-control/*` (ticket 103, `GlobalResetIT`, `MockControlResetIT`) |
| MCT-08 | ticket 135 | profile `mock-control` only on the test doubles: start guard with `MW_ENV=prod` (`ProdGuardTest`), `scripts/check-mock-control-prod.sh` under the root `check` (ticket 103); the UI part in 135 |
| T1 | tickets 104, 137 | happy path: P2P in `tests/api/src/41-p2p-frs` (ticket 104); every financial use case in 137 |
| T2 | tickets 104, 137 | partner business rejection: P2P core refusal with en/bn text in folder 41 |
| T3 | tickets 104, 137 | timeout, success on inquiry: P2P core `TIMEOUT` nextN in folder 41 |
| T4 | tickets 104, 137 | timeout, failure on inquiry: P2P lost refusal in folder 41 |
| T5 | tickets 96, 104, 137 | never answers, `MANUAL_REVIEW` with alert: P2P in `tests/api/src/90-slow/p2p-frs.js` |
| T6 | tickets 104, 137 | same Idempotency-Key twice: P2P in folder 41 |
| T7 | tickets 104, 137 | duplicate partner callback: P2P `DUPLICATE_CALLBACK` on the core in folder 41 |
| T8 | tickets 98, 137 | circuit open, failover: bill fetch fails over to the catalogue fallback (ticket 114, `BillFetchServiceTest`), PAY pre-send failover is routing (98); P2P fast fail is the core switch DOWN in folder 41 |
| T9 | tickets 104, 137 | insufficient balance / limit: P2P limit before any transfer, and a short balance, in folder 41 |
| T10 | tickets 104, 137 | restart mid-saga (`scripts/chaos.sh` `CHAOS_TARGETS=mw-orchestrator` checks the ledger; folder 41 checks the same identity on a completed P2P) |
| T11 | tickets 125, 137 | AML HOLD |
| T12 | tickets 127, 137 | reconciliation next day |

## 11. Acceptance (FRS 10.4) and phases (FRS 10.5)

FRS 10.4 criteria 1–7 are run by ticket 138 on a fresh stack. FRS 10.5 phases are milestones M7
(foundation, tickets 89–104) to M13 (hardening, tickets 131–138) in `DEPENDENCIES.md`. Performance
scripts are updated but not run at gates (no load tests on the demo host). Coverage (FRS 10.3, 80 %)
is reported, not gated (D19).
