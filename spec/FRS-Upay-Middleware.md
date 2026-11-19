# Upay Middleware – Functional Requirements Specification (Mock API Build)

Nov 19, 2026 · @Ahmad Naqibul Arefin

We will build the full Upay middleware in Java / Spring Boot against mock APIs for every external party and a mock core wallet, so that every RFP flow runs end to end before any partner sandbox exists. When real sandboxes arrive, only connector configuration changes; orchestration, channels and the admin console stay the same.

## 1. Introduction

### 1.1 Purpose

This document defines the functional requirements for the Upay Mobile Money middleware, derived from the Upay RFP v0.1 (sections 3, 5, 6, 8 and Annex B). It is the build baseline for the development team and the basis for acceptance testing of the mock-based release.

### 1.2 Scope

In scope:

- API gateway and App Middleware for all mobile apps (Customer, Agent, Merchant, Distributor, DSO, Partner)
- USSD menu engine and SMPP handling for GP, Robi, Teletalk and Banglalink
- Web Checkout, Org Portal and SP Portal APIs
- Orchestration of all financial flows with idempotency, saga and reversal
- Connectors for every integration in RFP section 5, each backed by a mock server
- A mock core wallet service (balances, debit, credit, hold, release, reversal)
- Notification service (SMS over SMPP 3.4, email over SMTP, push)
- Reconciliation (MT940/MT942 and partner settlement files), AML feed, eKYC
- Audit trail, transaction search, Data Warehouse events (Kafka and SFTP)
- Admin console with RBAC and maker-checker
- Mock control plane: scenario selection, fault injection and test data

Out of scope for this document:

- CI/CD pipelines, GitOps, Kubernetes, Helm, IaC, image registry and cluster operations (RFP ENV-02 to ENV-04)
- Infrastructure monitoring stack setup (ELK, Prometheus, Grafana); the application still emits logs, metrics and traces
- Real partner certification and commercial onboarding
- Mobile app and portal front-end screens (except the admin console)
- A production double-entry ledger (the core wallet is mocked)

### 1.3 Assumptions

1. Every external party is reached through a connector that can point to either a mock or a real endpoint by configuration alone.
2. Mock message formats follow the published or likely format of each party (REST JSON, SOAP XML, SMPP PDUs, ISO 20022 XML, SWIFT MT text, CSV files over SFTP). Exact field lists are replaced when real specifications arrive.
3. Currency is BDT with 2 decimal places; all timestamps are stored in UTC and shown in Asia/Dhaka (UTC+6).
4. User-facing text is available in Bangla and English.
5. Local development runs on Docker Compose; how it is deployed later is not covered here.

### 1.4 Reference stack

| Concern | Choice |
| --- | --- |
| Language / framework | Java 21, Spring Boot 3.x |
| Gateway | Spring Cloud Gateway |
| Integration / routing | Apache Camel (SOAP via CXF, SFTP, Kafka, SMTP components) |
| SMPP | jSMPP (client and mock SMSC/USSD gateway) |
| Resilience | Resilience4j (timeout, retry, circuit breaker, bulkhead) |
| Messaging | Apache Kafka (events, outbox relay) |
| Database | PostgreSQL with Flyway migrations |
| Cache / sessions / locks | Redis |
| Auth | Keycloak (OAuth2/OIDC) for console and partners; JWT for apps |
| Secrets (dev) | HashiCorp Vault dev mode or Spring Cloud Config with encryption |
| SWIFT MT parsing | Prowide Core |
| ISO 20022 | Prowide ISO 20022 or JAXB from XSD |
| Mocks | WireMock (REST/SOAP) plus custom Spring Boot mock apps for stateful parties |
| Admin console UI | React or Angular (team choice) over console REST API |
| Observability (app side) | Micrometer, OpenTelemetry SDK, structured JSON logs |

### 1.5 Glossary

| Term | Meaning |
| --- | --- |
| MFS | Mobile Financial Service |
| MSISDN | Customer mobile number (8801XXXXXXXXX) |
| OCS | Online Charging System of a telco (top-up) |
| C2W / W2B | Card or bank to wallet / wallet to bank |
| AVL | Account validation lookup at a bank |
| NPSB | National Payment Switch Bangladesh |
| IMT | International money transfer (inbound remittance) |
| EC | Bangladesh Election Commission (NID / eKYC) |
| Saga | Multi-step transaction with compensating steps on failure |
| Maker-checker | One user proposes a change, a second user approves it |
| TXN ID | Middleware transaction reference, unique per financial request |

## 2. Solution overview

The middleware is a set of Spring Boot services between channels, the (mock) core wallet and all external parties. Channels never call partners directly: every request passes the gateway, is converted to the canonical model, orchestrated, and sent out through one connector per party.

&#91;embedded content: logical architecture · 4 channel groups, 8 middleware modules, mocks\]

The orchestrator is the only module that moves money; the mock core wallet and mock suite sit where the real core and partners will later connect.

### 2.1 Modules

| ID | Module | Responsibility |
| --- | --- | --- |
| M01 | API Gateway | Single entry point; auth, rate limit, quotas, routing to services, correlation ID |
| M02 | App Middleware | REST and SOAP facade for the six mobile apps; sessions, device binding, PIN verification hand-off |
| M03 | USSD Engine | Menu tree, session state, language, per-MNO adapters over SMPP |
| M04 | Checkout Service | Merchant checkout sessions, redirect, callback and IPN |
| M05 | Portal API | Org Portal and SP Portal: bulk disbursement, SP operations |
| M06 | Orchestrator | Transaction flows, saga steps, compensation, status inquiry, idempotency |
| M07 | Routing Service | Runtime rules picking biller, aggregator, bank or operator, with failover |
| M08 | Connector Framework | Adapter SPI, transformation, resilience policies, per-party config |
| M09 | Connectors (x n) | One adapter per external party (section 6) |
| M10 | Notification Service | SMS, email, push; templates, scheduling, retry, delivery status |
| M11 | Reconciliation Service | MT940/MT942 and settlement file ingestion, matching, exceptions, reports |
| M12 | Compliance Service | AML feed and hold/block handling; eKYC with consent logging |
| M13 | Audit Service | Immutable audit trail of requests, responses, transformations, operator actions |
| M14 | Data Export Service | Kafka event streams and SFTP batch export to the Data Warehouse |
| M15 | Admin Console | Web UI and API for configuration, search, manual actions, maker-checker |
| M16 | Mock Core Wallet | Stand-in for Digital Wallet and Digital Payment microservices |
| M17 | Mock Suite | Simulators for every external party, plus a mock control API |

