# mw-core-mock

Standalone service module (spec 4.2, 4.3). Package `mfs.mw.core.mock`. Follow the root
`CLAUDE.md` conventions; this file says what is specific to this service.

## Responsibilities

- Test double of the core wallet (spec "Core mock", 6.3, WP06): never ships to production, replaced by
  the real core with no change to the orchestrator.
- Consumes `core.wallet.command.v1` (`route/CoreWalletCommandRoute`) and `core.payment.command.v1`
  (`route/CorePaymentCommandRoute`), both with retry topics + DLQ. Handled: `ValidateTransaction`
  (fee, limits, PIN, account status; moves no money), `PostTransfer` (double-entry debit/credit plus
  fee), `HoldFunds`, `CommitHold`, `ReleaseHold`, `CreditWallet`, `ReverseTransaction`,
  `QueryTransaction`. `ApplyComplianceAction` and unknown types are logged and ignored.
- **Topic decision (spec 6.3 does not say which command goes where):** the mock accepts every
  command type on either command topic and answers on the paired event topic (wallet command ->
  `core.wallet.event.v1`, payment command -> `core.payment.event.v1`). The handler never looks at
  the topic otherwise, so the orchestrator may route as it likes; the intended split (not enforced)
  is: payment topic for `HoldFunds`/`CommitHold`/`ReleaseHold` (transactions with an external call),
  wallet topic for the rest. A command that is repeated on the other topic is answered from
  `command_log`, on the topic it arrived on.
- Emits the result events (`TransactionValidated`/`TransactionRejected`, `FundsHeld`/`HoldRejected`,
  `HoldCommitted`/`CommitFailed`, `HoldReleased`/`ReleaseFailed`, `TransferPosted`/`TransferRejected`,
  `WalletCredited`/`CreditRejected`, `TransactionReversed`/`ReversalRejected`, `TransactionStatus`)
  through the transactional outbox, in the same transaction as the ledger change and the
  `command_log` row. Idempotent on `commandId`: the first result event is stored verbatim and a
  repeated command gets the same event again (a second outbox row, one ledger effect). A command
  whose `expiresAt` passed before it was first seen is dropped without an event.
- Holds: `HoldFunds` runs the same checks as a transfer on the payer (PIN, limits, available funds;
  the credit party need not be a wallet) and reserves `amount + fee` in `wallet.held_minor`: the
  ledger balance is unchanged, the available balance (`balance - held`) shrinks, so later holds and
  transfers see less. `CommitHold` debits payer (amount to the `SETTLEMENT` system wallet, fee to
  `FEE-POOL`) and frees the hold; `ReleaseHold` only frees it. Committing a committed hold or
  releasing a released one (new commandId) succeeds without effect; commit of a released hold fails
  `HOLD_RELEASED`, release of a committed one `HOLD_ALREADY_COMMITTED`, unknown ids `HOLD_NOT_FOUND`.
  `holdId` is `HOLD-<coreRef>`.
- `CreditWallet`: money from the `SETTLEMENT` wallet (seeded with 10^12 poisha so the sum of all
  balances is constant), no PIN, no fee. `ReverseTransaction`: undoes every entry (fee included) of
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
- Fault injection (admin API, not a product contract, in memory): `POST /core-mock/v1/faults`
  `{mode, commandType?, txnId?, delayMs?, reasonCode?, times?}`, `GET` lists, `DELETE
  /core-mock/v1/faults` is the reset, `DELETE /core-mock/v1/faults/{id}` removes one. Modes:
  `DELAY` (reply published after `delayMs`, via `outbox.available_at`), `DROP_REPLY` (command takes
  effect and is logged, no reply), `DUPLICATE_REPLY` (two outbox rows, same eventId), `REJECT`
  (command refused without effect: the command's failure event with `reasonCode`, default
  `INJECTED_REJECT`; stored in `command_log` like any result; ignored for `QueryTransaction`, which
  has no failure event). A fault matches by `commandType` and/or `txnId` (absent = any) and fires at
  most `times` times (absent = until reset). A replay of an already logged command is answered from
  the log (REJECT does not re-decide), but DELAY/DROP/DUPLICATE still shape that reply.
- Balances (admin API, not a product contract): `GET /core-mock/v1/wallets/{walletId}` (id or MSISDN)
  -> `{walletId, type, status, balanceMinor, heldMinor, availableMinor}`, unknown `400 MW-VAL-003`.
  The e2e suite reads it (and the PIN key and faults) through the dev-only APISIX route
  `/test/core-mock/v1/*` (`deploy/apisix/routes/test-core-mock.json`).
- Ledger in schema `coremock` (role `coremock_rw`): `wallet`, `hold`, `entry`, `command_log`, `reversal`.
  Seed (on start, idempotent, `WalletSeeder`): 1,000 customers `01711000001..`, 50 agents
  `01811000001..`, 20 merchants `01911000001..`, 2 orgs `01311000001..` (wallet id = MSISDN) and the
  system wallets `FEE-POOL` and `SETTLEMENT`. All share the fake seed PIN `1234` (bcrypt). Balances, fees and limits
  are `mw.coremock.*` in `application.yml`; fee is a flat amount per `txnType`, debited from the payer
  on top of the amount; limits are per transaction and per UTC day (sum of transfer debits).
