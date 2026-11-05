@merchant @mw-app-gateway @mw-orchestrator @mw-core-mock
Feature: In-app merchant payment (Customer app) and the merchant's collections (Merchant app)
  The customer (customer01, wallet 01711000001) pays a merchant (merchant01, wallet 01911000001)
  with its own PIN; MERCHANT_PAY carries no fee. The merchant initiates nothing and sees the
  payments to its own wallet only, with the payer's number masked. Balances are compared before
  and after, never absolute.

  Background:
    Given the core balances of these wallets are noted:
      | 01711000001 |
      | 01911000001 |
      | FEE-POOL    |

  Scenario: a customer pays a merchant and the merchant sees the collection
    Given customer01 is signed in to the app
    When customer01 submits MERCHANT_PAY of 2500 poisha with counterparty 01911000001 and PIN "1234"
    Then the response status is 200
    And the transaction status is COMPLETED
    And the core balances changed by:
      | 01711000001 | -2500 |
      | 01911000001 | 2500  |
      | FEE-POOL    | 0     |
    And the app shows the transaction as COMPLETED
    And merchant01 sees the transaction in its collections with the payer masked as 0171****001
    And merchant02 does not see the transaction in its collections

  Scenario: a payment the customer's balance cannot cover is rejected and moves no money
    Given customer01 is signed in to the app
    And the core rejects the next ValidateTransaction with reason INSUFFICIENT_BALANCE
    When customer01 submits MERCHANT_PAY of 2500 poisha with counterparty 01911000001 and PIN "1234"
    Then the response status is 422
    And the error code is "MW-CORE-INSUFFICIENT_BALANCE"
    And the response carries a txnId
    And no core balance changed
    And the app shows the transaction as REJECTED
    And merchant01 does not see the transaction in its collections

  Scenario: a merchant payment to a wallet that is not a merchant is refused by the core
    Given customer01 is signed in to the app
    When customer01 submits MERCHANT_PAY of 2500 poisha with counterparty 01711000002 and PIN "1234"
    Then the response status is 422
    And the error code is "MW-CORE-INVALID_COUNTERPARTY"
    And no core balance changed

  Scenario Outline: <user> may not initiate <type>
    Given <user> is signed in to the app
    When <user> submits <type> of 2500 poisha with counterparty <counterparty> and PIN "1234"
    Then the response status is 403
    And the error code is "MW-AUTH-002"
    And no core balance changed

    Examples:
      | user       | type         | counterparty |
      | agent01    | MERCHANT_PAY | 01911000001  |
      | merchant01 | MERCHANT_PAY | 01911000002  |
      | merchant01 | P2P          | 01711000001  |

  Scenario Outline: <user> has no collections view
    Given <user> is signed in to the app
    When <user> lists its collections
    Then the response status is 403
    And the error code is "MW-AUTH-002"

    Examples:
      | user       |
      | customer01 |
      | agent01    |

  # Ticket 43 (criteria 1 and 2): a repeat with the same key, and unknown core outcomes resolved by
  # QueryTransaction (reply lost: applied, COMPLETED; refusal lost: nothing applied, FAILED).
  Scenario: a merchant payment sent twice with the same idempotency key pays the merchant once
    Given customer01 is signed in to the app
    When customer01 submits MERCHANT_PAY of 2500 poisha with counterparty 01911000001 and PIN "1234"
    And customer01 sends the same request again with the same idempotency key
    Then the response status is 200
    And every response carries the same txnId
    And the transaction status is COMPLETED
    And the core balances changed by:
      | 01711000001 | -2500 |
      | 01911000001 | 2500  |
      | FEE-POOL    | 0     |

  @tsq
  Scenario: a merchant payment whose core reply is lost is found by QueryTransaction and completes
    Given customer01 is signed in to the app
    And the core drops its reply to the next PostTransfer
    When customer01 submits MERCHANT_PAY of 2500 poisha with counterparty 01911000001 and PIN "1234"
    Then the response status is 202
    And the app shows the transaction as COMPLETED within 90 seconds
    And the core balances changed by:
      | 01711000001 | -2500 |
      | 01911000001 | 2500  |
      | FEE-POOL    | 0     |
    And merchant01 sees the transaction in its collections with the payer masked as 0171****001

  @tsq
  Scenario: a merchant payment whose core refusal is lost is found by QueryTransaction and fails
    Given customer01 is signed in to the app
    And the core refuses the next PostTransfer and its reply is lost
    When customer01 submits MERCHANT_PAY of 2500 poisha with counterparty 01911000001 and PIN "1234"
    Then the response status is 202
    And the app shows the transaction as FAILED with error "MW-TMO-003" within 90 seconds
    And no core balance changed
