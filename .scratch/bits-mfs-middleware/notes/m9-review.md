# M9 gate code review: 69a5a33c..152bca56

Scope: first-parent merges of tickets 113a-c, 114, 115a-b, 116a-c, 117a-d, 118a, 119a, 120a, 127a-c and 139. The review was split by area (the merchant+card, remit+atm, billpay/bank/simulators/deploy/contracts, orchestrator/channels and admin-api/recon groups). Each finding was verified against the code at HEAD. Axis: Standards = root/service `CLAUDE.md`; Spec = ticket files and `spec/FRS-Upay-Middleware.md`.

Counts: Critical 0, High 6, Medium 15, Low 16.

Checked and clean (no finding):
- **Partner framework copies:** `partner/` and the Kamelet folders are byte-identical once the package is normalised, across all 7 adapters.
- **Constructor injection:** no field injection.
- **Logging:** no `System.out`.
- **Kafka listeners:** no blocking on listener threads.
- **SOAP parsing:** hardened against XXE (merchant, atm).
- **Compose:** memory limits are set, and `mock-control` is enabled only in the test doubles.
- **Refund caps:** cumulative and concurrent refund caps hold (checkout, orchestrator, core-mock lock).
- **Endpoint scoping:** refund, payment list and linked accounts are scoped to the merchant or wallet.
- **mw-recon internal token:** checks issuer, audience, `azp` and expiry.
- **Recon maker-checker:** a maker cannot approve their own request.
- **Bill fetch failover:** only an INQUIRY that was never sent fails over.

## High

- **[High · Spec] ATM VERIFY/CASH_IN answer ACCEPTED with no token check, hold or credit (fail-open on cash)**
  - **Where:** `services/mw-adapter-atm/src/main/java/mfs/mw/adapter/atm/service/AtmInboundService.java:58-64`, `controller/AtmSoapEndpoint.java:28-34`, `contracts/api/mw-adapter-atm/atm-soap.v1.xsd` (the Status enum is only `ACCEPTED`).
  - **Defect:**
    - `VerifyTokenAndDebit` returns `ACCEPTED` without checking the token, placing a hold or publishing anything.
    - `CashIn` does the same with no credit.
    - The XSD has no decline status.
  - **Failure:** a caller holding the shared UsernameToken sends any MSISDN, any 6-digit token and any amount. The switch reads ACCEPTED as "dispense" and dispenses cash while no wallet is debited.
  - **Fix:** until 120b, have VERIFY and CASH_IN return a SOAP fault (`MW-SYS-*`, not enabled) behind a flag that is off by default. Add DECLINED and PENDING statuses to the XSD before it is published to UCB.

- **[High · Spec] Accepted remittances are dropped by the orchestrator**
  - **Where:** `services/mw-orchestrator/src/main/java/mfs/mw/orchestrator/service/PartnerInboundService.java:100-102` together with `services/mw-adapter-remit/src/main/java/mfs/mw/adapter/remit/service/RemitInboundService.java:138-144`.
  - **Defect:** the adapter answers 202 ACCEPTED and publishes kind REMITTANCE. The orchestrator logs `partner.inbound.unsupported` and drops every kind except CALLBACK, because 119b is not built.
  - **Failure:** UCB IMT sends a credit and gets a Upay reference with status ACCEPTED. No credit and no MANUAL_REVIEW follows, and `GET /remit/v1/credits/{ref}` shows ACCEPTED forever. This breaks the ticket's "once accepted, never silently dropped".
  - **Fix:** either refuse credits (503, not enabled) until the REMIT_IN flow is merged, or park REMITTANCE events as MANUAL_REVIEW in the orchestrator. Add an end-to-end check.