### 2.2 Canonical transaction model

Every financial request is converted to one canonical object before orchestration. Connectors map it to and from each party's format.

| Field | Type | Notes |
| --- | --- | --- |
| txnId | String (26, ULID) | Generated by middleware; unique |
| idempotencyKey | String (64) | From channel; unique per channel + initiator |
| correlationId | String | Propagated across all hops and logs |
| txnType | Enum | CASH\_IN, CASH\_OUT, SEND\_MONEY, PAYMENT, BILL\_PAY, TOPUP, ADD\_MONEY\_CARD, ADD\_MONEY\_BANK, W2B, REMITTANCE\_IN, ATM\_CASH\_OUT, ATM\_CASH\_IN, QR\_PAY, NPSB\_OUT, NPSB\_IN, DISBURSEMENT, REVERSAL |
| channel | Enum | APP\_CUSTOMER, APP\_AGENT, APP\_MERCHANT, APP\_DISTRIBUTOR, APP\_DSO, APP\_PARTNER, USSD\_GP, USSD\_ROBI, USSD\_TELETALK, USSD\_BL, WEB\_CHECKOUT, ORG\_PORTAL, SP\_PORTAL, PARTNER\_API |
| initiator / debitParty / creditParty | Object | type (WALLET, BANK\_ACCOUNT, CARD\_TOKEN, BILLER, MSISDN, MERCHANT), id, name (masked in logs) |
| amount, fee, vat, commission | Decimal(18,2) | BDT |
| externalRef | Object | partner name, partner txn ref, partner status code |
| status | Enum | See 2.3 |
| metadata | Map | Biller account no., bill month, operator, prepaid/postpaid etc. |
| timestamps | Instant | created, lastUpdated, completed |

### 2.3 Transaction states

| State | Meaning | Next states |
| --- | --- | --- |
| RECEIVED | Validated and persisted, idempotency key reserved | AUTHORISED, FAILED |
| AUTHORISED | Limits, AML pre-check and PIN passed | DEBIT\_HELD, FAILED |
| DEBIT\_HELD | Funds held on the mock wallet | EXTERNAL\_PENDING, COMPLETED, REVERSING |
| EXTERNAL\_PENDING | Request sent to partner, no final answer | COMPLETED, REVERSING, UNKNOWN |
| UNKNOWN | Timeout or ambiguous reply; status inquiry scheduled | COMPLETED, REVERSING, MANUAL\_REVIEW |
| COMPLETED | Partner confirmed, wallet hold committed | REVERSING (only via approved manual reversal) |
| REVERSING | Compensation running | REVERSED, MANUAL\_REVIEW |
| REVERSED | Funds returned to source | (final) |
| FAILED | Rejected before funds moved | (final) |
| MANUAL\_REVIEW | Automatic resolution exhausted; needs operator action | COMPLETED, REVERSED |

Every state change is written to the transaction table and the audit trail in the same database transaction, and published as an event through an outbox table.

## 3. Common platform requirements

These rules apply to every channel and every connector. IDs trace to RFP FR-01 to FR-09 and SEC items.

### 3.1 Gateway and security (FR-01, SEC-04, SEC-09, SEC-10)

| ID | Requirement |
| --- | --- |
| PLT-001 | All inbound traffic enters through the API Gateway; no service is reachable directly from a channel. |
| PLT-002 | Supported auth per client type: JWT (apps, issued after login), OAuth2 client credentials (partners, portals), mTLS (banks, aggregators where configured), API key + HMAC-SHA256 request signature (merchants, checkout callbacks). |
| PLT-003 | Each client (app, MNO gateway, partner, merchant) is registered with: client ID, allowed APIs, auth method, IP allow-list, rate limit, daily quota. |
| PLT-004 | Rate limiting per client and per API with token bucket in Redis; exceeding returns HTTP 429 with Retry-After. |
| PLT-005 | Every request gets an X-Correlation-ID (accepted from caller or generated) carried in all logs, Kafka headers and outbound calls. |
| PLT-006 | Request payload validation (JSON Schema / Bean Validation / XSD) before any business logic; validation errors return code VAL-xxx. |
| PLT-007 | MSISDN, NID, account numbers, card PAN and PIN are masked in logs and audit views (e.g. 8801\*\*\*\*\*6789). PIN and full PAN are never logged or stored. |
| PLT-008 | Replay protection: signed requests carry timestamp and nonce; requests older than 5 minutes or with a reused nonce are rejected. |

### 3.2 Idempotency and duplicates (FR-05, NFR-06)

| ID | Requirement |
| --- | --- |
| PLT-010 | Every financial API requires an Idempotency-Key header (or field for SOAP/USSD, derived from session ID + step). |
| PLT-011 | The key is stored with a hash of the request body. Same key + same body returns the original response; same key + different body returns HTTP 409 IDM-001. |
| PLT-012 | A request arriving while the first is still running returns HTTP 202 with the current status, never a second execution. |
| PLT-013 | Business duplicate check (configurable window, default 60 s): same initiator, type, counterparty and amount raises a confirmation prompt on app/USSD or rejects for API clients. |
| PLT-014 | Inbound partner callbacks (bank, aggregator, IMT, CyberSource) are deduplicated by partner reference. |

### 3.3 Orchestration and saga (FR-04, FR-07)

