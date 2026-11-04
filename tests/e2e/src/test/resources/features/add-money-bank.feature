@addmoney @c2w @mw-app-gateway @mw-orchestrator @mw-core-mock @mw-adapter-bank @mw-simulators @mw-audit-dw
Feature: ADD_MONEY_BANK (C2W): add money from a linked bank account (ticket 23, spec WP16)
  A customer adds money to the wallet from a linked bank account. The orchestrator validates the
  receiving wallet and its PIN at the core, asks the bank (the C2W bank simulator c2wbank in
  mw-simulators) through mw-adapter-bank to debit the account, and credits the wallet with
  CreditWallet once the bank confirmed. Money is inbound, so nothing is ever held. The bank
  accepts every debit with 202 and posts the outcome, signed hmac-sha256, to
  /partner/c2wbank/callback/debits through APISIX; mw-adapter-bank verifies it, deduplicates on
  partner, reference and outcome, and writes it to mw.partner.inbound.v1 before answering. The
  bank refuses accounts ending in 999; an amount ending in .13 makes it answer too late and send
  no callback (the status inquiry finds the debit). The credited money comes from the core's
  SETTLEMENT wallet; there is no fee.

  Background:
    Given customer01 is signed in to the app
    And the bank confirms debits by callback
    And the core balances of these wallets are noted:
      | 01711000001 |
      | SETTLEMENT  |
      | FEE-POOL    |

  Scenario: a confirmed debit credits the wallet once and holds nothing
    When customer01 adds 50000 poisha from bank account 1234567890123 at c2wbank with PIN "1234"
    Then the app shows the transaction as COMPLETED within 25 seconds
    And the core balances changed by:
      | 01711000001 | 50000  |
      | SETTLEMENT  | -50000 |
      | FEE-POOL    | 0      |
    And no funds are held on 01711000001
    And the audit trail of the transaction records the states "RECEIVED, VALIDATING, PARTNER_PENDING, COMMITTING, COMPLETED" once each

  @callback
  Scenario: a replayed bank callback has no second effect
    When customer01 adds 51000 poisha from bank account 1234567890123 at c2wbank with PIN "1234"
    Then the app shows the transaction as COMPLETED within 25 seconds
    When the bank replays the transaction's callback
    Then the replayed callback was acknowledged
    And the app shows the transaction as COMPLETED
    And the core balances changed by:
      | 01711000001 | 51000  |
      | SETTLEMENT  | -51000 |
      | FEE-POOL    | 0      |
    And the audit trail of the transaction records no late callback
    And the audit trail of the transaction records the states "RECEIVED, VALIDATING, PARTNER_PENDING, COMMITTING, COMPLETED" once each

  @callback
  Scenario: a callback the bank sends twice credits the wallet once
    Given the bank confirms debits by callback, sending each callback twice
    When customer01 adds 52000 poisha from bank account 1234567890123 at c2wbank with PIN "1234"
    Then the app shows the transaction as COMPLETED within 25 seconds
    And the core balances changed by:
      | 01711000001 | 52000  |
      | SETTLEMENT  | -52000 |
      | FEE-POOL    | 0      |
    And the audit trail of the transaction records no late callback

  Scenario: a debit the bank refuses fails and moves nothing
    When customer01 adds 50000 poisha from bank account 1234567890999 at c2wbank with PIN "1234"
    Then the app shows the transaction as FAILED with error "MW-PTR-C2WBANK-DEBIT_REFUSED" within 25 seconds
    And no core balance changed
    And no funds are held on 01711000001

  Scenario: a wrong PIN is refused by the core and the bank is never asked
    When customer01 adds 50000 poisha from bank account 1234567890123 at c2wbank with PIN "9999"
    Then the app shows the transaction as REJECTED with error "MW-CORE-INVALID_PIN" within 25 seconds
    And no core balance changed

  # The bank holds its answer 30 s (past the adapter's 25 s limit) and sends no callback: the
  # adapter reports UNKNOWN, the status inquiry 30 s later finds the debit applied, and the
  # wallet is credited.
  @tsq
  Scenario: a bank timeout is resolved by status inquiry and the wallet is credited
    When customer01 adds 50013 poisha from bank account 1234567890123 at c2wbank with PIN "1234"
    Then the app shows the transaction as COMPLETED within 120 seconds
    And the core balances changed by:
      | 01711000001 | 50013  |
      | SETTLEMENT  | -50013 |
      | FEE-POOL    | 0      |
    And no funds are held on 01711000001
    And the audit trail of the transaction records the states "RECEIVED, VALIDATING, PARTNER_PENDING, UNKNOWN, COMMITTING, COMPLETED" once each