- **[High · Standards (spec 6.4 outbox)] Remittance row and Kafka publish are a dual write, which can double-credit**
  - **Where:** `services/mw-adapter-remit/src/main/java/mfs/mw/adapter/remit/service/RemitInboundService.java:104-137`.
  - **Defect:** `publisher.publish(...)` is a synchronous `KafkaTemplate.send().get(12s)` inside `tx.execute`. The `EventPublisher` exception in the root CLAUDE.md only covers requests that change no local state, but here the `remittance` row is that state. `OutboxWriter` is already in the module.
  - **Failure:** Kafka stores the record but the ack times out (or the DB commit fails after the ack). The row rolls back and the partner gets a retryable 503. On the resend a new txnId and inboundId are minted, so there are two REMITTANCE events for one partnerRef that the orchestrator inbox cannot deduplicate, and 119b double-credits.
  - **Side effect:** each request also holds one of the 2 Hikari connections for up to 12 s.
  - **Fix:** write the event through `OutboxWriter` in the same transaction as the row, or derive txnId and inboundId deterministically from (partnerId, partnerRef).

- **[High · Spec] A bare HTTP 404 on STATUS_QUERY becomes final NOT_FOUND for three aggregators, so a paid bill's hold is released**
  - **Where:** `deploy/partner-routes/aggregators/generate.mjs:87-88`, generated into `services/mw-adapter-billpay/src/main/resources/aggregators-dev.yml:56,111,222` and the mw-admin-api seeds.
  - **Defect:** for sslcommerz, ekpay and agg4 any 404 maps to `NOT_FOUND`, and `definiteStatuses=200,404` makes it final. The billagg and bank generators also require `body.status = "NOT_FOUND"`; these three dropped that guard.
  - **Failure:** a PAY ends UNKNOWN but was actually applied. The status inquiry hits a gateway or load-balancer 404 (for example a route missing during a deploy). That becomes NOT_FOUND and is then treated as FAILED, so the customer's hold is released while the bill is paid. Money is lost and nothing alerts.
  - **Fix:** require the aggregator's own not-found marker in the body, and map any other 404 to UNKNOWN. Regenerate the files and add a "bare 404 is UNKNOWN" case to `BillAggregatorRouteIT`.

