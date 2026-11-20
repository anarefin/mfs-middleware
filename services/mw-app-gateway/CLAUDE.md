# mw-app-gateway

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.app.gateway`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- App Middleware part 1 (spec 9.1, WP08): `POST /app/v1/transactions` (P2P, CASH_IN, CASH_OUT, MERCHANT_PAY, ADD_MONEY_BANK,
  TOPUP, BILL_PAY, B2B_TRANSFER and BULK_DISBURSE, `mw.app-gateway.supported-txn-types`; ticket 105 runs preview then confirm on that body),
  `POST /app/v1/{group}/preview` and `POST /app/v1/{group}/confirm` (groups send-money, cash-in, cash-out, payment,
  bill-pay, top-up, add-money, b2b, disburse; preview moves nothing and needs no PIN; a postpaid top-up preview
  returns the outstanding bill and the minimum due, and confirm sends those stored amounts, ticket 112b), `GET /app/v1/transactions` (wallet history, ADR D25),
  `GET /app/v1/transactions/{txnId}`, `POST /app/v1/balance` (ADR D26), `GET /app/v1/pin-key`,
  `GET /app/v1/billers`, `POST /app/v1/billers/{code}/fetch-bill` and `GET /app/v1/merchant/collections` behind the APISIX
  route `/app/v1/*` (`deploy/apisix/routes/app-v1.json`, `openid-connect` bearer-only against mw-auth's
  discovery document since ticket 69).
- SOAP facade (ticket 108, FRS CH-APP-01): `POST /app/ws/v1` with Spring Web Services (not Camel). The same
  preview, confirm, balance and transaction-status services as the REST controllers. The bearer is the HTTP
  `Authorization` header (`SecurityConfig` permits `GET /app/ws/v1/*.wsdl` and authenticates every other
  `/app/**`). `Idempotency-Key` is a SOAP header and shares the REST replay store. The XSD is
  `contracts/api/mw-app-gateway/app-soap.v1.xsd`; the WSDL is generated at `/app/ws/v1/app.wsdl`. The device
  gate is an MVC interceptor, so it does not apply to this servlet. Payloads are not logged. APISIX route
  `deploy/apisix/routes/app-ws-v1.json` (its own `limit-count` group `app-ws-v1`).
- Auth (`config/SecurityConfig`, `security/`; ticket 69, D21): the bearer is an mw-auth access token on
  `/app/**`, verified again here (`SecurityConfig.appTokenDecoder`): RS256 only, header `typ at+jwt`, a
  key of mw-auth's JWK Set (`mw.app-gateway.jwt.jwk-set-uri`, `http://mw-auth:8080/auth/v1/.well-known/jwks.json`,
  fetched on first use and again on an unknown kid), expiry, the exact issuer (`mw.app-gateway.jwt.issuer`
  = mw-auth's `mw.auth.tokens.issuer`, `mw-auth`) and audience `mw-app`. Not `JwtValidators.createDefault*`:
  since Spring Security 7 they accept `typ JWT` only. A Keycloak token is refused (another
  issuer); `security/RealmIssuerValidator` is gone. The payer wallet is the `msisdn` claim, the channel
  follows the `role` claim (from the wallet type at mw-auth): `customer` -> `CUSTOMER_APP`/`CUSTOMER`,
  `agent` -> `AGENT_APP`/`AGENT`, `merchant` -> `MERCHANT_APP`/`MERCHANT`, `distributor` ->
  `DISTRIBUTOR_APP`/`DISTRIBUTOR`, `dso` -> `DSO_APP`/`DSO`, `partner` -> `PARTNER_APP`/`PARTNER`
  (ticket 106); any other role is 403 `MW-AUTH-002`; a token whose `amr` lacks `pin` (a first-PIN
  OTP login) is 403 `MW-AUTH-045`; a token whose `deviceId` claim is not the request's `X-Device-Id` is 401
  `MW-AUTH-003` (device gate).
  Role per `txnType` (`mw.app-gateway.txn-type-roles`, checked at submit after the type check):
  P2P, CASH_OUT, MERCHANT_PAY, TOPUP, BILL_PAY and ADD_MONEY_BANK customer only, CASH_IN agent only,
  B2B_TRANSFER distributor, DSO and agent (the caller pays from their own wallet), BULK_DISBURSE partner only
  (the request posts that wallet as party type ORG, the core's ORGANISATION, to a CUSTOMER; the role stays PARTNER);
  the merchant initiates nothing (`AppGatewayConfigTest` checks no type lists MERCHANT) (switch here, nowhere else). Deny by
  default: a type with no entry is refused for every role; `AppGatewayConfigTest` fails when a
  txnType of the OpenAPI enum has no entry.
  No token / bad token `401 MW-AUTH-001`; no wallet, other role or a type the role may not
  initiate `403 MW-AUTH-002`, nothing published. Other paths
  (actuator) are open inside the app network. Keycloak is reached over `data` (MVP deviation of
  18.1), not `ops`.
- Device gate (ticket 29, spec 9.1/18.6; a verifier since ticket 68: device binding, the binding OTP, sessions
  and their nonces moved to mw-auth, `/auth/v1/*`; `config/DeviceBindingConfig`, `security/DeviceBindingInterceptor`
  + `@DeviceGate`, `service/DeviceVerifier`, `service/RequestSignatures`, `repository/DeviceGateStore`,
  `client/AuthClient`): after the JWT, every `/app/**` handler needs `X-Device-Id` of a device bound to the token's
  wallet (`401 MW-AUTH-003`): its wallet and public key come from mw-auth `GET /internal/v1/devices/{deviceId}`
  and are cached in Redis `devkey:{deviceId}` for `device-key-ttl` (5 min). `POST /app/v1/transactions`
  and `POST /app/v1/balance` (`@DeviceGate(SIGNED)`) also needs `X-Timestamp` (epoch ms, strictly less than `clock-skew` 5 min either way,
  `MW-AUTH-005`), `X-Signature` (ECDSA P-256 SHA-256, DER or P1363, over `METHOD\nPATH\nhex(sha256(body))\n
  TIMESTAMP\nNONCE\nDEVICE-ID`; `MW-AUTH-004`) and `X-Nonce` (once per device: Redis `nonce:{deviceId}:{nonce}` SET
  NX, TTL 2x skew + 30 s; the same key mw-auth uses, so a nonce is used once across both; `MW-AUTH-006`). The body
  hashed is the received body (`filter/CachedBodyFilter`, at most 64 KiB read with `readNBytes(limit+1)`, a larger
  one `413 MW-VAL-BODY_TOO_LARGE` as at the edge): exactly the bytes the schema check and the controller parse; no
  hash is taken from a header. That is why APISIX runs no request-validation on this route. Order: device, token device,
  session (with the key fingerprint, ticket 69), timestamp, signature, nonce.
- **Session check decision (ticket 68, kept in ticket 69):** every app request asks mw-auth `GET
  /internal/v1/sessions/{walletId}/{deviceId}` (which extends the sliding idle timeout; 404 -> `401 MW-AUTH-007`),
  not the token's `sid` claim: a logout, a lock or a new binding at mw-auth must end app access at once, which a 5
  min token cannot. A confirmation is cached in Redis `sesschk:{walletId}:{deviceId}` for `session-cache-ttl`
  (10 s; 0 = ask every time), so a burst asks once and an ended session passes here for at most 10 s.
- Stale device keys (security review of ticket 68, fixed in ticket 69; `service/DeviceVerifier`): the session
  answer carries the fingerprint (hex SHA-256 of the SPKI DER) of the device's current key; the confirmation
  cache keeps it. The session is checked before the signature; a cached `devkey` whose fingerprint differs is
  evicted and fetched again once (still different, or another wallet: `401 MW-AUTH-003`), and a session 404
  evicts the cached key too, so a rebinding or key replacement stops the old key within the 10 s confirmation
  TTL instead of the 5 min key cache (`service/DeviceVerifierTest`).
- mw-auth client (`client/AuthClient`, `client/ServiceTokenClient`, `config/AuthClientProperties`
  `mw.app-gateway.auth.*`; contract `api/mw-auth/auth-internal.v1.openapi.yaml`): `https://mw-auth:8443` over mTLS
  with this service's Vault certificate (`config/ServiceCertificateManager` + `TlsProperties` from the template,
  `mw.tls.enabled`: off in the image default and tests, `MW_TLS_ENABLED=true` in compose; PKI role
  `mw-app-gateway`) and a Keycloak realm `services` client-credentials JWT of client `mw-app-gateway` (audience
  mapper `aud-mw-auth`; secret `mw.app-gateway.auth.client-secret` in `secret/mw-app-gateway`, synced from
  `KC_MW_APP_GATEWAY_SECRET`; on mw-auth's `mw.internal-auth.allowed-clients`). Timeout 2 s, Resilience4j breaker
  `mw-auth` (count based, 4xx ignored). A 404 is an answer; mw-auth or Redis unreachable, a 5xx, a timeout, an
  open breaker or any other refusal is `503 MW-SYS-012` (fail closed; Redis and mw-auth are not in the health
  groups). Redis password `spring.data.redis.password` synced into `secret/mw-app-gateway`. Tests not about it
  run with `device-binding.enabled=false` (test `config/application.yml`); `security/DeviceBindingIT` runs the gate
  on real Redis with `AuthClient` stubbed, `client/AuthClientIT` the client against a WireMock mTLS server (ticket 72
  adds the authorisation: body, refusals passed through and never retried, the token refresh on MW-AUTH-001, fail
  closed; and the transport key on the public port). `SyncReplyIT` and `KafkaUnavailableIT` stub `AuthClient`
  (`@MockitoBean`) with a fixed assertion.
- Removed in ticket 68 (now in mw-auth): `POST /app/v1/devices/otp`, `POST /app/v1/devices`, `POST
  /app/v1/sessions`, the OTP publish to `mw.notify.request.v1`, tables `app_device` and `app_device_otp` (dropped by
  `V9__drop_device_tables.sql`; no data migration, a dev stack is reseeded). The outbox, its relay and the
  conformance suite stay as chassis although nothing appends to the outbox now.
- Validation: body schema first (`service/BodySchema` via `filter/BodySchemaInterceptor`, one per endpoint,
  `config/BodySchemaConfig`; after the JWT, before the device gate; ticket 29 and review A6, APISIX validates no
  body): `derived-schemas/app-v1-transaction-request.json` (`TransactionRequest`),
  `app-v1-balance-request.json` (`BalanceRequest`, ADR D26), `app-v1-fetch-bill-request.json` (`FetchBillRequest`) and
  `app-v1-push-token-request.json` (`PushTokenRequest`, ticket 110), derived from the contract by
  `scripts/derived-schemas.sh --write` (`./gradlew :contractsLint` fails on drift; a new JSON body endpoint needs its
  schema registered in `BodySchemaConfig`; the device bodies moved to mw-auth in ticket 68), validated
  with json-schema-validator (runtime dependency, on Boot's Jackson) on the cached bytes; duplicate keys,
  trailing content or a non-object `400 MW-VAL-001`, schema errors `400 MW-VAL-001` with dotted fields (no
  pattern/format in the schema, so no request value in a message). Then `Idempotency-Key` required, 1..64
  visible ASCII (`MW-VAL-001`, detail field `Idempotency-Key`); body by Bean Validation (`MW-VAL-001`, `amount.valueMinor` > 0, PIN
  ciphertext required: it goes to mw-auth); unsupported `txnType` `MW-VAL-010`; for P2P/CASH_IN/CASH_OUT/MERCHANT_PAY a
  counterparty equal to the caller's own wallet `MW-VAL-012` (the core enforces the real wallet
  types per txnType: `MW-CORE-INVALID_COUNTERPARTY`); `attributes` keys allow-listed
  (`mw.app-gateway.allowed-attributes`, only `reference`), so a client can never set reserved
  keys (`duplicate`, `requestTxnId`). MSISDNs accepted as `01XNNNNNNNN`, `8801...`, `+8801...`
  (`util/Msisdn`) and forwarded only in the canonical `01XNNNNNNNN` form.
- TOPUP (ticket 17): body `partner {partnerId: gp|robi|banglalink|teletalk, productCode:
  PREPAID|POSTPAID}` is required, `creditParty.type` absent or `MSISDN`; any other type takes no
  `partner` and only `WALLET` (`MW-VAL-001` naming `partner` / `creditParty.type`). Forwarded as
  `partner` and credit party `{type: MSISDN, id}`. An operator refusal arrives as FAILED with
  `MW-PTR-<OPERATOR>-*` and is answered 502.
- BILL_PAY (ticket 21): body `creditParty {type: BILLER_ACCOUNT (required), id: <bill number>}` and
  `partner {billerCode}` only (no partnerId/productCode: the middleware picks the aggregator);
  `MW-VAL-001` naming `partner.billerCode` / `creditParty.type` otherwise; `creditParty.id` is
  checked by its type (`dto/CounterPartyId`: bill number `[0-9A-Za-z-]{1,32}` for BILLER_ACCOUNT,
  an accepted MSISDN for every other type). The canonical partner is `{partnerId: <aggregator of
  the biller>, productCode: BILL, billerCode}` from the catalogue (`service/BillerService.partnerFor`;
  unknown biller `MW-VAL-001` on `partner.billerCode`, catalogue unavailable `503 MW-SYS-011`),
  credit party `{type: BILLER_ACCOUNT, id}`. A refusal is FAILED `MW-PTR-<AGGREGATOR>-*` -> 502.
- ADD_MONEY_BANK (ticket 23, C2W): body `debitParty {type: BANK_ACCOUNT, id: [0-9]{6,20}}`
  (required, masked last 4) and `partner {partnerId: c2wbank}` only (`TransactionRequest.BANKS`);
  `creditParty` is refused (the money goes to the caller's own wallet). `MW-VAL-001` naming
  `debitParty` / `partner.partnerId` / `creditParty` otherwise; every other type refuses
  `debitParty` and requires `creditParty` (no longer `required` in the OpenAPI schema, checked
  here). Forwarded as debit party `{type: BANK_ACCOUNT, id}`, credit party the token's wallet
  (its PIN authorises), partner `{partnerId, productCode: C2W}` (`RequestMapper.partnerOf`). A
  bank refusal is FAILED `MW-PTR-C2WBANK-*` -> 502; an outcome not known within the budget 202.
- Billers and bill fetch (ticket 21, `controller/BillerController`, `service/BillerService`,
  `client/BillPayClient`): read-only calls to mw-adapter-billpay's internal API
  (`contracts/api/mw-adapter-billpay/billpay.v1.openapi.yaml`, `mw.app-gateway.billpay.base-url`
  `http://mw-adapter-billpay:8080` over the `app` network, read timeout 28 s > the adapter's 25 s
  partner limit). `GET /app/v1/billers` -> `{billers: [{code, name, category}]}` (the aggregator
  is not shown); the catalogue is reused for `catalogue-ttl` (60 s) and the last copy kept when the
  adapter cannot be asked. `POST /app/v1/billers/{code}/fetch-bill {billNumber}` -> `{billerCode,
  billNumber, amount, dueDate, billPeriod}`; the adapter's envelope passes through with its code and
  status (404 `MW-VAL-003`, 502 `MW-PTR-*`, 503 `MW-SYS-*`, 504 `MW-TMO-*`); adapter unreachable or
  unreadable `503 MW-SYS-011` (retryable). Resilience4j instance `billpay`: retry (2 retries, these
  are idempotent reads) and circuit breaker on `BillPayUnavailableException` only; the envelope
  (`BillPayRefusedException`) is a normal answer. Any authenticated app user with a wallet may call
  them. Why HTTP and not Kafka: see "Design decision: synchronous bill fetch" in
  mw-adapter-billpay's CLAUDE.md (no money moves; no Kafka thread waits; contract, not code,
  dependency; Kafka request/reply would misuse orchestrator-owned partner topics).
- Submit (`service/TransactionService`): mints a UUIDv7 txnId (`util/UuidV7`), maps to the
  canonical `mw.txn.request.v1` (`mapper/RequestMapper`; initiator MSISDN = token wallet; debit = token
  wallet (its own PIN), credit = body wallet (walletType AGENT for CASH_OUT, MERCHANT for MERCHANT_PAY, else CUSTOMER)), then
  asks mw-auth for the transaction assertion (below), registers a waiter, then publishes directly (not via the outbox) with header `mw-reply-to = instanceId`. Kafka refusing
  or timing out (`max.block.ms`/`delivery.timeout.ms` 5 s/10 s) -> `503 MW-SYS-010`, retryable,
  nothing recorded. Idempotency is decided by mw-orchestrator (7.3); this service only requires
  and forwards the key.
- Transaction assertion (ticket 72, spec 18.7, D22; `AuthClient.authorise`, `RequestMapper.toAuthorisation`): for
  every submitted type (all of them need the payer's PIN) `TransactionService` sends `POST /internal/v1/txn-authorisations
  {walletId (the token's MSISDN), channel, deviceId (the token's `deviceId` claim, `AppUser.deviceId`), encryptedPin,
  txn {txnType, amount (valueMinor), currency, payer = debitParty {type, id}, payee = creditParty {type, id},
  idempotencyKey}}`, built from the very `mw.txn.request.v1` it then publishes (mw-auth hashes these values into the
  assertion's `txnHash` and the core recomputes it from the request: `TransactionServiceTest` checks they are
  identical), and publishes the request with `assertion` and no `encryptedPin` (the PIN ciphertext goes to mw-auth
  only, never to Kafka, never logged). mw-auth's refusal passes through at once with its code, status, messages and
  details, nothing published or recorded: `401 MW-AUTH-040` (wrong PIN, `triesLeft`), `423 MW-AUTH-041` (`retryAfter`)
  / `042`, `403 MW-AUTH-043` / `045` / `002`, `401 MW-AUTH-047`, `409 MW-IDEM-001`; mw-auth unreachable `503
  MW-SYS-012` (retryable). A repeat with the same key inside the assertion's 60 s gets the same assertion from mw-auth
  without a second PIN check; the orchestrator still decides idempotency. A 401 is retried with a new service token
  only when its code is `MW-AUTH-001` (or it has no envelope), so a wrong PIN is checked once.
- Balance check (ADR D26, app.v1 1.6.0; `controller/BalanceController`, `service/BalanceService`,
  `RequestMapper.toBalanceRequest`, `dto/BalanceRequest` / `dto/BalanceView`): `POST /app/v1/balance {encryptedPin,
  locale?}` with `Idempotency-Key` (as a submit), signed (`@DeviceGate(SIGNED)`), any app role (customer, agent,
  merchant; not in `txn-type-roles`, and BALANCE is not a txnType of `POST /app/v1/transactions`:
  `AppGatewayConfigTest`). The wallet is the token's: mw-auth's assertion for `{txnType BALANCE, amount 0, BDT, payer =
  payee = {WALLET, msisdn}, idempotencyKey}`, then `mw.txn.request.v1` with txnType BALANCE, amount `{0, BDT}`, debit
  = credit = the caller's wallet (walletType from the role), the assertion; the same waiter and `mw-reply-to` as the
  submit (`service/TxnReplyExchange`, shared with `TransactionService`: publish, 503 `MW-SYS-010`, the budgeted wait,
  FAILED/REJECTED as the envelope). COMPLETED with `balance` -> 200 `{txnId, available, asOf}`; mw-auth refusals as for
  a submit; `MW-CORE-*` 422; no terminal answer in the budget (or a duplicate still running, or COMPLETED without
  `balance`) 504 `MW-TMO-001` retryable, never 202. Never stored: no `app_transaction` row at submit, and
  `StatusProjectionService` skips every BALANCE record, so `GET /app/v1/transactions/{txnId}` never serves one; the
  wallet history leaves it out at mw-orchestrator (ADR D25 query API). The balance is never logged
  (`StatusMessage.balance`, `BalanceView`). Tests: `BalanceControllerTest`, `BalanceServiceTest`,
  `StatusProjectionServiceTest`, `service/BalanceIT` (Kafka, `OrchestratorStub` answers BALANCE by key prefix).
- PIN transport key (ticket 72; `controller/PinKeyController`, `service/PinKeyService`): `GET /app/v1/pin-key` (token
  only, `@DeviceGate(NONE)`) answers `{keyId, algorithm RSA-OAEP-SHA-256, publicKeyPem}`, the newest mw-crypto
  `PIN_TRANSPORT` version read from mw-auth `GET /internal/v1/pin-key` over the same mTLS client and service token as
  the assertion (security review of ticket 72: never from the plain public port; `dto/ChannelPinKey`, a key whose
  `purpose` is not `PIN_TRANSPORT` is refused, `503 MW-SYS-012`), reused `pin-key-ttl` (5 min); while mw-auth cannot be asked the last key is
  served; none yet `503 MW-SYS-012`. The app encrypts the PIN's UTF-8 bytes with RSA-OAEP (SHA-256, MGF1 SHA-256).
- Sync wait (7.5): `service/ReplyWaiters` (`txnId -> future`); `config/StatusReplyConsumerConfig` consumes
  `mw.txn.status.v1` with group `mw-app-gateway.reply.<instanceId>` (latest offset, auto-commit,
  no retry), keeps records whose `mw-reply-to` is this instance and only completes futures, so
  no Kafka thread ever waits. The HTTP (virtual) thread waits at most `reply-budget` (25 s).
  A record completes a waiter only when terminal (COMPLETED/FAILED/REJECTED/REVERSED) or a
  duplicate answer (`attributes.duplicate=true`, matched on `attributes.requestTxnId`), AND its
  `idempotencyKey` and `initiator.id` equal the waiting request's. Answers: COMPLETED/REVERSED
  200 with the view; FAILED/REJECTED the error envelope with the status's code (`MW-CORE-*` 422,
  `MW-IDEM-001` 409, `MW-VAL-*` 400, `MW-TMO-*` 504) and txnId; duplicate still running or no
  answer in time `202 {txnId, status: PROCESSING}` + `Location`. A duplicate answer carries the
  STORED txnId.
- Stored response (ticket 99, FRS PLT-011; `service/IdempotentReplay`, `repository/IdempotentResponseStore`,
  `config/IdempotentReplayProperties` `mw.app-gateway.idempotent-replay.{enabled, ttl 72h}`): the first 200
  answer under a key is kept in Redis `idemresp:<sha256(channel, wallet, key)>` (`SET NX EX`, value request
  hash + the view's JSON); a repeat with the same content (type, parties, amount, partner, attributes; not
  the PIN ciphertext, locale or `confirmDuplicate`) gets that body again byte for byte before mw-auth or
  the orchestrator is asked, even after a reversal; other content `409 MW-IDEM-001`. 202 / FAILED /
  REJECTED answers are not stored (the orchestrator answers them). Redis trouble: logged
  `txn.idempotent.store_unavailable`, the orchestrator answers. Off in the test config;
  `service/IdempotentReplayIT` (Kafka + Redis). The body field `confirmDuplicate` (app.v1 1.7.0) is the user's
  yes to `400 MW-VAL-024` (orchestrator business duplicate) and is forwarded on `mw.txn.request.v1`.
- Status view (`GET`): table `app_transaction` (own schema `app_gateway`), the latest status of
  every app-channel transaction, kept by `messaging/StatusProjectionListener` (shared group
  `mw-app-gateway`, retry topics + DLQ) and seeded with RECEIVED at submit. A duplicate answer is
  not projected as a status; it links the txnId minted for the repeat (`attributes.requestTxnId`)
  to the stored txnId (`app_transaction.stored_txn_id`, V5), so when the answer comes after the
  reply budget the 202's txnId still resolves: GET on it returns the stored transaction's view
  (its txnId, up to its final status), 404 for anyone else (S3). Only the initiator's wallet can read a row; anything else is 404 `MW-VAL-003`.
- MERCHANT_PAY and collections (ticket 30, WP20): the customer pays the merchant wallet named in
  `creditParty` (no partner, no fee in mw-core-mock, which checks CUSTOMER -> MERCHANT:
  `MW-CORE-INVALID_COUNTERPARTY`). `GET /app/v1/merchant/collections?limit=1..100` (default 50,
  `MW-VAL-001` otherwise; `controller/MerchantController`, `service/CollectionService`,
  `mapper/CollectionMapper`): merchant role only (`403 MW-AUTH-002`), wallet from the token, so a
  merchant only ever sees payments to its own wallet. Rows: `app_transaction.payee_id` (V7, the
  merchant wallet of a MERCHANT_PAY, set by the projection and at submit,
  `TransactionView.merchantPayee`), status COMPLETED or REVERSED only, newest first (UUIDv7 txnId
  order). Each entry `{txnId, status, amount, payer, coreRef, requestedAt, updatedAt}` with the payer's
  MSISDN masked (`0171****789`); no fee, no PIN.
- Wallet history (ADR D25, app.v1 1.5.0): `GET /app/v1/transactions?limit=1..100&offset=0..10000` (default
  20 / 0; optional `txnType`, `direction` IN|OUT, `[from, to)`; out of range `MW-VAL-001`) lists the token's
  own wallet's transactions, sent and received, from every channel, newest first:
  `TransactionHistory {items, limit, offset, hasMore}`, each `HistoryItem {txnId, txnType, direction, status,
  amount, fee?, counterparty {type, id, name?, walletType?}, coreRef?, error?, requestedAt, updatedAt}`
  (`controller/TransactionController`, `service/TransactionHistoryService`, `mapper/HistoryMapper`). Any app
  role, session level (no signature); the wallet is always `user.msisdn()`, never a parameter. Read from
  mw-orchestrator's internal query API (`client/OrchestratorQueryClient`, copied from mw-portal-api's,
  `config/OrchestratorClientProperties` `mw.app-gateway.orchestrator.*`, `MW_ORCHESTRATOR_BASE_URL`, plain
  HTTP on the app network) with the same `client/ServiceTokenClient` token as mw-auth's (client
  `mw-app-gateway`, audiences mw-auth, mw-orchestrator and mw-notify): always `walletId`, asks `limit + 1` to tell
  `hasMore`, a 401 drops the token and asks once more. The orchestrator decides what is shown (IN items only
  COMPLETED / REVERSING / REVERSED) and masks the parties; the mapper keeps the other side as `counterparty`
  (credit party of an OUT item, debit party of an IN one) and leaves out the fee and the error of an IN
  item; `requestedAt` is the orchestrator's `createdAt`. Unavailable (connect, timeout, no token, any other
  status, unreadable answer, open breaker) -> `503 MW-SYS-013`, retryable (WARN
  `orchestrator.query.unavailable`); Resilience4j `orchestrator` (retry 2, breaker counting calls). Items are
  self-contained: `GET /app/v1/transactions/{txnId}` still serves only the caller's own app submissions.
  Tests: `TransactionHistoryServiceTest`, `HistoryMapperTest`, `TransactionControllerTest`,
  `client/OrchestratorQueryClientIT` (WireMock, no Docker), `ApiContractsTest`.
- Push token (ticket 110, app.v1 1.8.0, FRS EXT-04; `controller/PushTokenController`, `service/PushTokenService`,
  `dto/PushTokenRequest`, `dto/PushTokenMessage`): `PUT /app/v1/notifications/push-token {token (1..4096),
  platform ANDROID|IOS}`, session level (bound device, live session, no signature), any app role; body schema
  `derived-schemas/app-v1-push-token-request.json` (the body cache and schema check now take PUT too). Publishes
  `mw.notify.push-token.v1 {walletId = msisdn claim, deviceId = X-Device-Id, token, platform, registeredAt,
  correlationId}` (key the wallet; contract owned by mw-notify, its consumer) straight to Kafka (nothing stored
  here); 204; Kafka refusing `503 MW-SYS-010`. The token is never logged. APISIX route `app-v1` takes PUT since.
  Tests: `PushTokenControllerTest`, `contracts/ProducedContractsTest` (fixture through the real service).
- Saved billers, favourites and the inbox (ticket 107a, app.v1 1.10.0, CH-APP-08; `controller/NonFinancialController`,
  `service/PreferenceService`, `repository/SavedBillerRepository`, `repository/FavouriteRepository`, schema
  `V10__preferences.sql`): `GET|POST|DELETE /app/v1/saved-billers` and `/app/v1/favourites` are the token
  wallet's own rows, stored in the clear (no mw-crypto). The owner sees the account number and the
  counterparty MSISDN; `@Sensitive` masks them in logs. A duplicate is 409 `MW-IDEM-001`; another wallet's
  id is 404 `MW-VAL-003`. `GET /app/v1/notifications` and `POST /app/v1/notifications/{id}/read` read
  mw-notify's customer inbox (`client/NotifyInboxClient`, `config/NotifyClientProperties`
  `mw.app-gateway.notify.*`, `MW_NOTIFY_BASE_URL`, the same service token, audience mw-notify) for the token
  MSISDN only. Unavailable is 503 `MW-SYS-014`, retryable. Session level, no signature. Body schemas
  `derived-schemas/app-v1-save-biller-request.json` and `app-v1-save-favourite-request.json`. The edge route
  `app-v1` allows DELETE. Tests: `SavedBillerRepositoryIT`, `FavouriteRepositoryIT`,
  `NonFinancialControllerTest`, `contracts/ApiContractsTest`.
- Profile, limits and the statement (ticket 107b, app.v1 1.11.0, CH-APP-08; `controller/AccountReadController`,
  `service/AccountReadService`, `client/CoreWalletClient`, `config/CoreClientProperties`
  `mw.app-gateway.core.*`, `MW_CORE_BASE_URL`): the gateway calls mw-core-mock itself. `GET /app/v1/profile`
  is WAL-01 (the owner sees the holder name; `@Sensitive` keeps the initial in logs) plus language `bn`.
  `GET /app/v1/limits` is WAL-05 for a P2P of 100 poisha. `GET /app/v1/statement?from&to` is WAL-12, JSON or
  CSV (`Accept: text/csv`), and a range longer than 90 days is 400 `MW-VAL-001`. The wallet is the token's
  MSISDN. The core out of reach is 503 `MW-SYS-015`, retryable; a core 404 is `MW-VAL-003` and a core 422
  `MW-CORE-*` is passed through. Session level, no signature. Caller `mw-app-gateway` is on the core's
  `allowed-clients` (and the dev compose list) and the Keycloak client has audience `mw-core-mock`. Tests:
  `AccountReadControllerTest`, `client/CoreWalletClientTest`, `contracts/ApiContractsTest`.
- Dev provisioning (`provision_mw_app_gateway` in `deploy/provision/services.sh`, run by the one-shot `provision` on every `docker compose up`, idempotent, dev only): role
  `app_gateway_rw`, schema `app_gateway`, `secret/mw-app-gateway` (DB password; `spring.data.redis.password`
  synced from `REDIS_PASSWORD`; `mw.app-gateway.auth.client-secret` from `KC_MW_APP_GATEWAY_SECRET`;
  `mw.app-gateway.preview.signing-key` created once, ticket 105), AppRole, PKI
  role `mw-app-gateway`. Keycloak realm `services` client `mw-app-gateway` (audiences `mw-auth`, ADR D25
  `mw-orchestrator`, `mw-notify` for the inbox, and `mw-core-mock` for profile, limits and the statement): a running stack
  needs `deploy/reset.sh` to import it. Compose waits for a healthy mw-auth.

## Contracts owned

- `api/mw-app-gateway/app.v1.openapi.yaml` (app API v1). This service checks every JSON request body against
  its schema in `src/main/resources/derived-schemas/` (`TransactionRequest`, `BalanceRequest`, `FetchBillRequest`; `scripts/derived-schemas.sh --write` after changing one;
  `./gradlew :contractsLint` fails until then). The edge answers 401/413/429 itself (`deploy/apisix/README.md`).
- `api/mw-app-gateway/app-soap.v1.xsd` (ticket 108): the SOAP facade. The WSDL is generated from it
  (`config/WebServiceConfig`); `contracts/ContractDriftTest` checks the operations. Not an OpenAPI file, so it
  is owned in `contracts/OWNERS.yml` and copied into the jar, and it is not a `contracts.yml` `apis` entry.

## Contracts used

- `contracts.yml` (module root) is the machine-readable list of the events and APIs below, checked by
  `./gradlew :contractsLint` and tested without Docker by `contracts/ProducedContractsTest` (every produced
  event built by the real mapper validates), `contracts/ConsumedContractsTest` (every valid sample reads into
  the own DTO) and `contracts/ApiContractsTest` (app API responses, the billpay client's requests and mw-auth's answers).
- `api/mw-adapter-billpay/billpay.v1.openapi.yaml` (client; own types `dto/Biller`, `dto/Bill`).
- `api/mw-notify/notify-internal.v1.openapi.yaml` (client, ticket 107a: `GET /internal/v1/inbox` and
  `POST /internal/v1/inbox/{id}/read` for the token's MSISDN; own type `dto/InboxMessage`).
- `api/mw-core-mock/core-wallet.v1.openapi.yaml` (client, ticket 107b: WAL-01, WAL-05, WAL-12 for the
  token's MSISDN; own types `dto/CoreAccount`, `dto/CoreLimits`, `dto/CoreStatement`).
- `api/mw-orchestrator/txn-query.v1.openapi.yaml` (client, 1.3.0, ADR D25: `GET /internal/v1/transactions` with
  `walletId`, as the wallet-scoped caller; own types `dto/QueryTxnPage`, `dto/QueryTxn`; a contract-shaped page
  is validated and read in `ApiContractsTest`).
- `events/mw-notify/mw.notify.push-token.v1` (produced, ticket 110; own type `dto/PushTokenMessage`).
- `events/mw-orchestrator/mw.txn.request.v1` (produced; own type `dto/TxnRequest`, validated
  against the schema in `RequestMapperTest` and `SyncReplyIT`).
- `api/mw-auth/auth-internal.v1.openapi.yaml` (client, ticket 68: `GET /internal/v1/devices/{deviceId}`, `GET
  /internal/v1/sessions/{walletId}/{deviceId}`; own types `dto/BoundDevice`, `dto/LiveSession` (key fingerprint,
  ticket 69); ticket 72: `POST /internal/v1/txn-authorisations`, own types `dto/TxnAuthorisationRequest`,
  `dto/TxnAuthorisation`; checked in `ApiContractsTest`) and `api/mw-auth/auth.v1.openapi.yaml` (`GET /auth/v1/pin-key`,
  `dto/PinKey`, served unchanged as the app API's `PinKey`). The app token itself is mw-auth's (`api/mw-auth/auth.v1.openapi.yaml`
  describes its claims; tested by `security/AppTokenDecoderTest`, `security/AppUserResolverTest`).
- `events/mw-orchestrator/mw.txn.status.v1` (consumed; own type `dto/StatusMessage`, unknown
  fields ignored) and the orchestrator's `mw-reply-to` / duplicate-answer conventions.

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- Security pass (ticket 46, `docs/security-findings.md`): `build.gradle.kts` raises the transitive
  Jackson 2 (BOM), lz4-java and snappy-java to patch releases with the HIGH CVE fixes (written inline in
  `build.gradle.kts`; drop once the Boot BOM ships them); the shared dev provisioning image `deploy/provision/Dockerfile` runs `apk upgrade` and removes gosu. `scripts/security.sh <this module>` scans the SBOM and the
  `mfs-mw/*:local` images (HIGH/CRITICAL fail; accepted ones in `.trivyignore.yaml` with a reason and expiry).
- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field. Party ids are
  `PARTY_ID` (ticket 23): masked by the party's `type` (wallet/MSISDN as an MSISDN, a bank account or any
  other type last 4 only).
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
  Add context with `log.atInfo().addKeyValue("event", "...")` or the MDC.
- `config/MwServiceProperties`: config binding; secrets from Vault when `VAULT_ENABLED=true`.
- `messaging/EventPublisher`: Kafka records with the 6.1 headers (+ extra headers such as `mw-reply-to`)
  through Spring Kafka's `KafkaTemplate` (ADR D27), idempotent `acks=all` producer; `publish` waits at most
  `mw.kafka.send-timeout`. The admin client (asked for the cluster id by the observed template's first
  send) is bounded like the producer (`spring.kafka.admin.operation-timeout`), so Kafka down still fails fast.
- Spring Kafka (ADR D27, copied from the template, ticket 80): `config/KafkaConfig` (non-blocking retry topics
  `<topic>.mw-app-gateway.retry.1..3` then `<topic>.mw-app-gateway.dlq` for every `@KafkaListener` on the default factory,
  `messaging/RetryTopicNaming`, `RetryTopicRecoverer` (`mw-retry-*` headers, class only), `RetryDueTimeCap`,
  `ConsumerContextInterceptor` (consumer MDC), `Utf8Deserializer`; validation/schema failures to the DLQ at
  once); retry topics 10 s / 1 min / 10 min (`mw.kafka.retry.delays`); per-record manual ack; no sleeps on
  Kafka threads. Consumers: `messaging/StatusProjectionListener` (retried, group `mw-app-gateway`) and
  `config/StatusReplyConsumerConfig`: the per-instance reply consumer of `mw.txn.status.v1`, a container built from its own
  `statusReplyContainerFactory` (not a `@KafkaListener`, so the retry configuration never applies to it), group
  `mw-app-gateway.reply.<instanceId>`, `auto-offset-reset=latest`, auto-commit, no retry topic or DLQ, an error handler
  that logs the exception class and skips; `messaging/StatusReplyListener.onReply` only completes futures.
  `messaging/StatusReplyConsumerIT` runs two instances: each gets its replies, none sent before it started.
- `scheduler/OutboxRelayScheduler`: the outbox relay schedule (`mw.outbox.relay.period`, ShedLock-guarded).
- `application.yml`: Resilience4j defaults (19.2), own schema `app_gateway` migrated by Flyway on
  start from `db/migration` (16.4); spec 16.2 names no schema for this service.

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-app-gateway:test` | every test in one JVM: unit, slice and contract tests (`contracts/ProducedContractsTest`, `ConsumedContractsTest`, `ApiContractsTest`), the Testcontainers `*IT` classes (PostgreSQL, Kafka, Redis) and the `@Conformance` suite (outbox, inbox, HTTP, purge); one PostgreSQL and one Kafka per test JVM, a fresh database per Spring context; the `*IT` and conformance classes are skipped without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-app-gateway:build` | `test`, then the boot jar `build/libs/mw-app-gateway.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-app-gateway:bootJar && docker build -t mw-app-gateway:dev services/mw-app-gateway` | image, as spec 4.3 rule 1 |
| `./gradlew clean build` | the full gate: every module, plus the repo checks (`:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`); unchanged modules come from the build cache |
| `./gradlew :mw-app-gateway:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-app-gateway` | rebuild this service's image (`mfs-mw/mw-app-gateway:local`) and restart it with what it depends on (dev timers from `deploy/.env`) |