| ID | Requirement |
| --- | --- |
| PLT-020 | Each flow is defined as ordered steps, each with an action and a compensation (e.g. HOLD funds / RELEASE hold). |
| PLT-021 | Step progress is persisted after each step so a restarted service resumes or compensates without loss. |
| PLT-022 | If the external call fails with a definite error, the orchestrator runs compensations in reverse order and marks the transaction REVERSED or FAILED. |
| PLT-023 | If the external call times out or returns an ambiguous code, the transaction goes to UNKNOWN and a status inquiry job runs at configured intervals (default 30 s, 2 min, 10 min, 1 h). |
| PLT-024 | After the last inquiry without a final answer, the transaction goes to MANUAL\_REVIEW and an alert is raised. |
| PLT-025 | Reversal requests to partners are themselves idempotent and retried until acknowledged. |
| PLT-026 | Fee, VAT and commission are fetched from the (mock) core before debit and returned to the channel for confirmation. |

### 3.4 Resilience (FR-06, NFR-07)

| ID | Requirement |
| --- | --- |
| PLT-030 | Per-connector configurable: connect timeout, read timeout, retry count, backoff, circuit breaker thresholds, bulkhead size. |
| PLT-031 | Retries apply only to safe cases: non-financial reads, and financial calls where the partner supports idempotent references. |
| PLT-032 | An open circuit returns a fast failure (code EXT-503) and triggers failover routing if a fallback partner exists. |
| PLT-033 | Each connector runs in its own thread pool / bulkhead so one slow partner cannot exhaust shared resources. |
| PLT-034 | Asynchronous work (notifications, AML feed, DW export, status inquiry) uses Kafka with persistent topics and dead-letter topics. |
| PLT-035 | Events are published via transactional outbox; no event is lost if Kafka is down. |

### 3.5 Routing (FR-09)

| ID | Requirement |
| --- | --- |
| PLT-040 | Routing rules map (txnType, product, biller code / bank code / operator, amount range, channel) to a primary connector and ordered fallbacks. |
| PLT-041 | Rules are stored in the database, editable in the console with maker-checker, and take effect without restart (cache refresh within 30 s). |
| PLT-042 | Operator prefix to MNO mapping (017/013 GP, 018 Robi, 015 Teletalk, 019/014 Banglalink) is configurable, with MNP override lookup by MSISDN. |
| PLT-043 | A connector or biller can be disabled with a maintenance message shown to users. |

### 3.6 API catalogue and versioning (FR-08)

| ID | Requirement |
| --- | --- |
| PLT-050 | All REST APIs are URI-versioned (/v1/...) and documented in OpenAPI 3.1 generated from code; SOAP services publish WSDL. |
| PLT-051 | A breaking change creates a new version; the old version stays available for at least one release. |
| PLT-052 | Swagger UI and WSDL listing are available in non-production environments. |

### 3.7 Error model

All channel-facing errors use one envelope: code, message (EN), messageBn (BN), txnId, correlationId, timestamp.

| Prefix | Area | Examples |
| --- | --- | --- |
| VAL | Validation | VAL-001 missing field, VAL-002 invalid MSISDN, VAL-003 amount out of range |
| AUTH | Authentication / authorisation | AUTH-001 invalid token, AUTH-002 wrong PIN, AUTH-003 account locked |
| IDM | Idempotency | IDM-001 key reused with different body |
| LIM | Limits | LIM-001 daily limit exceeded, LIM-002 insufficient balance |
| CMP | Compliance | CMP-001 AML hold, CMP-002 KYC incomplete |
| EXT | External party | EXT-400 rejected by partner, EXT-504 partner timeout, EXT-503 partner unavailable |
| SYS | Internal | SYS-500 unexpected error |

Each connector holds a mapping table from partner response codes to these codes, editable in the console.

## 4. Channel requirements

Each channel has its own adapter that turns channel traffic into canonical requests; business rules live only in the orchestrator.

### 4.1 App Middleware (Customer, Agent, Merchant, Distributor, DSO, Partner apps)

| ID | Requirement |
| --- | --- |
| CH-APP-01 | Expose REST/JSON APIs for all apps, plus a SOAP/XML facade for apps or partners that require SOAP (RFP 5.1). Both call the same internal services. |
| CH-APP-02 | Login: MSISDN + PIN (verified by mock core) + device ID; returns access token (15 min) and refresh token (configurable, default 30 days). |
| CH-APP-03 | Device binding: first login on a new device requires OTP over SMS; one active device per customer by default (configurable per app type). |
| CH-APP-04 | Session management: forced logout, concurrent session limit, idle timeout per app type. |
| CH-APP-05 | Every financial API is two-step: /preview (returns fee, VAT, counterparty name, limits check) then /confirm with PIN and Idempotency-Key. |
| CH-APP-06 | Role scoping: an app token can only call APIs allowed for that app type (e.g. cash-in only from Agent app, disbursement only from Partner app / portal). |
| CH-APP-07 | Throttling per app type and per user (e.g. max 5 PIN failures, then lock for 24 h, configurable). |
| CH-APP-08 | Non-financial APIs: balance, statement (last N / date range), profile, limits, saved billers, favourites, notifications inbox. |
| CH-APP-09 | Status API: GET /v1/transactions/{txnId} returns current state for any pending transaction. |

API groups to deliver: auth, account, send-money, cash-in, cash-out, payment, bill-pay, top-up, add-money, transfer-to-bank, remittance, qr, statement, notifications, agent/distributor float (B2B), DSO onboarding (eKYC).

### 4.2 USSD engine and SMPP (FR-10, FR-11)

