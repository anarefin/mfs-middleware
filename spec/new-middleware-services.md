# upay MFS middleware: service catalogue (from the RFP)

**Status:**
- **Done**: built, no RFP gap.
- **Partial**: built in the repo, RFP features still missing.
- **New**: not built yet.

**Scope rule:** the Core Engine (ledger, customer master, fees, limits, fraud/risk decisions,
cashback) is out of scope. PIN verification and HSM custody are in the middleware.

## Edge
| Service | Status | Core scope / features |
| --- | --- | --- |
| API gateway (APISIX) | Partial | Single entry point; OAuth2/JWT, mTLS, API keys, HMAC; rate limits and quotas per channel and partner; API versioning (FR-01, FR-08) |
| `mw-file-gateway` | New | One SFTP hub for every file flow (AML, MT940/942, settlement, DW batch, bulk drop, regulatory files); PGP, checksums, schedules, idempotent pickup, file audit |

## Security
| Service | Status | Core scope / features |
| --- | --- | --- |
| `mw-auth` | New | MFS login for customers, agents, merchants, distributors and DSOs (MSISDN + PIN); device binding; OTP; sessions; step-up auth; PIN set, change, reset and lockout; PIN verification through `mw-crypto`; signed transaction authorisation assertion for the core; session and device revocation (AML block, care lock) |
| `mw-crypto` (HSM gateway) | New | The only HSM client. PIN: RSA-OAEP decrypt, PVV generate and verify, PIN-block translation (ISO 9564). Also: JWS/XML/HTTP signing, HMAC, tokenisation, envelope encryption, key management and ceremony. Provider SPI: PKCS#11 (SoftHSMv2 in dev) and payment-HSM host commands |
| Keycloak | Partial | One OIDC issuer for the customers, portals, ops (MFA) and partners realms; customer login delegated to `mw-auth` |
| Vault / OpenBao | Partial | Secrets; PKI for service certificates; rotation and expiry alerts (ENV-06) |

## Channels
| Service | Status | Core scope / features |
| --- | --- | --- |
| `mw-app-gateway` | Partial | App Middleware for the 6 apps (customer, agent, merchant, distributor, DSO, partner); REST and a **SOAP facade**; normalisation; throttling; auth delegated to `mw-auth` |
| `mw-ussd` | Partial | Menu engine (bn/en, configurable); sessions; adaptations for each MNO; SMPP and HTTP transports; **all 4 MNOs** (GP, Robi, Teletalk, Banglalink); short codes; PIN handed straight to `mw-auth` |
| `mw-checkout` | Partial | Merchant sessions, hosted page, redirect, signed IPN; **refunds**, payment links |
| `mw-portal-api` | Partial | Org bulk disbursement (maker-checker), SP operations; **SFTP bulk drop** |
| `mw-care-api` | New | Call-centre channel: customer lookup, lock/unlock and PIN-reset requests, case notes; audited and masked |

## Orchestration
| Service | Status | Core scope / features |
| --- | --- | --- |
| `mw-orchestrator` | Partial | Canonical model; saga engine; idempotency; timeout → status inquiry → commit or reversal; manual review. **To add:** flows `ATM_CASH_IN/OUT`, `ADD_MONEY_CARD`, `W2B`, `REMIT_IN`, `QR_PAY_INTEROP`; partner-side and partial reversal; passing the `mw-auth` assertion to the core |
| `mw-routing` (orchestrator module) | New | Runtime routing rules for billers, banks and operators; failover between aggregators; per-partner enable and disable (FR-09) |

## Partner adapters
| Service | Status | Core scope / features |
| --- | --- | --- |
| `mw-adapter-telco` | Partial | GP, Robi, Teletalk and Banglalink OCS top-up (prepaid and postpaid); SIM-swap / subscriber lookup (risk signal) |
| `mw-adapter-billpay` | Partial | Utility aggregator (~17 billers), SSLCommerz, eKpay, aggregators 3/4 (~40 billers); bill fetch, payment, callbacks, biller catalogue sync |
| `mw-adapter-merchant` | New | Merchant system (SOAP) payment notification and confirmation |
| `mw-adapter-bank` | Partial | ~20 partner banks: C2W, W2B, AVL, card payments |
| `mw-adapter-card` | New | CyberSource add money: Flex tokens, 3-D Secure, webhooks; isolated for PCI DSS scope |
| `mw-adapter-remit` | New | UCB IMT inbound remittance: beneficiary validation, credit, status |
| `mw-adapter-atm` | New | UCB ATM gateway (SOAP): cardless cash in/out, reversal advice, PIN blocks through `mw-crypto` |
| `mw-adapter-interop` | New | BanglaQR and NPSB on ISO 20022 (pacs.008/002/028, camt.056/029); QR payloads; XML signatures through `mw-crypto` |

## Compliance, reconciliation, data
| Service | Status | Core scope / features |
| --- | --- | --- |
| `mw-compliance` | New | EC NID / eKYC verification with a consent log; AML transaction feed; intake of block/hold instructions, forwarded to the core and `mw-auth` |
| `mw-recon` | New | MT940/942 parsing; partner settlement files; matching; exception queue; daily, intraday and on-demand reports |
| `mw-audit-dw` (audit) | Partial | Immutable audit of requests, responses, transformations and operator actions (hash chain, HSM-signed anchor); search by txnId, MSISDN, partner and time |
| `mw-data-export` | New | DW Kafka stream and SFTP batch with tokenised identifiers; retention and archival (FR-21/22). Can be split out of `mw-audit-dw` or kept inside it |

## Notification and operations
| Service | Status | Core scope / features |
| --- | --- | --- |
| `mw-notify` | Partial | SMS (SMPP 3.4), email (SMTP), templates (bn/en), delivery receipts, OTP priority; **push (FCM/APNs)**, scheduling, channel fallback |
| `mw-webhook` | New | Signed outbound callbacks with retries and a delivery log for merchants, SPs and orgs |
| `mw-admin-api` + `mw-admin-ui` | Partial | Txn search, saga timeline, manual TSQ and reversal, maker-checker, partner toggle and timeouts, USSD menus, SMS templates. **To add:** partner onboarding, credential and certificate management, routing and throttling rules, recon exceptions, care role, HSM key-ceremony screens |
| API catalogue / developer portal | New | OpenAPI/WSDL catalogue generated from `contracts/`; sandbox keys (FR-08) |

## Test and dev assets (ENV-10/12)
| Service | Status | Core scope / features |
| --- | --- | --- |
| `mw-simulators` | Partial | Simulators today for OCS, utility aggregator, C2W bank, SMSC, USSD gateway. **To add:** every other RFP partner (aggregators, merchant SOAP, CyberSource, UCB IMT/ATM, NPSB, EC, AML SFTP, trust bank MT940) and **a payment-HSM simulator** (host commands, keys in SoftHSMv2) |
| `mw-core-mock` | Partial | Core wallet test double; **to add:** verifying the `mw-auth` assertion in place of the PIN check |
| `softhsm` (SoftHSMv2 container) | New | Dev/test PKCS#11 token for `mw-crypto`; init script with fake PINs |
| `mw-seeder` | Done | Seeds the dev/test baseline |
| `mw-test-console` | Done | Manual testing UI for channels and simulators |

## Open points
- Production HSM vendor and model: decides the host-command protocol of the payment-HSM simulator.
- The core vendor accepts the `mw-auth` transaction assertion instead of a PIN.
- Push server: in scope or the app vendor's.