- Wallet types per `txnType` (`mw.coremock.wallet-types`, debit -> credit): CASH_IN AGENT ->
  CUSTOMER, CASH_OUT CUSTOMER -> AGENT; other types unrestricted. Wrong types `INVALID_COUNTERPARTY`
  (validate, post and hold; decided before the PIN, so it never counts a PIN failure).
- PIN lockout (spec 9.1, rule owned by the core): `wallet.pin_failures` counts consecutive wrong
  PINs on the debit wallet (validate, post, hold), a correct PIN resets it, the
  `mw.coremock.pin-max-failures`-th (5) in a row sets status `LOCKED` (log event
  `wallet.pin.locked`). A LOCKED wallet is refused as payer with `ACCOUNT_LOCKED` (before its PIN
  is checked) but can still be credited. Counting runs in the command transaction, so a replay
  answered from `command_log` never counts twice. No unlock API yet (seed reset only).
- Reason codes (core reasons, mapped by the orchestrator): `WALLET_NOT_FOUND`,
  `CREDIT_WALLET_NOT_FOUND`, `INVALID_COUNTERPARTY`, `ACCOUNT_LOCKED`, `ACCOUNT_BLOCKED`,
  `CREDIT_ACCOUNT_BLOCKED`, `SAME_WALLET`, `INVALID_PIN`, `INVALID_PIN_BLOCK`, `INVALID_AMOUNT`,
  `LIMIT_EXCEEDED_PER_TXN`, `LIMIT_EXCEEDED_DAILY`, `INSUFFICIENT_BALANCE`, plus the hold and
  reversal reasons above. Check order: wallets, wallet types, status, PIN, amount, limits, funds.
- PIN: RSA-OAEP with SHA-256 (hash and MGF1), PKCS#8 private key from OpenBao
  (`mw.coremock.pin.private-key`), checked against the wallet's bcrypt hash. The plaintext is never
  logged or stored. `GET /core-mock/v1/pin-public-key` exports `{keyId, algorithm, publicKeyPem}`.
- Init (`mw-core-mock-init`, label `mw.oneshot`, `init/init.sh`, idempotent, dev only): creates role
  and schema `coremock`, `secret/mw-core-mock` (DB password, PIN key pair, created once and never
  overwritten) and the AppRole `mw-core-mock`; role-id/secret-id land in a volume the service reads
  as a Spring configtree (`/run/mw/approle`). On a fresh stack it waits for OpenBao's
  `dev-init.json` and retries the database step when another init races it.

## Contracts owned

- `events/mw-core-mock/core.event.v1.schema.json` and its per-event payload schemas (topics
  `core.wallet.event.v1`, `core.payment.event.v1`). Tests validate every emitted event against it.

## Contracts used

- `events/mw-orchestrator/core.command.v1.schema.json` (+ the payload of each handled command),
  own types in `dto/CoreCommand`, `dto/TransferPayload`, `HoldPayload`, `CreditPayload`,
  `ReversePayload`, `QueryPayload`; `common/defs.v1` shapes. Tests build every command through the
  schema (`CoreCommandsTestSupport`).

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- `filter/CorrelationIdFilter`: `X-Correlation-Id` in, generated if absent, MDC `correlationId`.
- `filter/SecurityHeadersFilter`: security headers on every response (18.6).
- `exception/`: `MwException`, `ErrorCategory` (namespace -> HTTP status, retryable),
  `ErrorCodes`, `GlobalExceptionHandler` building `dto/ErrorEnvelope`. Logs show route
  templates, never raw URIs.
- `util/MaskingPolicy` + `@Sensitive`: masking rules of 18.3; mark every personal field.
- `logging/JsonLogFormatter`: JSON log lines with the 21.1 fields, masking before output.
  Add context with `log.atInfo().addKeyValue("event", "...")` or the MDC.
- `config/MwServiceProperties`: config binding; secrets from OpenBao when `VAULT_ENABLED=true`.
- `messaging/EventPublisher`: Kafka records with the 6.1 headers, idempotent `acks=all` producer.
- `route/RetryTopicRouteBuilder` + `messaging/RetryDelayListener`: subclass per consumed topic; retry
  topics 10 s / 1 min / 10 min then DLQ (19.2); per-record commit; no sleeps on Kafka threads.
- `application.yml`: Resilience4j defaults (19.2), own schema `core_mock` migrated by Flyway on
  start from `db/migration` (16.4); rename the schema to the spec 16.2 name if it has one.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | unit, slice and Testcontainers tests (Docker needed for `*IT`), JaCoCo gate (80% lines on `service`/`mapper`/`client`) |
| `./gradlew check bootJar && docker build -t mw-core-mock:dev .` | image, as spec 4.3 rule 1 |
| `make verify M=mw-core-mock` | isolation + field-injection check + contracts lint + `./gradlew check` |
| `make up M=mw-core-mock` | run this service's `compose.yml` (platform project must be up) |