| ID | Requirement |
| --- | --- |
| CH-USSD-01 | Bind to each MNO USSD gateway over SMPP 3.4 as configured (transceiver or transmitter + receiver), one or more binds per MNO. |
| CH-USSD-02 | Handle bind, enquire\_link (default 30 s), unbind, generic\_nack; reconnect with backoff on loss; windowing (configurable window size, default 10). |
| CH-USSD-03 | Read USSD session fields from TLVs (ussd\_service\_op, its\_session\_info or MNO-specific) via a per-MNO adapter, so GP, Robi, Teletalk and Banglalink differences sit in one place. |
| CH-USSD-04 | Menu tree stored in the database as nodes (menu, input, confirm, action, end) with text in Bangla and English; editable in the console with maker-checker, no code change. |
| CH-USSD-05 | Session state in Redis keyed by MSISDN + session ID, with per-MNO session timeout (default 120 s overall, 30 s per screen). |
| CH-USSD-06 | Response text fits 182 characters (GSM-7) or the Bangla UCS2 limit; long menus paginate with "0. Next" / "00. Back". |
| CH-USSD-07 | PIN input is never echoed, logged or stored in session after verification. |
| CH-USSD-08 | Short code management: each short code (e.g. \*268#) maps to an MNO, root menu and allowed customer types. |
| CH-USSD-09 | If a financial step is still running when the USSD session ends, the result is sent by SMS. |
| CH-USSD-10 | Language selection persists per customer and defaults to Bangla. |

Minimum USSD menus for the mock build: My Account (balance, mini statement, change PIN, language), Send Money, Cash Out, Payment, Mobile Recharge, Pay Bill, Add Money from Bank, Transfer to Bank, Remittance status.

### 4.3 Web Checkout

| ID | Requirement |
| --- | --- |
| CH-WEB-01 | Merchant creates a checkout session (amount, merchant order ID, callback URL, success/fail URLs) with API key + HMAC. |
| CH-WEB-02 | Customer is redirected to the checkout page, enters MSISDN, receives OTP, enters PIN, confirms. |
| CH-WEB-03 | On completion the middleware redirects the browser and sends a signed server-to-server callback (IPN) to the merchant, retried with backoff up to 24 h. |
| CH-WEB-04 | Merchant APIs: create, query status, refund (full and partial), and list transactions by date. |
| CH-WEB-05 | Checkout session expires after a configurable time (default 15 min). |

### 4.4 Org Portal and SP Portal

| ID | Requirement |
| --- | --- |
| CH-POR-01 | Bulk disbursement: upload a CSV/XLSX (MSISDN, amount, reference), validate every row, show a preview with totals and errors. |
| CH-POR-02 | A disbursement batch needs maker-checker approval in the portal before execution. |
| CH-POR-03 | Batches execute asynchronously with throttling, per-row status, retry of failed rows, and a downloadable result file. |
| CH-POR-04 | Organisation wallet balance check before execution; batch is blocked if funds are short. |
| CH-POR-05 | SP Portal: service providers view their transactions, settlements, refunds and reports, scoped to their own data only. |
| CH-POR-06 | Both portals call the middleware through OAuth2 with users and roles defined per organisation. |

## 5. Business use cases and flows

Twenty-two use cases cover every integration in RFP section 5; each must run end to end against mocks in success, failure, timeout and reversal paths.

### 5.1 Use case catalogue

| ID | Use case | Channels | External parties (mocked) |
| --- | --- | --- | --- |
| UC-01 | Customer registration with eKYC | Customer app, DSO app, Agent app | EC NID |
| UC-02 | Login, device binding, PIN change | All apps, USSD | SMSC (OTP) |
| UC-03 | Balance and statement | All apps, USSD | — |
| UC-04 | Send money (P2P) | Customer app, USSD | — |
| UC-05 | Cash-in at agent | Agent app | — |
| UC-06 | Cash-out at agent | Customer app, USSD | — |
| UC-07 | B2B float transfer (distributor to agent) | Distributor app, DSO app | — |
| UC-08 | Mobile top-up, prepaid and postpaid | Customer app, USSD | GP / Robi / Teletalk / Banglalink OCS |
| UC-09 | Utility bill payment | Customer app, USSD | Utility aggregator |
| UC-10 | Other bill payment | Customer app, USSD | SSLCommerz, eKpay, aggregator 3, aggregator 4 |
| UC-11 | Merchant payment (in-store) | Customer app, USSD | Merchant system (SOAP) |
| UC-12 | Web checkout payment and refund | Web Checkout | Merchant callback |
| UC-13 | Add money from card | Customer app | CyberSource |
| UC-14 | Add money from bank (C2W) | Customer app, USSD | Bank gateway |
| UC-15 | Transfer to bank (W2B) with AVL | Customer app, USSD | Bank gateway |
| UC-16 | Inbound international remittance | — (partner initiated) | UCB IMT |
| UC-17 | ATM cash-out and cash-in | ATM | UCB ATM gateway |
| UC-18 | BanglaQR payment (on-us and off-us) | Customer app | BanglaQR / NPSB |
| UC-19 | NPSB interoperable transfer in and out | Customer app | NPSB |
| UC-20 | Bulk disbursement | Org Portal | — |
| UC-21 | AML screening and hold/block | All financial flows | AML (SFTP) |
| UC-22 | Daily reconciliation and DW export | Batch | Trust bank (MT940/942), partners, DW |

### 5.2 Reference flow: mobile top-up (UC-08)

This flow is the template for every "wallet debit, then external call" use case (UC-08 to UC-11, UC-15, UC-18 out, UC-19 out).

1. Channel calls /v1/topup/preview with MSISDN, amount, connection type. Middleware resolves operator by prefix + MNP, checks amount against operator denominations, gets fee from mock core.
2. Channel calls /v1/topup/confirm with PIN and Idempotency-Key. Gateway checks token, rate limit, idempotency.
3. Orchestrator persists RECEIVED, verifies PIN and limits with mock core (AUTHORISED), runs AML real-time rules if enabled.
4. Orchestrator places a hold on the customer wallet (DEBIT\_HELD).
5. Routing picks the operator OCS connector; connector maps canonical to OCS request and sends it (EXTERNAL\_PENDING).
6. Success: hold committed, state COMPLETED, SMS and push sent, event published.
7. Definite failure: hold released, state REVERSED, failure SMS sent.
8. Timeout: state UNKNOWN, status inquiry scheduled per PLT-023; result resolves to 6 or 7, or MANUAL\_REVIEW.

### 5.3 Reference flow: inbound credit (UC-14, UC-16, UC-19 in)

This is the template for flows where a partner pushes money into a wallet.

1. Partner calls the middleware (or the middleware receives a callback after a customer-initiated request).
2. Gateway checks mTLS / signature and partner reference uniqueness (PLT-014).
3. Middleware validates the beneficiary wallet (exists, active, KYC level allows amount, inbound limits).
4. AML screening of sender name and country (remittance) with hold if flagged.
5. Credit wallet via mock core; respond to partner with Upay reference.
6. If credit fails after acceptance, the transaction goes to MANUAL\_REVIEW, never silently dropped.
7. Notify the customer; publish event.

### 5.4 Flow-specific requirements

| ID | Use case | Requirement |
| --- | --- | --- |
| FL-01 | UC-01 | Collect NID number, date of birth, selfie and NID images; call EC; compare name/DOB; store match score; log consent with timestamp and channel; set KYC level (Basic / Full). |
| FL-02 | UC-01 | NID images and selfie are stored encrypted; responses from EC are masked in logs. |
| FL-03 | UC-08 | Postpaid top-up first fetches the outstanding bill from OCS and shows it before payment. |
| FL-04 | UC-09, UC-10 | Two-step bill flow: fetch bill (biller code + account/customer no. + month) then pay; amount may be fixed, minimum or open per biller config. |
| FL-05 | UC-09, UC-10 | Biller catalogue (about 17 utility + 40 other billers) held in the database with aggregator mapping, input fields, validation regex and fallback aggregator. |
| FL-06 | UC-12 | Refund cannot exceed the original amount minus earlier refunds; refunds go to the original wallet. |
| FL-07 | UC-13 | Card data never touches Upay: use CyberSource hosted page / tokenisation; middleware handles only tokens and the 3-D Secure result callback. |
| FL-08 | UC-14 | Customer links a bank account (AVL check), then requests add-money; bank debits account and confirms; middleware credits wallet. |
| FL-09 | UC-15 | AVL before transfer returns account holder name for confirmation; amount held in wallet until bank confirms credit. |
| FL-10 | UC-16 | Support both push (IMT credits wallet) and lookup (IMT asks beneficiary name by MSISDN before sending). |
| FL-11 | UC-17 | ATM cash-out uses a one-time token generated in app (6-digit, 10 min validity); ATM gateway sends token + amount; middleware verifies and debits. |
| FL-12 | UC-18 | Parse and generate BanglaQR (EMVCo merchant-presented) payloads; on-us QR goes to the internal merchant, off-us QR goes via NPSB ISO 20022. |
| FL-13 | UC-20 | Each disbursement row is an individual transaction with its own txnId linked to the batch ID. |
| FL-14 | All | Every completed financial transaction produces a customer SMS, an optional push, and an event for DW and AML feeds. |

## 6. Integration connectors and mocks

Every party in RFP section 5 gets one connector and one mock; switching a connector from mock to real is a configuration change (base URL, credentials, certificates), never a code change.

### 6.1 Connector framework (RFP 5.6 note, FR-02, FR-03)

| ID | Requirement |
| --- | --- |
| CON-01 | A Java SPI (e.g. PaymentConnector, BillerConnector, TopupConnector, BankConnector, FileConnector) with standard operations: execute, inquireStatus, reverse, healthCheck. |
| CON-02 | A new REST or SOAP partner of an existing category can be added by configuration only: endpoint, auth, field mapping template, response code mapping, resilience policy. |
| CON-03 | Field mapping uses declarative templates (e.g. JSONata / FreeMarker / XSLT) stored in the database and versioned. |
| CON-04 | Each connector logs raw request and response (masked) to the audit store with txnId and latency. |
| CON-05 | Each connector exposes health status (UP, DEGRADED, DOWN, MAINTENANCE) to the console and routing service. |
| CON-06 | A developer guide and a sample connector are delivered so the Upay team can add partners without the vendor. |

### 6.2 Connector and mock inventory

| ID | Party | Protocol | Operations the connector must support | Mock type |
| --- | --- | --- | --- | --- |
| EXT-01 | GP, Robi, Teletalk, Banglalink USSD gateways | SMPP 3.4 | bind, deliver\_sm / submit\_sm with session TLVs, enquire\_link | Custom jSMPP server simulating a handset session (web UI to "dial" a short code) |
| EXT-02 | SMSC | SMPP 3.4 | submit\_sm (GSM-7 and UCS2, concatenated), deliver\_sm receipts | Custom jSMPP SMSC with message inbox UI and delivery receipt control |
| EXT-03 | Email server | SMTP | send with HTML template and attachment | MailHog / Mailpit |
| EXT-04 | Push server | HTTPS (FCM/APNs via app server) | send to device token / topic | WireMock with stored messages |
| EXT-05 | AML system | SFTP files | upload transaction file; read alert / hold / block response file | SFTP server + mock AML job that writes response files with rule-based alerts |
| EXT-06 | Trust bank | MT940 / MT942 over SFTP | fetch daily MT940, intraday MT942 | Generator that builds MT940/942 from mock wallet txns with injected mismatches |
| EXT-07 | Bangladesh Election Commission | SOAP / REST | NID verify (NID, DOB) returning name, father/mother name, address, photo | Stateful mock with seed NID records |
| EXT-08 | GP, Robi, Teletalk, Banglalink OCS | REST | recharge, postpaid bill fetch, postpaid pay, status inquiry, reverse | Stateful mock per operator with different payloads and error codes |
| EXT-09 | Utility aggregator (\~17 billers) | REST, bidirectional | biller list, bill fetch, pay, status, reverse; inbound payment-status callback | Stateful mock with seed bills per biller |
| EXT-10 | SSLCommerz, eKpay, aggregator 3, aggregator 4 (\~40 billers) | REST, bidirectional | as EXT-09, each with its own payload style | One mock app with four profiles |
| EXT-11 | Merchant system | SOAP | payment notify, refund, merchant validation | WireMock SOAP stubs |
| EXT-12 | Bank gateway (\~20 banks) | SOAP / REST, bidirectional | AVL, C2W debit request, W2B credit, status, reverse, card payment | Stateful mock with bank profiles (code, protocol) and seed accounts |
| EXT-13 | BanglaQR & NPSB | ISO 20022 (pacs.008, pacs.002, pacs.028, camt.056) | QR payment, fund transfer out/in, status, return | Stateful mock switch with participant list |
| EXT-14 | CyberSource | HTTPS | payment session / token, authorise + capture, 3DS callback, refund | Mock hosted page with test cards (success, decline, 3DS fail) |
| EXT-15 | UCB IMT | HTTPS, inbound | beneficiary lookup, credit wallet, status | Mock partner that calls middleware on demand from a UI |
| EXT-16 | UCB ATM gateway | SOAP, inbound | token verify + cash-out, cash-in, reversal | Mock ATM UI sending SOAP requests |
| EXT-17 | Data Warehouse | Kafka + SFTP | consume event topics; receive daily batch files | Kafka consumer + SFTP drop folder with file validator |
| EXT-18 | Core wallet (Digital Wallet / Digital Payment) | REST (assumed) | see section 7 | Mock Core Wallet service (M16) |

### 6.3 Requirements on every mock

| ID | Requirement |
| --- | --- |
| MCK-01 | Same protocol, transport and security style as the real party (SOAP envelope, mTLS, HMAC, SMPP binds), so connector code is exercised for real. |
| MCK-02 | Stateful where it matters: a paid bill stays paid, a reversed recharge cannot be reversed twice, status inquiry returns the true stored state. |
| MCK-03 | Deterministic test triggers by data (e.g. amount ending .13 = timeout, MSISDN ending 0000 = invalid number, account ending 99 = insufficient funds), documented per mock. |
| MCK-04 | Runtime scenario control via the mock control API (section 10). |
| MCK-05 | Configurable latency (fixed or random range) per operation. |
| MCK-06 | Each mock can send asynchronous callbacks to the middleware with configurable delay, including duplicate and out-of-order callbacks. |
| MCK-07 | Each mock keeps a request journal viewable in a UI and queryable by API for test assertions. |
| MCK-08 | Seed data is loaded from versioned files (JSON/CSV) and resettable with one call. |
| MCK-09 | Every mock runs as a container with the rest of the stack in Docker Compose. |

## 7. Mock core wallet (M16)

The mock core wallet stands in for the Digital Wallet and Digital Payment microservices with real balances and holds, so reversal and reconciliation logic is tested against actual money movement. Its API is our best guess of the core contract and must be swapped for the real one once the core vendor publishes it (Annex B item 1).

### 7.1 Data held

| Entity | Key fields |
| --- | --- |
| Account | walletId, MSISDN, type (CUSTOMER, AGENT, MERCHANT, DISTRIBUTOR, ORGANISATION, SYSTEM), status (ACTIVE, LOCKED, DORMANT, CLOSED), KYC level, PIN hash, PIN retry count |
| Balance | available, held, total (BDT, 2 dp) |
| Hold | holdId, walletId, amount, txnId, expiry, status (ACTIVE, COMMITTED, RELEASED, EXPIRED) |
| Ledger entry | entryId, txnId, walletId, debit/credit, amount, balance after, timestamp |
| Tariff | txnType, channel, customer type, amount slab, fee, VAT %, commission split |
| Limit | txnType, customer type, KYC level, per-txn min/max, daily count/amount, monthly count/amount |
| System wallets | Fee income, VAT payable, commission payable, settlement per partner (telco, biller, bank, NPSB, CyberSource, UCB) |

### 7.2 Operations

| ID | Operation | Behaviour |
| --- | --- | --- |
| WAL-01 | GET /accounts/{msisdn} | Account info, status, KYC level, name |
| WAL-02 | POST /pin/verify | Verify PIN hash; lock after N failures |
| WAL-03 | GET /balance/{walletId} | Available, held, total |
| WAL-04 | POST /charges/calculate | Fee, VAT, commission for given type, amount, channel |
| WAL-05 | POST /limits/check | Pass/fail with remaining daily and monthly amounts |
| WAL-06 | POST /holds | Reserve funds; fails with LIM-002 if available is short |
| WAL-07 | POST /holds/{id}/commit | Move held amount to credit party and system wallets, writing double entries |
| WAL-08 | POST /holds/{id}/release | Return held amount to available |
| WAL-09 | POST /transfers | Direct debit/credit for on-us flows (P2P, cash-in, cash-out, B2B, disbursement row) |
| WAL-10 | POST /credits | Credit from a partner settlement wallet (add money, remittance, NPSB in, ATM cash-in) |
| WAL-11 | POST /reversals | Reverse a completed transfer or credit by txnId; idempotent |
| WAL-12 | GET /statements | Ledger entries by wallet and date range |
| WAL-13 | GET /transactions/{txnId} | Ledger status for status inquiry and reconciliation |

### 7.3 Rules

- Every operation is idempotent by txnId.
- Sum of all ledger entries per txnId is zero (debits equal credits).
- Holds expire after a configurable time (default 30 min) and auto-release, raising an event the middleware must handle.
- Seed data: at least 1,000 customers, 50 agents, 10 distributors, 20 merchants, 3 organisations, all system and settlement wallets, and a realistic tariff and limit sheet.
- The mock supports the same scenario controls as other mocks (forced timeout, forced error, latency).

## 8. Supporting services

Notification, reconciliation, compliance, audit and data export all run off the transaction events, so a failure in any of them never blocks a payment.

### 8.1 Notification service (FR-12, FR-13)

| ID | Requirement |
| --- | --- |
| NOT-01 | Consume transaction and system events from Kafka; a rule table maps event type + outcome + customer type to channels (SMS, email, push) and template. |
| NOT-02 | Templates per channel and language (Bangla, English) with placeholders (amount, fee, balance, txnId, counterparty, time in Asia/Dhaka). |
| NOT-03 | SMS over SMPP 3.4: GSM-7 for English, UCS2 for Bangla, long-message concatenation (UDH or SAR TLVs), sender ID / masking per message type. |
| NOT-04 | Delivery receipts (deliver\_sm) update the message status: SUBMITTED, DELIVERED, FAILED, EXPIRED. |
| NOT-05 | Priority queues: OTP highest, transaction alerts next, promotional lowest; OTP must not wait behind bulk. |
| NOT-06 | Retry with backoff on SMSC/SMTP/push failure; dead-letter after max attempts with console visibility. |
| NOT-07 | Scheduled and bulk notifications (e.g. campaign to a segment) with throttle per second. |
| NOT-08 | Notification log searchable by MSISDN, txnId, channel, status and date; message body masked for OTP. |
| NOT-09 | Customer opt-out for promotional messages; transactional and OTP messages cannot be opted out. |

### 8.2 Reconciliation and settlement (FR-14, FR-15)

| ID | Requirement |
| --- | --- |
| REC-01 | Pull MT940 (daily) and MT942 (intraday) from the trust bank SFTP on schedule; parse with Prowide; store statement lines. |
| REC-02 | Pull partner settlement files (OCS, aggregators, banks, NPSB, CyberSource, UCB) in each partner's format (CSV, XML, fixed width) via configurable parsers. |
| REC-03 | Match statement/settlement lines to middleware transactions and mock wallet ledger by partner ref, amount and date, with tolerance rules. |
| REC-04 | Classify results: MATCHED, MISSING\_IN\_PARTNER, MISSING\_IN\_UPAY, AMOUNT\_MISMATCH, STATUS\_MISMATCH (e.g. Upay REVERSED, partner SUCCESS). |
| REC-05 | Exceptions go to a work queue in the console with suggested action (force complete, reverse, raise dispute); actions require maker-checker. |
| REC-06 | Reports per partner: daily, intraday and on demand; CSV/XLSX/PDF download; totals by count and amount. |
| REC-07 | Trust account position: sum of all e-money wallet balances vs trust bank closing balance, daily. |
| REC-08 | Files are processed once (checksum + filename register); reprocessing needs an explicit operator action. |

### 8.3 Compliance (FR-16, FR-17)

| ID | Requirement |
| --- | --- |
| CMP-01 | Generate AML transaction files (configurable interval, default hourly) to the AML SFTP folder in the agreed format with a control/trailer record. |
| CMP-02 | Read AML response files: ALERT (log only), HOLD (freeze funds of a txn), BLOCK (lock the wallet). Apply them through the mock core and audit each action. |
| CMP-03 | Optional real-time pre-check rules in the middleware (velocity, amount thresholds, watch-list MSISDN) that can stop a transaction with CMP-001. |
| CMP-04 | eKYC calls log consent (customer, purpose, channel, time, agent ID where relevant) before the EC call is made. |
| CMP-05 | NID, DOB and photo data are encrypted at rest and masked in every UI except roles with explicit permission. |

### 8.4 Audit trail (FR-18)

| ID | Requirement |
| --- | --- |
| AUD-01 | Record every inbound request, outbound request, response, transformation result, state change and operator action, with txnId, correlationId, actor, timestamp. |
| AUD-02 | Audit records are append-only: no update or delete through any API; tables are protected by DB permissions and a hash chain (each record holds the hash of the previous). |
| AUD-03 | Search by txnId, MSISDN, partner, channel, status, amount range and time range, returning results within 3 s for 30 days of data. |
| AUD-04 | Full end-to-end view of one transaction: timeline of all hops with payloads (masked) and latencies. |
| AUD-05 | Export of search results to CSV with the export itself audited. |

### 8.5 Data and events (FR-21, FR-22)

| ID | Requirement |
| --- | --- |
| DAT-01 | Publish to Kafka topics: txn.created, txn.state-changed, txn.completed, notification.status, recon.exception, kyc.completed, audit.operator-action, with Avro or JSON Schema in a schema registry. |
| DAT-02 | Daily batch export to the DW SFTP: transactions, wallet snapshots (from mock core), notifications, recon results, with control file (row count, checksum). |
| DAT-03 | PII in DW feeds is masked or tokenised per a configurable field policy. |
| DAT-04 | Retention rules per data type configurable (e.g. online 90 days, archive 10 years), with an archival job that moves data to archive storage and keeps it searchable on demand. |

## 9. Admin console (FR-19, FR-20, SEC-09)

The console is the only way operators change middleware behaviour; every sensitive change goes through maker-checker and is audited.

### 9.1 Functions

| ID | Area | Functions |
| --- | --- | --- |
| ADM-01 | Partner and endpoint onboarding | Create/edit partner, connector type, base URL, auth method, timeouts, retry and circuit-breaker policy, maintenance window, mock/real switch |
| ADM-02 | Credentials and certificates | Upload client certificates and keys (stored in vault, never shown again), API keys, HMAC secrets; expiry dashboard with alerts at 30/7/1 days |
| ADM-03 | Channel clients | Register apps, MNO binds, merchants, portals; IP allow-lists, rate limits, quotas |
| ADM-04 | Routing rules | Create/edit rules and fallbacks (PLT-040), test a rule against a sample request |
| ADM-05 | Billers and products | Biller catalogue, input fields, amount rules, aggregator mapping, enable/disable |
| ADM-06 | USSD menus | Visual tree editor for menus per short code and MNO, Bangla/English text, preview simulator |
| ADM-07 | Templates and notification rules | SMS/email/push templates, rule table (NOT-01), test send |
| ADM-08 | Response code mapping | Partner code to Upay code per connector |
| ADM-09 | Transaction search and detail | Search (AUD-03), detail timeline (AUD-04), current state |
| ADM-10 | Manual actions | Retry status inquiry, force complete, manual reversal, resend notification, release hold, all with reason and maker-checker |
| ADM-11 | Reconciliation workbench | Exceptions queue, actions (REC-05), reports |
| ADM-12 | Compliance actions | View AML holds/blocks, release with approval |
| ADM-13 | Monitoring views | Per channel, partner and biller: volume, success rate, latency p95, failures, circuit state (live, last 1 h / 24 h) |
| ADM-14 | Users and roles | Users via Keycloak SSO with MFA; roles and permission matrix |
| ADM-15 | Audit viewer | All operator actions with before/after values |
| ADM-16 | Mock control (non-prod only) | Scenario switching, fault injection, seed reset (section 10) |

### 9.2 Roles

| Role | Can do |
| --- | --- |
| Viewer | Read-only search and dashboards (masked data) |
| Operations Maker | Propose manual actions and config changes |
| Operations Checker | Approve or reject proposals made by others |
| Finance / Recon | Recon workbench and reports; propose recon actions |
| Compliance | AML holds/blocks, KYC data view (unmasked) |
| Integration Admin | Partners, connectors, credentials, routing (all via maker-checker) |
| Security Admin | Users, roles, audit viewer; cannot approve their own role changes |
| QA / Tester | Mock control, non-production only |

### 9.3 Maker-checker rules

- A change is saved as a draft with a diff view; it is applied only after a checker approves.
- Maker and checker must be different users; a checker cannot approve a change they edited.
- Pending requests expire after a configurable time (default 24 h).
- Approved changes can be rolled back to any previous version, itself via maker-checker.
- Covered items: all of ADM-01 to ADM-08, ADM-10 to ADM-12, ADM-14.

## 10. Mock control, testing and delivery

The mock suite is a product in its own right (RFP ENV-10): QA must be able to force any partner behaviour on demand, and every scenario below must pass before a use case is called done.

### 10.1 Mock control API

| ID | Requirement |
| --- | --- |
| MCT-01 | POST /mock-control/{party}/scenarios sets the active behaviour for an operation: SUCCESS, BUSINESS\_ERROR(code), TIMEOUT, DELAY(ms), HTTP\_5XX, MALFORMED\_RESPONSE, CONNECTION\_RESET, DUPLICATE\_CALLBACK, LATE\_CALLBACK(ms), PARTIAL\_SUCCESS. |
| MCT-02 | Scenarios can be scoped: globally, per MSISDN / account / biller code, or for the next N calls only. |
| MCT-03 | Party-level switches: DOWN (refuse connections), MAINTENANCE, SLOW (latency range). |
| MCT-04 | SMPP mocks additionally support: bind refusal, dropped enquire\_link, throttling error (ESME\_RTHROTTLED), delayed or missing delivery receipts. |
| MCT-05 | File mocks support: missing file, late file, duplicate file, corrupt file, file with injected mismatches. |
| MCT-06 | GET /mock-control/{party}/journal returns received requests for test assertions; DELETE resets journal and state. |
| MCT-07 | POST /mock-control/reset reloads all seed data across all mocks and the mock core wallet. |
| MCT-08 | Mock control is disabled and absent in any production build profile. |

### 10.2 Mandatory test scenarios per financial use case

| # | Scenario | Expected outcome |
| --- | --- | --- |
| T1 | Happy path | COMPLETED; balances, fees, VAT, commission correct; SMS sent; events published |
| T2 | Partner business rejection | REVERSED; hold released; mapped error shown in Bangla and English |
| T3 | Partner timeout, later success on inquiry | UNKNOWN then COMPLETED; no double debit |
| T4 | Partner timeout, later failure on inquiry | UNKNOWN then REVERSED |
| T5 | Partner never answers | MANUAL\_REVIEW after last inquiry; alert raised |
| T6 | Same Idempotency-Key sent twice | One execution; identical responses |
| T7 | Duplicate partner callback | Processed once |
| T8 | Circuit open on primary partner | Routed to fallback where configured, else fast EXT-503 |
| T9 | Insufficient balance / limit exceeded | FAILED before any external call |
| T10 | Service restart mid-saga | Flow resumes or compensates; no lost or double money |
| T11 | AML HOLD response | Funds frozen; customer notified; console shows hold |
| T12 | Reconciliation next day | Transaction appears MATCHED, or the injected mismatch appears as the right exception type |

### 10.3 Test assets to deliver

- Unit tests for business logic (target 80% coverage, RFP NFR-08).
- Integration tests per connector against its mock (Testcontainers).
- Contract tests between middleware and mock core wallet / each mock (OpenAPI or Pact).
- End-to-end suite covering T1 to T12 for every use case in 5.1.
- Performance scripts (Gatling or JMeter) for top-up, send money and bill pay, with mocks set to realistic latency.
- Postman / Bruno collections per channel for manual testing.
- Seed data sets and a masked test data generator (RFP ENV-12).

### 10.4 Acceptance criteria for the mock build

1. Every use case UC-01 to UC-22 runs end to end on a local Docker Compose stack.
2. All T1 to T12 scenarios pass for every financial use case in the automated suite.
3. Any connector can be switched from mock to a different endpoint by console configuration only.
4. A new REST biller can be added by the Upay team using only the console and the developer guide.
5. OpenAPI/WSDL published for every exposed endpoint; mock trigger rules documented per mock.
6. No PIN, full PAN or unmasked NID appears in any log or audit view.
7. Reconciliation of one full mock business day shows zero unexplained differences.

### 10.5 Suggested build phases

| Phase | Content | Exit criterion |
| --- | --- | --- |
| 1. Foundation | Gateway, canonical model, orchestrator, idempotency, audit, mock core wallet, mock control framework, console shell with RBAC | P2P send money T1 to T12 pass via app API |
| 2. Channels | App Middleware (all six apps), USSD engine + SMPP mocks for 4 MNOs, notification service + SMSC mock | UC-02 to UC-07 on app and USSD |
| 3. Telco and billers | OCS connectors, utility aggregator, four bill aggregators, merchant SOAP, web checkout | UC-08 to UC-12 |
| 4. Banks and cards | Bank gateway, CyberSource, UCB IMT, UCB ATM | UC-13 to UC-17 |
| 5. Interoperability | BanglaQR and NPSB ISO 20022 | UC-18, UC-19 |
| 6. Back office | Bulk disbursement, AML, eKYC, reconciliation, DW export, retention | UC-01, UC-20 to UC-22 |
| 7. Hardening | Performance runs, security review of code, documentation, developer guide | Section 10.4 met |

This order follows the RFP priority list (section 11.2) and also covers the PoC Upay may ask for (one USSD flow and one REST biller).

### 10.6 Open questions

- [ ] Core wallet API contract: will the core vendor share a spec, or should our mock API (section 7) be proposed as the contract?
- [ ] Which partners already have sandbox documentation (OCS, banks, aggregators, UCB, NPSB, EC)? Their specs should replace our assumed formats first.
- [ ] Is the push App Server in scope, or only a call to the app vendor's server (Annex B item 7)?
- [ ] Target TPS and latency figures (NFR-01, NFR-02) for performance test design.
- [ ] AML file format and frequency expected by the AML vendor.
- [ ] Is the SOAP facade needed for the Upay apps themselves, or only for partners?
- [ ] Short codes and sender IDs per MNO.
- [ ] Admin console UI framework preference (React or Angular).
