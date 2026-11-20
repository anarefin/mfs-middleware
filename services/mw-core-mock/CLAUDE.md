# mw-core-mock

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.core.mock`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Wallet holder names (`V10__wallet_holder_name.sql`, `wallet.holder_name`, optional): `WalletSeeder` gives its
  generated ranges fixed realistic Bangladeshi names (`util/HolderNames`, by type and index: customers
  people, agents shops, merchants businesses, orgs companies; fixtures 01711000001 Rahim Uddin,
  01711000002 Ayesha Siddiqua, 01711000003 Kamal Hossain, 01811000001 Hossain Telecom, 01911000001 Karim
  Traders, 01911000002 Lalbagh Pharmacy, 01311000001 Meghna Garments Ltd, 01311000002 Padma Foods Ltd),
  filling only rows whose name is still null on every start. The wallet GET serves `holderName` (null for
  system wallets); the seed PUT takes it and stores it on create or on an existing wallet that has none.
  `@Sensitive(NAME)`: logs show the first initial only.
- Test-only balance setting (the test console's "Set balance"): the seed PUT's optional `balanceMinor` sets
  the wallet's AVAILABLE balance (new or existing; balance = it + held). Never a silent edit: the difference
  is a double entry of kind `ADJUSTMENT` (`V11__balance_adjustment.sql`) between the wallet and `SETTLEMENT`,
  txn/command id `adjust:<uuid>`; not a TRANSFER, so no daily-limit effect; no entry when already there.
  `openingBalanceMinor` is unchanged (a new wallet's start balance; the seeder sends only that).

- Test double of the core wallet (spec "Core mock", 6.3, WP06): never ships to production, replaced by
  the real core with no change to the orchestrator.
- **Core wallet REST API** (FRS 7.2 WAL-01, WAL-03..13, ADR D31, ticket 93; contract
  `api/mw-core-mock/core-wallet.v1.openapi.yaml`, base `/core/v1`), the only way in since ticket 95b retired the
  Kafka commands: `controller/CoreWalletController` (mapping and `@Valid` only) -> `service/CoreWalletService`.
  Money operations are the internal commands of the same name (`dto/CoreCommand`, once the Kafka contract), built
  there and handed to `CoreCommandService.handle(cmd, topic)`: holds `HoldFunds`, `.../{holdId}/commit` `CommitHold`,
  `.../release` `ReleaseHold` (answers on `core.payment.event.v1`), transfers `PostTransfer`, credits
  `CreditWallet`, reversals `ReverseTransaction` (`amount` optional = the whole original; partial is
  `AMOUNT_MISMATCH` until ticket 116) on `core.wallet.event.v1`. So the rules, the faults, the `command_log`
  replay and the `core.event.v1` outbox results are the commands' (test `controller/CoreWalletEventsIT`).
  Idempotent by txnId and operation (FRS 7.3): commandId `<txnId>:rest:<op>` (commit and release: the
  hold's txnId), `rest_request` (`V12__rest_request.sql`) keeps the SHA-256 of the first body; same body =
  the stored answer and no second posting, another body `409 MW-IDEM-001`. A core refusal is `422
  MW-CORE-<reason>` (stored and committed like a success, so a repeat is the same 422), a refused
  assertion `401 MW-AUTH-046`; an unknown hold `422 MW-CORE-HOLD_NOT_FOUND` (no command, no event).
  Holds and transfers need mw-auth's assertion in `X-Txn-Assertion` plus the body's `idempotencyKey`
  (`security/TxnAssertionHeader`: missing `401 MW-AUTH-046` before anything runs; BULK_DISBURSE needs
  none), then `AssertionCheck` as on the command; credits need none. Reads write nothing: accounts
  (type, status, `kycLevel`), balance (available/held/total), charges (the tariff sheet, below: fee, VAT,
  commission and its split; `customerType` default CUSTOMER), limits (the limit sheet: `allowed` +
  `reasonCode`, `remaining` perTxn/daily/monthly/dailyCount/monthlyCount), statements (entries by wallet
  and `[from, to)`, `limit` <= 500, `page` from 0 + `hasMore`, each entry's `balanceAfter`), transactions (`StatusOperations.current`, the QueryTransaction view,
  plus every entry). WAL-02 `/pin/verify` is not built (D20, D22, D31). Auth: `/core/v1/**` needs a
  Keycloak realm `services` client-credentials JWT (`config/SecurityConfig`, `config/InternalAuthProperties`
  `mw.internal-auth.*`: exact issuer, `aud` mw-core-mock, `azp` in `allowed-clients` mw-orchestrator,
  mw-auth, mw-admin-api; no or bad token `401 MW-AUTH-001`, other client `403 MW-AUTH-002`); every other
  path (`/core-mock/v1/**` test/admin API, the dev-only APISIX route `/test/core-mock`, actuator) stays
  open as before. The Keycloak clients and their `aud-mw-core-mock` mappers come with the callers (ticket
  95). Tests: `controller/CoreWalletControllerTest` (slice, `security/InternalTokens`),
  `controller/CoreWalletRestIT` (money, idempotency, every body against the contract).
- Additions for the orchestrator's REST client (ticket 95a): `POST /core/v1/transactions/validate`
  (`CoreWalletService.validate`, the Kafka ValidateTransaction's rules: payer/payee, limits, fee, the assertion
  checked and its jti claimed for the txn; nothing moves; idempotent under `<txnId>:rest:validate`; answer
  `ValidationResult` {txnId, status VALIDATED, coreRef, fee, vat, limitsLeft}). `GET /core/v1/balance/{walletId}`
  with `X-Txn-Assertion` + `?txnId=` + `Idempotency-Key` (a customer's own read, the BALANCE check): the
  BalanceInquiry command under `<txnId>:rest:balance` (assertion, jti, ACTIVE wallet, faults); a repeat is answered
  entirely from the stored BalanceReported event (available, held, total, asOf as at the first answer; the event
  payload now carries `held` and `total`), and the assertion's SHA-256 is part of the request hash, so another
  assertion under the same txnId and key is `409 MW-IDEM-001` (security review of 95a). Without the assertion a
  balance read is allowed for ops callers (`azp` mw-admin-api, mw-auth) and SYSTEM wallets only, else `401
  MW-AUTH-046`; the answer carries `asOf`. Operator rounds: commit / release `HoldActionRequest.round` and credit
  `CreditRequest.round` n >= 1 are their own operation (`<txnId>:rest:<op>:manual-<n>`), so an operator's forced
  decision is decided afresh instead of replaying the refusal stored for the saga's own call. Tests in
  `CoreWalletRestIT` / `CoreWalletControllerTest`.
- Additions for mw-admin-api and mw-auth (ticket 95b, ADR D31), each the internal command of the same name
  under `<txnId>:rest:<op>` (rules, faults, replay, result event as below): `POST
  /core/v1/accounts/{walletId}/unlock {txnId, changeRequestId, makerId?, checkerId?}` (`UnlockWallet`, op
  `unlock`) -> `AccountActionResult {txnId, walletId, status UNLOCKED, coreRef}`, refusals `422
  MW-CORE-WALLET_NOT_LOCKED` / `WALLET_NOT_FOUND`; `POST /core/v1/customers/{walletId}/verify-factors {txnId,
  resetId, factors {nidLast4Token, dateOfBirthToken, tokenKid}}` (`VerifyCustomerFactors`, op `factors`; the
  factors `@Sensitive(SECRET)`) -> `{..., status VERIFIED, resetId}`, refusals `422 MW-CORE-WALLET_NOT_FOUND` /
  `WALLET_NOT_ACTIVE` / an injected `FACTORS_MISMATCH`. Per-operation callers in `config/SecurityConfig`: the
  unlock only `azp` mw-admin-api, the factors check only mw-auth (else `403 MW-AUTH-002`). Tests in
  `CoreWalletRestIT`.
- **The Kafka command side is gone (ticket 95b):** `messaging/CoreCommandListener`, the topics
  `core.wallet.command.v1` / `core.payment.command.v1` (`deploy/kafka/topics.txt`) and the contract
  `core.command.v1` (files and samples) are retired; this module consumes no Kafka topic. The internal
  commands it still runs: `ValidateTransaction` (fee, limits, assertion, account status; moves no money),
  `PostTransfer` (double-entry debit/credit plus fee), `HoldFunds`, `CommitHold`, `ReleaseHold`,
  `CreditWallet`, `ReverseTransaction`, `QueryTransaction`, `UnlockWallet` (ticket 52), `VerifyCustomerFactors`
  (ticket 70), `BalanceInquiry` (ADR D26); the service tests build them directly
  (`service/CoreCommandsTestSupport`). `ApplyComplianceAction` and unknown types are logged and ignored.
- `BalanceInquiry` (ADR D26, txnType BALANCE, commandId `<txnId>:balance`; `CoreCommandMapper.balance`: a
  `TransferPayload` with amount 0 and debit = credit wallet, assertion required, else malformed): unknown
  wallet `BalanceInquiryRejected WALLET_NOT_FOUND`, not ACTIVE (LOCKED, BLOCKED, HOLD) `WALLET_NOT_ACTIVE`,
  then the assertion (`AssertionCheck`, subject the wallet; refused `MW-AUTH-046`; its jti is claimed like a
  transfer's), else `BalanceReported {walletId, available (balance - held), asOf (clock now)}`
  (`CoreEventMapper.balanceReported`). No ledger entry, no daily-limit use; the amount is never logged.
  Idempotent on commandId (a replay answers the stored balance), REJECT faults apply. Test:
  `service/BalanceInquiryIT`.
- `VerifyCustomerFactors` (ticket 70, mw-auth's self-service PIN reset; `dto/FactorsPayload`,
  `CoreCommandMapper.factors`): the KYC factors arrive as TOKENISE_NID hashes the mock cannot recompute
  (no KYC data, no mw-crypto key), so it checks the wallet only: unknown `CustomerFactorsRejected
  WALLET_NOT_FOUND`, BLOCKED / HOLD `WALLET_NOT_ACTIVE` (a core LOCKED wallet may still reset), else
  `CustomerFactorsVerified {walletId, resetId}`; a mismatch is played with a REJECT fault (`reasonCode`
  e.g. `FACTORS_MISMATCH`; `CoreEventMapper.factorsRejected` carries the resetId). The real core compares
  the hashes with its KYC record (open point). Events on `core.wallet.event.v1` (since ticket 95b commandId
  `<txnId>:rest:factors`); mw-orchestrator drops them (`txn.unknown`), mw-auth skips them (it has the REST
  answer).
- **No PIN in the core (ticket 72, D20, D22; M11 removed).** The core holds, decrypts and checks no PIN
  and keeps no PIN lockout: mw-auth checks the PIN and locks (ticket 70) and the core only verifies its
  transaction assertion (below). Gone: `security/PinCipher`, `PinKeyProvider`, `controller/PinKeyController`
  (`GET /core-mock/v1/pin-public-key`), `dto/EncryptedPin`, bcrypt (`spring-security-crypto`), `mw.coremock.pin.*`,
  `pin-max-failures`, `seed.pin`, the reasons `INVALID_PIN` / `INVALID_PIN_BLOCK`; `V9__drop_core_pin.sql` drops
  `wallet.pin_hash` and `wallet.pin_failures`. `WalletStateChanged.hasPin` is always false (mw-auth decides a
  first PIN from `auth.credential`). **UnlockWallet decision:** status `LOCKED`, `WalletRepository.unlock` and
  `UnlockWallet` STAY, for core-side account locks (a real core locks an account for its own reasons:
  compliance, fraud, dormancy). The mock no longer sets LOCKED itself (no API either; tests set it in SQL), so
  ops unlock (mw-admin-api) keeps sending both mw-auth `UNLOCK` on `auth.wallet-lock.v1` (the PIN lock) and the
  core unlock (a core lock, REST since ticket 95b; `WALLET_NOT_LOCKED` when there is none, which is harmless).
- **Event topic decision (M2 gate, ticket 50):** the result of `HoldFunds` / `CommitHold` / `ReleaseHold` goes
  to `core.payment.event.v1`, every other result to `core.wallet.event.v1` (`CoreWalletService` names the
  topic per REST operation; the command topics of the decision are retired since ticket 95b).
- `ValidateTransaction` whose credit party is outside the core (`BILLER_ACCOUNT`, `BANK_ACCOUNT`,
  `CARD_TOKEN`; ticket 21, BILL_PAY) checks the payer side only (wallet types, status, assertion,
  limits, funds with the type's fee); `PostTransfer` still needs a credit wallet
  (`CREDIT_WALLET_NOT_FOUND`): such flows hold, then commit to `SETTLEMENT`. A TOPUP's credit
  party `MSISDN` (the number recharged; `PAYER_SIDE_ONLY_PAYEES`) is validated the same way: it
  need not have a wallet and may be the payer's own number (no `SAME_WALLET`).
- `ValidateTransaction` whose debit party is outside the core (`BANK_ACCOUNT`, `CARD_TOKEN`,
  `BILLER_ACCOUNT`; ticket 23, ADD_MONEY_BANK / C2W) checks the receiving wallet only: it exists
  (`CREDIT_WALLET_NOT_FOUND`), not LOCKED (`ACCOUNT_LOCKED`) or blocked
  (`CREDIT_ACCOUNT_BLOCKED`), the assertion with that wallet as subject (the customer authorises the add
  money), amount, per-transaction limit; no funds or daily debit check, fee of the type
  (none configured). Moves nothing: the money arrives by `CreditWallet` from `SETTLEMENT` once the
  bank confirmed the debit. `PostTransfer` / `HoldFunds` from such a party are refused
  `WALLET_NOT_FOUND`.
- Emits the result events (`TransactionValidated`/`TransactionRejected`, `FundsHeld`/`HoldRejected`,
  `HoldCommitted`/`CommitFailed`, `HoldReleased`/`ReleaseFailed`, `TransferPosted`/`TransferRejected`,
  `WalletCredited`/`CreditRejected`, `TransactionReversed`/`ReversalRejected`, `TransactionStatus`,
  `WalletUnlocked`/`UnlockRejected`, `BalanceReported`/`BalanceInquiryRejected`) through the transactional outbox, in the same transaction as the ledger change and the
  `command_log` row. Idempotent on `commandId`: the first result event is stored verbatim and a
  repeated command gets the same event again (a second outbox row, one ledger effect). A command
  whose `expiresAt` passed before it was first seen is dropped without an event.
- Holds: `HoldFunds` runs the same checks as a transfer on the payer (assertion, limits, available funds;
  the credit party need not be a wallet) and reserves `amount + fee` in `wallet.held_minor`: the
  ledger balance is unchanged, the available balance (`balance - held`) shrinks, so later holds and
  transfers see less. The hold keeps its txnType, the fee's VAT and commission and the settlement wallet
  its commit credits (`attributes.settlementWallet` of the payload, REST `settlementWallet`; absent =
  `SETTLEMENT`; not a SYSTEM `SETTLEMENT*` wallet `HoldRejected SETTLEMENT_WALLET_NOT_FOUND`; V13).
  `CommitHold` debits the payer (amount to that settlement wallet, the fee split into `FEE_INCOME`,
  `VAT_PAYABLE`, `COMMISSION_PAYABLE`) and frees the hold; `ReleaseHold` only frees it. Committing a
  committed hold or releasing a released one (new commandId) succeeds without effect; commit of a released
  hold fails `HOLD_RELEASED`, release of a committed one `HOLD_ALREADY_COMMITTED`, unknown ids
  `HOLD_NOT_FOUND`. `holdId` is `HOLD-<coreRef>`.
- Hold expiry (ticket 94, FRS 7.3; `service/HoldExpiry`, `scheduler/HoldExpiryJob` ShedLock `hold-expiry`,
  `mw.coremock.hold-ttl` 30m, `mw.coremock.hold-expiry.cron` every 15 s ("-" in tests), `batch-size` 500):
  `hold.expires_at` = placed + ttl; a HELD hold past it is released in its own transaction (status
  `EXPIRED`, reservation freed) and `HoldExpired {holdId, walletId, amount, fee, expiresAt}` goes to
  `core.payment.event.v1` through the outbox, once (key and txnId the hold's, commandId
  `hold-expiry:<holdId>`, coreRef the hold's, balance after). Then commit fails `HOLD_EXPIRED`, release
  succeeds without effect, QueryTransaction / WAL-13 report `RELEASED` (the money is back). mw-orchestrator
  drops the event today (unmapped) and handles it from ticket 95. Test: `service/HoldExpiryIT`.
- `CreditWallet`: money from a settlement wallet: the one `attributes.settlementWallet` (REST
  `settlementWallet`) names, e.g. `SETTLEMENT_NPSB`, `SETTLEMENT_UCB_IMT` (unknown: `CreditRejected
  SETTLEMENT_WALLET_NOT_FOUND`), else `SETTLEMENT` (seeded with 10^12 poisha, the partners' with 10^11, so
  the sum of all balances is constant), no assertion, no fee. A txn is credited once whatever the commandId (an operator's
  fresh `<txnId>:credit:manual-<n>` round): an already credited txn is answered `WalletCredited` without
  effect, a reversed one refused `ALREADY_REVERSED`. `ReverseTransaction`: undoes every entry (fee included) of
  `originalTxnId` once, under the reversal's own txnId, with `REVERSAL` entries; the amount must
  equal the original transfer amount (`AMOUNT_MISMATCH`), the original must have entries
  (`ORIGINAL_NOT_FOUND`: a merely held txn has none), must not be a reversal
  (`ORIGINAL_NOT_REVERSIBLE`) nor reversed (`ALREADY_REVERSED`), and every wallet must afford it
  (`INSUFFICIENT_BALANCE`). Reversal does not lower a wallet's daily-limit usage.
- `QueryTransaction` reads the ledger and `command_log`, never the outbox, so it reports the true
  outcome of a command whose reply was dropped. With `queryCommandId`: that command's outcome.
  Without: `REVERSED` if reversed, else the txn's latest hold state (`HELD`/`COMMITTED`/`RELEASED`,
  with `holdId`), else the outcome of the latest non-query command (`VALIDATED`, `POSTED`, `CREDITED`,
  `REJECTED`), else `NOT_FOUND`.
- **Mock control** (ticket 103, ADR D33, FRS MCK-04 core part, MCK-08, MCT-01..03, MCT-06..08, 7.3 "same
  scenario controls"; `service/mockcontrol/`, copied from mw-simulators' engine (module isolation), contract
  `api/mw-core-mock/core-mock-control.v1.openapi.yaml`, `controller/MockControlController`): only in Spring
  profile `mock-control` (`MockControl.PROFILE`; compose sets `${SPRING_PROFILES_ACTIVE:-dev},mock-control`, the
  test config sets it; without it `NoMockControl` lets the ledger rules decide and no control path, journal or
  alias exists). `config/MockControlProdGuard` refuses to start with the profile and `mw.service.env`
  (`MW_ENV`) `prod`; `scripts/check-mock-control-prod.sh` (`./gradlew :mockControlProdCheck`) keeps it off
  every other service. One party `core` (`MockParties`): operations validate, hold, commit, release,
  transfer, credit, reversal, query, balance, charges, limits, accounts, statements, unlock, factors (command
  type -> operation in `MockParties.OPERATION_OF_COMMAND`); keys `debit`, `credit`, `wallet` (wallet ids /
  MSISDNs), `txnId`, `holdId`, `originalTxnId`. Same paths and lookup as mw-simulators (`/mock-control/core/
  scenarios|switch|settings|journal`, `GET /mock-control/parties`); a scenario `code` up to 64 chars (core
  reasons). `ScenarioEngine.decide` runs in `CoreCommandService.handle` for every command, Kafka or REST
  (after the expiry check): BUSINESS_ERROR = the command's failure event with reason `code` (default
  `INJECTED_REJECT`; stored in `command_log`, so a repeat is the same refusal; ignored for QueryTransaction),
  and the result event is shaped: DELAY / TIMEOUT (hold `delayMs`, default settings' `timeoutDelayMs`, else
  `mw.coremock.mock-control.timeout-delay` 30 s) / LATE_CALLBACK delay it (`outbox.available_at`),
  DUPLICATE_CALLBACK twice, NO_CALLBACK and HTTP_5XX / MALFORMED_RESPONSE / CONNECTION_RESET never (applied,
  reply lost). Switch on Kafka: DOWN = not handled (no effect, no event), MAINTENANCE = the failure event
  `CORE_UNAVAILABLE`, not logged. On `/core/v1/**` `filter/MockControlFilter` (registered after the security
  chain by `config/MockControlConfig`, with `filter/MockAbortValve` copied from mw-simulators) opens a
  `RestCall`: the switch once (DOWN closes the connection, MAINTENANCE `503 MW-SYS-CORE_UNAVAILABLE`, SLOW
  latency), reads decided there (BUSINESS_ERROR `422 MW-CORE-<code>`), money calls by the engine inside the
  command; then the answer is held (DELAY, TIMEOUT: applied), replaced (`<httpStatus|500> MW-SYS-CORE_ERROR`),
  cut short or dropped; a NO_CALLBACK (the legacy `DROP_REPLY` fault) also loses the REST answer: `504
  MW-TMO-CORE_ANSWER_LOST` after the call was applied or refused (ticket 95b; the API suite's lost-answer
  scenarios keep their UNKNOWN -> WAL-13 path; `CoreMockScenarioIT`). Journal: one entry per handled command (`COMMAND /<operation>/<commandType>`,
  header `x-txn-id`, body the keys; never an assertion), last `journal-limit` 1000. `DELETE
  /mock-control/core/journal` clears journal, scenarios, switch, settings (ledger stays). `POST
  /mock-control/reset {seedVersion?}` (MCT-07; `CoreReset`, called by mw-simulators' global reset) =
  `WalletSeeder.restore(version)` then the party reset, answers `{parties:[{party: core, status: RESET,
  seedVersion}]}`: wallets outside the seed (seed API: mw-seeder's personas, the test console) back to their
  opening balance (their entries undone), nothing held; `entry`, `hold`, `command_log`, `rest_request`,
  `reversal`, `used_assertion` truncated; the seed's wallets re-created and the sheets replaced from
  `seed/<version>/`; a given version stays in force (`WalletSeeder.version()`), an unknown one `400 MW-VAL-001`.
  Tests: `controller/CoreMockScenarioIT`, `controller/MockControlResetIT` (seed `t1` on the test classpath),
  `service/mockcontrol/LegacyFaultsTest`, `service/SeedLoaderTest`, `config/ProdGuardTest`.
- Fault injection, deprecated alias for one release (`controller/FaultController` -> `mockcontrol/LegacyFaults`,
  profile `mock-control` only, one `coremock.faults.deprecated` log line): `POST /core-mock/v1/faults`
  `{mode, commandType?, txnId?, delayMs?, reasonCode?, times?}`, `GET` lists, `DELETE /core-mock/v1/faults`
  removes the faults (only their scenarios), `DELETE /core-mock/v1/faults/{id}` one. Each fault is one scenario
  of `core`: `commandType` = operation, `txnId` = key, `times` = nextN; `REJECT` = BUSINESS_ERROR `reasonCode`,
  `DELAY` = DELAY, `DROP_REPLY` = NO_CALLBACK, `DUPLICATE_REPLY` = DUPLICATE_CALLBACK (outcome and reply are
  separate dimensions: a REJECT with a DROP_REPLY is a lost refusal). A replay of an already logged command is
  answered from the log (a refusal is not re-decided), but delay / drop / duplicate still shape that reply.
- Balances (admin API, not a product contract): `GET /core-mock/v1/wallets/{walletId}` (id or MSISDN)
  -> `{walletId, type, status, balanceMinor, heldMinor, availableMinor}`, unknown `400 MW-VAL-003`.
  The e2e suite reads it (and the faults alias) through the dev-only APISIX route
  `/test/core-mock/v1/*` (`deploy/apisix/routes/test-core-mock.json`).
- Wallet seed API (ticket 57, test-only, for mw-seeder; contract `api/mw-core-mock/core-mock-seed.v1.openapi.yaml`):
  `PUT /core-mock/v1/wallets/{walletId}` `{type: CUSTOMER|AGENT|MERCHANT|DISTRIBUTOR|ORG|ORGANISATION (ORG stored as
  ORGANISATION, full KYC), openingBalanceMinor}`
  (`controller/WalletSeedController` -> `service/WalletSeedService` -> `WalletRepository.insertIfAbsent`):
  create-if-absent with id = MSISDN (`01[3-9]` + 8 digits, else 400 MW-VAL-001) and the
  opening balance (no PIN: mw-seeder sets first PINs at mw-auth), no ledger entries (as `WalletSeeder`); 201 created, 200 already there (never changed,
  whatever the body). Only with `mw.coremock.seed-api.enabled=true` (`MW_COREMOCK_SEED_API_ENABLED`, set by
  `deploy/docker-compose.yml`; default off: a PUT is then 405). No APISIX route: mw-seeder calls it
  inside the app network.
- Ledger in schema `coremock` (role `coremock_rw`): `wallet`, `hold`, `entry`, `command_log`, `reversal`,
  `tariff`, `limit_rule` (V13, ticket 94, FRS 7.1). Accounts: type CUSTOMER, AGENT, MERCHANT, DISTRIBUTOR,
  ORGANISATION (ORG before 94: V13 renamed the rows; `Wallet.canonicalType` still reads ORG from the seed
  PUT, `wallet-types` config and the seed files), SYSTEM; status ACTIVE, LOCKED, BLOCKED, HOLD, DORMANT
  (cannot pay `ACCOUNT_DORMANT`, can receive), CLOSED (neither: `ACCOUNT_CLOSED` /
  `CREDIT_ACCOUNT_BLOCKED`); `kyc_level` BASIC / FULL. Every posting goes through `LedgerRepository.post`:
  each leg moves its wallet and writes its entry with `balance_after_minor` and `txn_type` in one
  statement, legs in wallet id order (people before system wallets: the lock order), credits before
  debits, and refused unless they balance (FRS 7.3: every txnId sums to zero; `LedgerInvariantIT`).
  `FEE-POOL` became `FEE_INCOME` (V13 moved its balance and entries).
- Seed (on every start, idempotent, `WalletSeeder` + `service/SeedFiles`; the mock-control global reset
  reloads it, `WalletSeeder.restore`, above; files
  `src/main/resources/seed/<mw.coremock.seed.version, v1>/`): `wallets.csv` (ranges, wallet id = MSISDN:
  1,000 customers `01711000001..` (`..0901..1000` BASIC KYC), 50 agents `01811000001..`, 10 distributors
  `01611000001..`, 20 merchants `01911000001..`, 3 organisations `01311000001..`), `system-wallets.csv`
  (`FEE_INCOME`, `VAT_PAYABLE`, `COMMISSION_PAYABLE`, `SETTLEMENT`, `SETTLEMENT_OCS_GP|ROBI|AIRTEL|TELETALK|BL`,
  `SETTLEMENT_BILLER_DESCO|DPDC|WASA|TITAS`, `SETTLEMENT_AGG_BILLAGG`, `SETTLEMENT_BANK_UCB|BRAC|DBBL`,
  `SETTLEMENT_NPSB`, `SETTLEMENT_CYBERSOURCE`, `SETTLEMENT_UCB_IMT`, `SETTLEMENT_UCB_ATM`), `tariff.csv` and
  `limits.csv`. Wallets already there are left alone (missing holder names filled), the two sheets are
  replaced by the files. No PIN (mw-auth holds PINs). Test: `service/WalletSeederIT`.
- Tariff (ticket 94, FRS 7.1, WAL-04; `service/Tariff`, pure, `TariffTest`): the row of the txnType whose
  `[slab_min, slab_max]` holds the amount, channel and customer (payer wallet) type equal or `*`, the most
  specific (channel before customer type), else no charges. The commands carry no channel, so only `*`
  channel rows apply to them. The fee is paid on top of the amount, VAT inside it: `vat = fee * pct / (100
  + pct)` half up; each `commission_split` share (`AGENT:40|DISTRIBUTOR:10`, percent of the fee net of
  VAT) rounded down; FEE_INCOME keeps the rest, so the legs add up to the fee exactly. Sheet v1: P2P 5 Tk,
  cash out 10 Tk (40 % agent, 10 % distributor), top-up 2 Tk, bill pay 3 Tk up to 5,000 Tk, higher slabs
  above; others free; VAT 15 %. Fee legs: payer DEBIT FEE (the fee), FEE_INCOME CREDIT FEE, VAT_PAYABLE
  CREDIT VAT, COMMISSION_PAYABLE CREDIT COMMISSION (`service/Legs`). `TransactionValidated.charges` =
  `SERVICE_FEE` (net of VAT) + `VAT`.
- Limits (ticket 94, FRS 7.1, WAL-05; `service/Limits`, pure, `LimitsTest`; applied by
  `TransferRules.limits`): the payer's rule, the most specific of txnType, wallet type, KYC level (each or
  `*`); checks per txn min (`LIMIT_BELOW_MIN_PER_TXN`) and max (`LIMIT_EXCEEDED_PER_TXN`), daily amount
  (`LIMIT_EXCEEDED_DAILY`) and count (`LIMIT_EXCEEDED_DAILY_COUNT`), monthly amount (`LIMIT_EXCEEDED_MONTHLY`)
  and count (`LIMIT_EXCEEDED_MONTHLY_COUNT`), UTC days and months, over TRANSFER debits (distinct txns for
  counts; a typed rule counts only its txnType's debits, a `*` rule all); empty = no limit. Sheet v1: `*`
  5,000 Tk per txn, 15,000 Tk and 1,000 txns a day, 2,00,000 Tk a month; BASIC customers 1,000 Tk / 3,000 Tk
  a day; P2P and cash out of FULL customers count limits; ORGANISATION 50,000,000 Tk a day (a whole bulk
  batch). Counts are generous on purpose (the API suite reruns on one stack in a day). The payee of money
  from outside (ADD_MONEY_BANK validate) is checked against its own rule's per-txn min/max only.
- Wallet types per `txnType` (`mw.coremock.wallet-types`, debit -> credit): CASH_IN AGENT ->
  CUSTOMER, CASH_OUT CUSTOMER -> AGENT, MERCHANT_PAY CUSTOMER -> MERCHANT (ticket 30, fee 0),
  BULK_DISBURSE ORGANISATION -> CUSTOMER (ticket 34, fee 0); other types unrestricted. BULK_DISBURSE carries
  no assertion (contract: the org authorised the batch by maker-checker; `TransferPayload.authorisationRequired`);
  any other transfer without `assertion` + `idempotencyKey` is malformed (retry topics, DLQ).
  Wrong types `INVALID_COUNTERPARTY`
  (validate, post and hold; decided before the assertion, so its jti is not claimed).
- Unlock (ticket 52, M2 gate decision; a core-side lock since ticket 72): `UnlockWallet` (sent by mw-admin-api
  after maker-checker approval, payload `subject` WALLET + `changeRequestId`, `makerId`, `checkerId`) sets a
  LOCKED wallet back to ACTIVE (`WalletRepository.unlock`, guarded by `status = 'LOCKED'`; log event
  `wallet.unlocked`) and answers `WalletUnlocked {walletId}`. A wallet that is not
  LOCKED (ACTIVE, or BLOCKED for another reason: an unlock never lifts a block) gets `UnlockRejected
  WALLET_NOT_LOCKED`, an unknown one `WALLET_NOT_FOUND`; nothing changes. Idempotent on `commandId`
  like every command (a replay is answered from `command_log`, even after the wallet locked again).
  A subject that is not a WALLET is malformed (retry topics, DLQ). The events go on the paired event
  topic (`core.wallet.event.v1`); mw-orchestrator drops them (`txn.unknown`) and mw-admin-api does
  not consume them. Reject faults apply (`UnlockRejected`). QueryTransaction of an unlock's txnId is
  not meaningful (reported REJECTED; nobody asks).
- Wallet state feed (ticket 67, spec 18.7, D5; `service/WalletStateEvents`, `mapper/CoreEventMapper.walletStateChanged`):
  `WalletStateChanged` on `core.wallet.event.v1` for every wallet with an MSISDN, through the outbox in
  the transaction of the change, key = walletId: `CREATED` (`WalletSeeder.seed` for the rows it inserts,
  the seed API when it creates), `STATUS_CHANGED` (the unlock), and with
  `mw.coremock.wallet-snapshot-on-start` (compose `MW_COREMOCK_WALLET_SNAPSHOT=true`, default off) a
  `SNAPSHOT` of every wallet after the seed on start. Payload `{walletId, msisdn, walletType (ORGANISATION
  for an organisation since ticket 94; mw-auth stores it as ORG), status (DORMANT, CLOSED since 94), kycLevel
  (94), hasPin (always false since ticket 72), version, change}`; `wallet.version` (V7) comes from one sequence `wallet_version_seq` and takes
  the next value on every status change, so it only grows, also across a ledger reset. Envelope (no
  command answered): a fresh UUIDv7 txnId (`util/UuidV7`), commandId `wallet-state:<txnId>`, coreRef
  `WALLET-V<version>`, outcome SUCCESS. mw-auth projects it; mw-orchestrator drops it (`txn.unknown`).
  A status set by hand in SQL (tests) publishes nothing. Test: `service/WalletStateEventsIT`.
- Reason codes (core reasons, mapped by the orchestrator): `WALLET_NOT_FOUND`,
  `CREDIT_WALLET_NOT_FOUND`, `INVALID_COUNTERPARTY`, `ACCOUNT_LOCKED`, `ACCOUNT_BLOCKED`,
  `CREDIT_ACCOUNT_BLOCKED`, `SAME_WALLET`, `MW-AUTH-046` (assertion), `INVALID_AMOUNT`,
  `LIMIT_EXCEEDED_PER_TXN`, `LIMIT_EXCEEDED_DAILY`, `INSUFFICIENT_BALANCE`, and since ticket 94
  `ACCOUNT_DORMANT`, `ACCOUNT_CLOSED`, `LIMIT_BELOW_MIN_PER_TXN`, `LIMIT_EXCEEDED_DAILY_COUNT`,
  `LIMIT_EXCEEDED_MONTHLY`, `LIMIT_EXCEEDED_MONTHLY_COUNT`, `SETTLEMENT_WALLET_NOT_FOUND`, `HOLD_EXPIRED`, plus the hold and
  reversal reasons above, `WALLET_NOT_LOCKED` (unlock), `FACTORS_MISMATCH` and `WALLET_NOT_ACTIVE`
  (VerifyCustomerFactors, BalanceInquiry). Check order: wallets, wallet types, status, assertion, amount, limits, funds.
- Transaction assertion (spec 18.7, D22, ticket 71; `service/AssertionCheck`, `security/AssertionKeys`,
  `client/AssertionJwksClient`, `repository/UsedAssertionRepository`, `util/TxnHash` + `util/CanonicalJson`
  (copied from mw-auth), `config/AssertionProperties` `mw.coremock.assertion.*`): a ValidateTransaction,
  PostTransfer or HoldFunds (every type but BULK_DISBURSE) must carry `assertion` plus `idempotencyKey` (else
  malformed) and is authorised by it, between status and amount in the check order.
  Checks: compact JWS, alg ES256 only, kid in mw-auth's set, signature, `iss` mw-auth, `aud` contains core,
  `sub` = the authorising wallet (payer; the payee of ADD_MONEY_BANK), `authMethod` PIN, `txnHash` = the hash
  recomputed from the command (txnType, amount, currency, debit and credit party type+id, the payload's
  idempotencyKey, the assertion's `channel`), then the jti in `coremock.used_assertion` (V8): used by
  another txnId refused; used by this txnId (the PostTransfer after its ValidateTransaction) accepted
  without the expiry check; unused: `exp` + `clock-skew` 30 s must not have passed (and `iat` not in the
  future), then the jti is claimed for this txnId in the command transaction. Every refusal is the
  command's failure event with reasonCode **`MW-AUTH-046`** (`Reason.ASSERTION_INVALID`, `Reason.code()`;
  the orchestrator passes it on as it is), WARN `core.assertion.refused` with the cause, never the token.
  Key set: `GET http://mw-auth:8080/auth/v1/.well-known/assertion-jwks.json` (contract `auth.v1`, consumed
  API), fetched at start (`scheduler/AssertionKeyRefreshJob`, an `ApplicationRunner`) and every
  `refresh-period` 30 s (not ShedLock-guarded: a per-instance cache), never on a command; a failure keeps
  the last set (WARN `core.assertion.jwks_failed`), an unknown kid is refused until the next refresh.
  Used jtis are purged `used-retention` (1 d) after `exp` (`service/UsedAssertionPurge`,
  `scheduler/UsedAssertionPurgeJob`, ShedLock `used-assertion-purge`, `purge-cron`). A stray `encryptedPin` is ignored (unknown field) and authorises nothing. Tests: `service/AssertionRulesIT` (validate + post
  of one txn, hold, tampered amount, other idempotency key, wrong aud, other wallet, foreign key and
  garbage, jti replayed on another txn, expired vs within skew, purge), `security/TestAssertions` (in-test
  key + JWKS server, wired by `TestInfrastructure`), `util/TxnHashTest`, `contracts/ApiContractsTest`.
- Dev provisioning (`provision_mw_core_mock` in `deploy/provision/services.sh`, run by the one-shot `provision` on every `docker compose up`, idempotent, dev only): creates role
  and schema `coremock`, `secret/mw-core-mock` (DB password, created once and never
  overwritten; no PIN key pair since ticket 72) and the AppRole `mw-core-mock`; role-id/secret-id land in a volume the service reads
  as a Spring configtree (`/run/mw/approle`).

## Contracts owned

- `events/mw-core-mock/core.event.v1.schema.json` and its per-event payload schemas (topics
  `core.wallet.event.v1`, `core.payment.event.v1`). Tests validate every emitted event against it.
- Machine-readable list: `contracts.yml` (produces 23 `core.event.v1` types (ticket 67: `WalletStateChanged`; D26: `BalanceReported`,
  `BalanceInquiryRejected`; ticket 94: `HoldExpired`, no consumer in the repo until ticket 95); consumes no event
  since ticket 95b (`contracts/ConsumedContractsTest`)), checked by `./gradlew :contractsLint`, tested by `contracts/ProducedContractsTest` /
  `ConsumedContractsTest` (no Spring, no Docker). The fault and balance admin API is not a product
  contract; the wallet GET, the test-only seed PUT and the deprecated faults alias are `api/mw-core-mock/core-mock-seed.v1.openapi.yaml`
  (ticket 57, consumed by mw-seeder), served shapes checked by `contracts/ApiContractsTest`.
- `api/mw-core-mock/core-wallet.v1.openapi.yaml` (ticket 93, ADR D31): the core wallet REST API `/core/v1`,
  the proposal for the real core's contract; consumed by mw-orchestrator from ticket 95. Served bodies
  checked by `controller/CoreWalletRestIT`, request schemas by `contracts/ApiContractsTest`.

- `api/mw-core-mock/core-mock-control.v1.openapi.yaml` (ticket 103, test-only): the core party's mock control
  and the core's global reset; consumed by mw-simulators (`client/CoreMockControlClient`). Served bodies:
  `contracts/ApiContractsTest`, the paths: `contracts/ContractDriftTest` (profiles test + mock-control).

## Contracts used

- `api/mw-auth/auth.v1.openapi.yaml` (the assertion key set); `common/defs.v1` shapes. The former
  `events/mw-orchestrator/core.command.v1` is retired (ticket 95b); `dto/CoreCommand`, `TransferPayload`,
  `HoldPayload`, `CreditPayload`, `ReversePayload`, `QueryPayload`, `UnlockPayload`, `FactorsPayload` are the
  internal command shapes the REST API builds.

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
- Kafka through Spring Kafka (ADR D27; no Camel in this module, copied from the template, ticket 79):
  `messaging/EventPublisher` sends records with the 6.1 headers through `KafkaTemplate` (idempotent,
  `acks=all`, at most 5 in flight; `publish` waits for the ack, `publishStoredAsync` sends an outbox row
  with exactly its stored headers). `config/KafkaConfig` puts every `@KafkaListener` on non-blocking retry
  topics `<topic>.<group>.retry.1..3` (10 s / 1 min / 10 min, `mw.kafka.retry.delays`) then
  `<topic>.<group>.dlq` (`messaging/RetryTopicNaming`, group = `spring.kafka.consumer.group-id`, the
  service name; declared in `deploy/kafka/topics.txt`); validation and JSON failures and records that are
  not UTF-8 go to the DLQ at once; `messaging/RetryTopicRecoverer` adds `mw-retry-attempt`,
  `mw-original-topic`, `mw-error-class`, `mw-retry-not-before`; `messaging/RetryDueTimeCap` caps a forged
  due time; `messaging/ConsumerContextInterceptor` puts the record's ids in the MDC. Listeners take
  `ConsumerRecord<String, String>` and `Acknowledgment` and call `ack.acknowledge()` after handling
  (per-record commit, AckMode `MANUAL_IMMEDIATE`); a delay pauses the partition, never sleeps on the
  consumer thread. Settings: `spring.kafka.*`, `mw.kafka.send-timeout`, `mw.kafka.listener.shutdown-timeout`
  (100 ms in tests), `mw.kafka.retry.exclude-topics`. `messaging/RetryTopicIT` (conformance) checks it all.
- `scheduler/OutboxRelayScheduler`: relays the transactional outbox (`messaging/OutboxRelay`, 6.4) every
  `mw.outbox.relay.period` after a run ends, ShedLock-guarded; off in tests (`mw.outbox.relay.enabled`).
  Tests that consume create their topics with retry topics and DLQ first (`KafkaTestTopics.createWithRetries`).
- `application.yml`: Resilience4j defaults (19.2), own schema `core_mock` migrated by Flyway on
  start from `db/migration` (16.4); rename the schema to the spec 16.2 name if it has one.

## Commands

| Command (from the repo root) | What it does |
| --- | --- |
| `./gradlew :mw-core-mock:test` | every test in one JVM: unit, slice and contract tests (`contracts/*ContractsTest`), the Testcontainers `*IT` classes (e.g. `CoreMockApplicationIT`, `config/MwServicePropertiesIT`, `logging/JsonLoggingIT`) and the `@Conformance` suite; one PostgreSQL and one Kafka per test JVM, a fresh database per Spring context; the `*IT` and conformance classes are skipped without Docker. One class: `--tests '<class>'` |
| `./gradlew :mw-core-mock:build` | `test`, then the boot jar `build/libs/mw-core-mock.jar` (fixed name, the one the Dockerfile copies) |
| `./gradlew :mw-core-mock:bootJar && docker build -t mw-core-mock:dev services/mw-core-mock` | image, as spec 4.3 rule 1 |
| `./gradlew clean build` | the full gate: every module, plus the repo checks (`:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`); unchanged modules come from the build cache |
| `./gradlew :mw-core-mock:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-core-mock` | rebuild this service's image (`mfs-mw/mw-core-mock:local`) and restart it with what it depends on (dev timers from `deploy/.env`) |