- **[High · Spec (REC-05, FRS 10.4 #7)] A recon exception whose action failed is stuck ACTIONED forever**
  - **Where:** `services/mw-recon/src/main/java/mfs/mw/recon/service/ExceptionQueueService.java:99-110`, `repository/ReconStore.java:285-301`.
  - **Defect:** ACTIONED moves only to RESOLVED, and only when the transaction reaches the expected status. `explain` and `markActioned` require OPEN or EXPLAINED, and admin-api `actionable()` refuses anything else.
  - **Failure:** an approved FORCE_COMMIT fails (the transaction goes FAILED or back to MANUAL_REVIEW), or the core refuses the REVERSAL. The exception can then never be explained or re-actioned, so the day never reaches zero unexplained differences.
  - **Fix:** when an ACTIONED transaction reaches a terminal status other than the expected one, move the exception back to OPEN (or to `ACTION_FAILED`) and emit `mw.recon.exception.v1`. Allow `explain` from that state.

- **[High · Spec (REC-07)] The trust position is keyed by the wrong date, so quiet days are silently skipped**
  - **Where:** `services/mw-recon/src/main/java/mfs/mw/recon/service/ReconService.java:333-340`, `repository/ReconStore.java:340`, `scheduler/TrustPositionJob.java:49-58`.
  - **Defect:** the closing balance is keyed by `source_file.business_date`. That is the latest `:61:` value date, or `LocalDate.now()` when the file has no lines. The date in the 62F field is ignored, and no run catches up a missed day.
  - **Failure:**
    - Yesterday's statement with no `:61:` lines gets today's date. The 00:45 job finds nothing for yesterday, logs at INFO and returns, so that day's position is never computed and nothing alerts.
    - A forward-value-dated line moves the balance to the wrong day.
  - **Fix:** store and look up the balance by the 62F date. Compute every missing day since the last `trust_position`, and log at WARN when yesterday's balance is missing.

## Medium

- **[Medium · Spec (FL-06)] A checkout refund can be stuck PENDING after the money moved**
  - **Where:** `services/mw-checkout/src/main/java/mfs/mw/checkout/service/RefundService.java:112-121`, `RefundOutcomes.java:51`, `RefundRepository.java:77-79`.
  - **Defect:** when `publisher.publish` throws (for example a send timeout after Kafka stored the record), `deleteUnsent` removes the row, but the orchestrator still runs the refund under the old refundId. The merchant then retries with the same Idempotency-Key and checkout inserts a new row. The orchestrator answers a duplicate under the old txnId, and `RefundOutcomes.apply` ignores duplicates.
  - **Failure:** the payer has the refund, but the merchant's refund stays PENDING forever with no IPN, and `refunded` is never counted.
  - **Fix:** on a publish exception, keep the row PENDING (and answer 503 or 202) instead of deleting it, or reuse the stored refundId on a retry with the same key. Also settle duplicates by `attributes.requestTxnId`.

- **[Medium · Spec (CH-WEB-03/FL-06)] A full reversal after a partial refund parks the payment in MANUAL_REVIEW**
  - **Where:** `services/mw-orchestrator/src/main/java/mfs/mw/orchestrator/service/TransactionService.java:256-300` (check at :279), `saga/SagaMachine.java:125`.
  - **Defect:** the admin/SYSTEM full reversal ignores existing refunds of the original. It sends a ReverseTransaction for the whole amount, and the core refuses with `REFUND_EXCEEDS`.
  - **Failure:** the payment goes COMPLETED → REVERSING → MANUAL_REVIEW, and every later merchant refund is refused `MW-VAL-021`.
  - **Fix:** in `acceptReversal`, refuse a partially refunded original up front (or reverse only the remainder). Map `ReversalRejected/REFUND_EXCEEDS` to a refusal and keep the original COMPLETED.

- **[Medium · Spec (115)] A merchant NOTIFY can be lost with no retry, backoff or DLQ**
  - **Where:** `services/mw-adapter-merchant/src/main/java/mfs/mw/adapter/merchant/service/MerchantPartnerService.java:77-88`, `messaging/MerchantRequestHandler.java:51-54,65-76`, `services/mw-orchestrator/src/main/java/mfs/mw/orchestrator/service/PartnerReplyService.java:217-262`.
  - **Defect:**
    - The adapter commits the inbox claim and acks before the call. If it dies or throws afterwards, no reply is written.
    - NOTIFY is not a `partnerCall()`, so no orchestrator deadline recovers it.
    - Retries happen only in `notifyAnswer`, they run immediately with no backoff, and "dead-letter" is only an ERROR log plus an audit event.
  - **Failure:** the adapter is killed after the claim and the merchant never learns of the payment, with no audit and no console entry. Separately, a 30 s merchant outage burns all 3 attempts in a few seconds.
  - **Fix:** add an orchestrator sweep that re-sends or dead-letters a NOTIFY with no answer after `partnerTimeout`, with a backoff between attempts and a real DLQ the console can see. The adapter should write a FAILED/UNKNOWN reply in its catch block.

- **[Medium · Spec (FL-08/UC-14), security] A linked bank account is not bound to the wallet owner, and AVL has no rate limit**
  - **Where:** `services/mw-app-gateway/src/main/java/mfs/mw/app/gateway/service/LinkedAccountService.java:45-63`.
  - **Defect:** linking needs only an account the bank's AVL accepts. The holder is never compared with the wallet's KYC, and failed AVL calls are not counted.
  - **Failure:**
    - A customer links a third party's account and uses `ADD_MONEY_BANK` to ask the bank to debit it.
    - A script enumerates which account numbers exist, and each holder's initial, via `POST /app/v1/bank-accounts`.
  - **Fix:** require the holder to match the KYC name (or a bank-confirmed MSISDN↔account link) before storing. Rate-limit AVL attempts per wallet.

- **[Medium · Spec (FL-09)] No AVL or holder-name confirmation before a transfer to bank**
  - **Where:** `services/mw-app-gateway/src/main/java/mfs/mw/app/gateway/service/PreviewService.java:232-249`.
  - **Defect:** FL-09 asks for AVL before the transfer, returning the holder name for confirmation. The W2B preview only quotes and never shows a holder name.
  - **Failure:** an account closed or renamed after linking is paid without a fresh check, and the customer never sees who they are paying.
  - **Fix:** call `BankAccountClient.validate` in the W2B preview and return the masked `holderName` in `PreviewView`.

- **[Medium · Spec (117a/117d)] Bank callbacks have no edge route and no shared HMAC secret**
  - **Where:** `deploy/apisix/routes/` (only `partner-c2wbank-callback.json`), `deploy/provision/services.sh` (bank `hmac-secret` loop).
  - **Defect:** the 20 `bank-<code>` profiles use hmac-sha256 callbacks, and C2W debits answer 202 PENDING with the outcome by callback. But there is no APISIX route for them, and each bank's `gen_once` secret is never synced to mw-simulators.
  - **Failure:** in production a bank callback gets a 404 at APISIX. In dev the simulator's callbacks get 401. Every C2W then resolves only by status inquiry after the deadline, and the duplicate-callback test T7 cannot pass.
  - **Fix:** generate a callback route per bank (or one `bank-[a-z]+` route). Sync one dev HMAC value into both `secret/mw-adapter-bank/bank-<code>` and `sim.callback-secrets.bank-<code>`.

- **[Medium · Spec (113a, FL-04)] `billMonth` is validated, then dropped**
  - **Where:** `services/mw-adapter-billpay/src/main/java/mfs/mw/adapter/billpay/service/BillFetchService.java:83-102`, `mapper/PartnerCallMapper.java:74-80`.
  - **Defect:** `fetchCall(...)` has no month parameter, so no INQUIRY mapping can send it.
  - **Failure:** for a biller with `billMonthRequired=true`, the customer picks 202409 but sees and pays the aggregator's default (current) month.
  - **Fix:** pass `billMonth` as `input.billMonth`, map it in the INQUIRY mappings that need it, and add a wire-level test.

- **[Medium · Spec/Security (139)] A dry run still sends the live OAuth2 client secret**
  - **Where:** `services/mw-adapter-*/src/main/java/.../partner/route/PartnerRouteLoader.java:594-606` with `partner/auth/OAuth2ClientCredentials.java:128-136` (all 7 copies).
  - **Defect:** the token request now goes to the dry-run target, but it still authenticates with the live `clientSecret` over HTTP Basic.
  - **Failure:** a candidate whose `dryRun.baseUrl` is a partner sandbox (or the dev simulator over plain HTTP, which records headers) receives the production client secret. Ticket 139's "never … with the live client secret" is only half met.
  - **Fix:** refuse `auth=oauth2-cc` in a dry run unless the record names a dry-run credential field (for example `dryRun.secretField`), or send a fixed dummy secret. Change all copies together.

- **[Medium · Spec (PLT-025)] ATM inbound operations have no cross-operation state or fingerprint**
  - **Where:** `services/mw-adapter-atm/src/main/java/mfs/mw/adapter/atm/service/AtmInboundService.java:66-81,91-107`, `src/main/resources/db/migration/V8__atm_inbound.sql`.
  - **Defect:**
    - Each operation is deduplicated only on (operation, atmTxnRef), so CONFIRM and REVERSAL for one cash-out are both ACCEPTED.
    - `atmId` is not checked against the VERIFY.
    - No amount or MSISDN fingerprint is stored.
  - **Failure:**
    - Once 120b publishes, a confirm plus a late reversal both commit the money and release the hold.
    - A replayed VERIFY with the same ref but a different MSISDN or amount gets ACCEPTED instead of an idempotency conflict.
  - **Fix:** keep a per-cash-out state machine (VERIFIED → CONFIRMED | REVERSED), store atmId and a fingerprint, and answer conflicts with an `MW-IDEM-*` fault.

- **[Medium · Spec (119/120, ticket 100)] No edge route for remit or UCB ATM inbound; the ATM password is sent as PasswordText**
  - **Where:** `deploy/apisix/routes/` (no remit or ucbatm route), `services/mw-adapter-atm/src/main/java/mfs/mw/adapter/atm/security/WsSecurityInterceptor.java:22-27`.
  - **Defect:** neither inbound API has an APISIX route. The ATM interceptor relies on edge TLS but takes PasswordText.
  - **Failure:** in production the partners cannot reach either service. If the service port is exposed directly to work around that, the ATM password crosses the network in clear text in every SOAP header.
  - **Fix:** add routes for `/remit/v1/*` and `/partner/ucbatm/ws/v1*` with mTLS (or the ticket-100 client registry) and the `X-Mw-*` strip. Document them in `deploy/apisix/README.md`.

- **[Medium · Spec (PLT-008)] ATM UsernameToken has no nonce or timestamp replay protection**
  - **Where:** `services/mw-adapter-atm/src/main/java/mfs/mw/adapter/atm/security/WsSecurityInterceptor.java:61-76`.
  - **Defect:** only Username and Password are checked: no `wsu:Created` window and no `wsse:Nonce` store. The remit side has both.
  - **Failure:** a captured request can be replayed indefinitely with a new atmTxnRef.
  - **Fix:** require Nonce and Created, reject a Created outside ±300 s, and record nonces in the existing `callback_nonce` table (or move to PasswordDigest or mTLS).

- **[Medium · Spec (REC-05)] mw-recon learns of an approved action only through a best-effort call**
  - **Where:** `services/mw-admin-api/src/main/java/mfs/mw/admin/api/service/ReconExceptionChangeHandler.java:130-144`.
  - **Defect:** `afterApproved` calls mw-recon after the commit. A failure is logged as UNKNOWN and never retried, with no outbox and no sweep.
  - **Failure:** mw-recon is down while a REVERSE is approved. The transaction becomes REVERSED, but the exception stays OPEN and never resolves (`resolveAwaiting` only looks at ACTIONED rows). A new action is refused, and mw-recon has no record of who approved what.
  - **Fix:** record the action durably (an outbox command, or a retry job over APPROVED requests with no acknowledgement), or let mw-recon resolve OPEN exceptions whose transaction reaches the target status.

- **[Medium · Spec (REC-05)] Only FORCE_COMMIT and REVERSE go through maker-checker, and the suggested action names do not match**
  - **Where:** `services/mw-recon/src/main/java/mfs/mw/recon/dto/ActionRequest.java:12`, `service/ExceptionQueueService.java:151-160`.
  - **Defect:** the suggestions return `FORCE_COMPLETE`, which no endpoint accepts. RAISE_DISPUTE and ACCEPT_AS_EXPLAINED are not change requests, although REC-05 and ticket 127 require them to be.
  - **Failure:** MISSING_IN_UPAY, AMOUNT_MISMATCH and trust-position exceptions can be closed by a single operator's explanation with no checker.
  - **Fix:** return `FORCE_COMMIT` in the suggestion. Route RAISE_DISPUTE and ACCEPT_AS_EXPLAINED through a `recon_exception` change request, or record a split decision.

- **[Medium · Spec (REC-07) / Standards (ShedLock)] Re-running the trust position duplicates exceptions or orphans them**
  - **Where:** `services/mw-recon/src/main/java/mfs/mw/recon/service/TrustPositionService.java:65-74`, `config/SchedulingConfig.java:19`.
  - **Defect:** a second compute for the same day overwrites the `trust_position` row but always inserts a new exception. There is no `lockAtLeastFor`, so a second instance can run the job again.
  - **Failure:**
    - Two OPEN exceptions for one gap double-count in reports and the work queue.
    - A clean re-run sets `exception_id = null` while the old exception stays OPEN.
  - **Fix:** reuse the day's exception, or resolve it as superseded. Add `lockAtLeastFor` (for example PT5M).

- **[Medium · Standards (masking)] The raw MT940 `:86:` narrative and operator explanations go out unscrubbed**
  - **Where:** `services/mw-recon/src/main/java/mfs/mw/recon/service/ExceptionEvents.java:63`, `service/ExceptionQueueService.java:163-169`, `service/Mt940Parser.java:72-73`.
  - **Defect:** `reference` (the counterparty name and account number in real statements) and the free-text `explanation` reach `ExceptionView` and `mw.recon.exception.v1` (`statementRef`) without masking. Up to 390 characters can also exceed the contract's `maxLength: 255`.
  - **Failure:** "A/C 1234567890123 KARIM" reaches the console, audit and DW unmasked, and an over-long narrative breaks the contract.
  - **Fix:** run `MaskingPolicy.scrub` and truncate before building the view or event, and prefer `partnerRef`/txnId for `statementRef`.

## Low

- **[Low · Spec (FL-07/118)] Card capture is marked SUCCESS without checking amount, currency or reference**
  - **Where:** `services/mw-adapter-card/src/main/resources/application-dev.yml:92-108`.
  - **Defect:** SUCCESS needs only `status = "CAPTURED"` and a `paymentId`.
  - **Failure:** a PAY for 5,000 against a 500 session, or a CAPTURED status answer for another capture, credits the wallet the requested amount.
  - **Fix:** have the acquirer echo amount, currency and clientReference, and answer UNKNOWN (`AMOUNT_MISMATCH`) when they differ.

- **[Low · Spec] The card-data guard checks names only, narrowly, and skips STATUS_QUERY**
  - **Where:** `services/mw-adapter-card/src/main/java/mfs/mw/adapter/card/mapper/PartnerCallMapper.java:33-34,54-63,97`.
  - **Defect:**
    - STATUS_QUERY returns before the check while its attributes are still forwarded.
    - The pattern misses `primaryAccountNumber`, `ccNumber`, `expMonth`, `securityCode`, `track2` and similar.
    - Attribute values are never inspected.
  - **Fix:** run the check first and use an allowlist of keys (`sessionId`, `transientToken`), optionally with a Luhn check on values.

- **[Low · Spec] A card PAY without `sessionId` or `transientToken` is sent anyway**
  - **Where:** `services/mw-adapter-card/src/main/java/mfs/mw/adapter/card/mapper/PartnerCallMapper.java:40-74`, `application-dev.yml:89-91`.
  - **Failure:** the request is sent with nulls, and the result is a partner error or an UNKNOWN that triggers status inquiries for a request that was always invalid.
  - **Fix:** refuse it with `MW-VAL-INVALID_PARTNER_REQUEST` before sending.

- **[Low · Security] The acquirer's `hostedPageUrl` is relayed unchecked**
  - **Where:** `services/mw-adapter-card/src/main/resources/application-dev.yml:80-83`.
  - **Failure:** a `javascript:` URL or a phishing host is handed to the app, which sends the customer there to type the card.
  - **Fix:** accept only https URLs on an allowlisted host, and answer UNKNOWN `MALFORMED_REPLY` otherwise.

- **[Low · Standards] Contracts owned by other services were changed in adapter-only commits**
  - **Where:** `contracts/events/mw-adapter-billpay/mw.partner.route.status.v1.schema.json`, `mw.partner.recon.v1.schema.json`, `contracts/events/mw-orchestrator/mw.partner.{request,reply,inbound}.v1.schema.json`.
  - **Defect:** the `family` enum was extended in commits `4f974e08`, `538da76f`, `372dceb2` and `a58834bd`. The tickets ask for the family value (115/118 split decisions), but there is no `OWNERS.yml` note or ADR, and the owners' tests were not part of those changes.
  - **Fix:** record the sanctioned owner-contract change (an ADR or `OWNERS.yml` note) and run the owners' `ProducedContractsTest`/`ConsumedContractsTest`.

- **[Low · Spec] Fee handling differs between one full refund and partial refunds**
  - **Where:** `services/mw-core-mock/src/main/java/mfs/mw/core/mock/service/PostingOperations.java:108-170`.
  - **Defect:** a refund equal to the whole payment takes the full-reversal path and returns the fee. Partial refunds keep it.
  - **Fix:** send refunds through `partial`, or document the fee rule.

- **[Low · Standards/Spec] Linked-account storage**
  - **Where:** `services/mw-app-gateway/src/main/java/mfs/mw/app/gateway/service/LinkedAccountService.java:46-54`, `AccountCipher.java`.
  - **Defect:**
    - The `count()` then `insert()` per-wallet cap is not atomic, so parallel links can go past `maxPerWallet`.
    - "Already linked" uses an `MW-IDEM-*` code.
    - It uses local AES instead of the mw-crypto envelope (already an open item).
  - **Fix:** enforce the cap with a DB constraint or conditional insert, and use an `MW-VAL-*` code.

- **[Low · Standards (masking)] The remittance fingerprint is an unsalted SHA-256 of the MSISDN**
  - **Where:** `services/mw-adapter-remit/src/main/java/mfs/mw/adapter/remit/service/RemitInboundService.java:232-241`.
  - **Defect:** the fingerprint is `sha256(msisdn|valueMinor|BDT)`.
  - **Failure:** about 7×10⁸ Bangladeshi MSISDNs can be brute-forced in minutes, so the MSISDN is effectively stored in `remit_ref`.
  - **Fix:** use HMAC-SHA256 with a key from Vault.

- **[Low · Security] The OAuth2 token cache is never pruned**
  - **Where:** `partner/auth/OAuth2ClientCredentials.java:71` (all copies).
  - **Defect:** there is one slot per routeId and secret hash, and none is ever removed.
  - **Failure:** valid bearer tokens outlive version swaps, dry-run candidates and secret rotations, and the map grows for the life of the process.
  - **Fix:** evict a route's slots when it is removed, or use a bounded expiring map.

- **[Low · Spec (REC-06)] The stored daily report is never marked stale**
  - **Where:** `services/mw-recon/src/main/java/mfs/mw/recon/service/ReportService.java:82-85`.
  - **Failure:** a settlement file matched at 10:00 still shows the 02:00 totals.
  - **Fix:** re-render or re-store the report after a later run for that day.

- **[Low · Spec (DAT-01)] A trust-position exception emits the whole balance as `amountMinor`**
  - **Where:** `services/mw-recon/src/main/java/mfs/mw/recon/service/ExceptionEvents.java:61-62`, `TrustPositionService.java:70-71`.
  - **Failure:** DW shows a mismatch of crores when the actual gap is BDT 120.
  - **Fix:** emit `abs(file - upay)`.

- **[Low · Spec (113)] A biller UPSERT accepts unknown keys and duplicate field names**
  - **Where:** `services/mw-admin-api/src/main/java/mfs/mw/admin/api/service/BillerCatalogService.java:84-86`, `BillerChangeHandler.java:52,66-67`, `BillerRules.java:60-93`.
  - **Failure:**
    - Extra keys are published once and then dropped by the next ENABLE or DISABLE, so the projections diverge.
    - Duplicate `accountNo` fields make the channel prompts ambiguous.
  - **Fix:** reject unknown keys and require unique field names.

- **[Low · Spec (117d simulator)] The bank gateway mock can double-credit on concurrent resends**
  - **Where:** `services/mw-simulators/src/main/java/mfs/mw/simulators/service/BankGatewayService.java:152-156,229-237`.
  - **Defect:** the balance changes before `putIfAbsent`, and the existence check is not atomic with it.
  - **Failure:** concurrent W2B resends both credit the account, so T3/T7 can flake.
  - **Fix:** apply inside `computeIfAbsent` or a per-key lock.

- **[Low · Spec (114 simulator)] The aggregator mock's bill-state check runs outside the ledger lock**
  - **Where:** `services/mw-simulators/src/main/java/mfs/mw/simulators/service/BillAggregatorMockService.java:115-118`.
  - **Failure:** two references paying the same bill at once both succeed.
  - **Fix:** move the check into the synchronized `AggregatorBillLedger.pay`.

- **[Low · Spec (MCK-06)] Callback replay for the new aggregators always returns 404**
  - **Where:** `services/mw-simulators/src/main/java/mfs/mw/simulators/service/CallbackService.java:145-147`.
  - **Defect:** sslcommerz, ekpay, agg3 and agg4 fall into the OCS-ledger lookup in the `default` branch.
  - **Fix:** add a branch that looks the payment up in `AggregatorBillLedger`.

- **[Low · Standards (docs)] The remit port is documented wrongly**
  - **Where:** `services/mw-adapter-remit/CLAUDE.md:29`.
  - **Defect:** it says `MW_PORT_ADAPTER_REMIT` is 8100. Compose uses 8101, and 8100 is mw-adapter-atm.
  - **Fix:** change the doc to 8101.
