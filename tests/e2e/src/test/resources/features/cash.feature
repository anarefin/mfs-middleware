@cash @mw-app-gateway @mw-orchestrator @mw-core-mock
Feature: Agent cash in (Agent app) and customer cash out to an agent (Customer app) through APISIX
  Cash in: the agent (agent01, wallet 01811000001) pays the customer from its float, with the
  agent's PIN. Cash out: the customer (customer01, wallet 01711000001) pays the agent with the
  customer's own PIN and the CASH_OUT fee. Only an agent may cash in and only a customer may
  cash out. Balances are compared before and after, never absolute.

  Background:
    Given the core balances of these wallets are noted:
      | 01811000001 |
      | 01711000001 |
      | FEE-POOL    |

  Scenario: an agent cash-in moves the amount from the agent's float to the customer
    Given agent01 is signed in to the app
    When agent01 submits CASH_IN of 2000 poisha with counterparty 01711000001 and PIN "1234"
    Then the response status is 200
    And the transaction status is COMPLETED
    And the core balances changed by:
      | 01811000001 | -2000 |
      | 01711000001 | 2000  |
      | FEE-POOL    | 0     |
    And the app shows the transaction as COMPLETED

  Scenario: a customer cash-out pays the agent, the fee goes to the pool
    Given customer01 is signed in to the app
    When customer01 submits CASH_OUT of 2000 poisha with counterparty 8801811000001 and PIN "1234"
    Then the response status is 200
    And the transaction status is COMPLETED
    And the core balances changed by:
      | 01811000001 | 2000  |
      | 01711000001 | -3000 |
      | FEE-POOL    | 1000  |
    And the app shows the transaction as COMPLETED

  Scenario: a cash-in the agent's float cannot cover is rejected and moves no money
    Given agent01 is signed in to the app
    And the core rejects the next ValidateTransaction with reason INSUFFICIENT_BALANCE
    When agent01 submits CASH_IN of 2000 poisha with counterparty 01711000001 and PIN "1234"
    Then the response status is 422
    And the error code is "MW-CORE-INSUFFICIENT_BALANCE"
    And the response carries a txnId
    And no core balance changed
    And the app shows the transaction as REJECTED

  Scenario: a cash-out to a wallet that is not an agent is refused by the core
    Given customer01 is signed in to the app
    When customer01 submits CASH_OUT of 2000 poisha with counterparty 01711000002 and PIN "1234"
    Then the response status is 422
    And the error code is "MW-CORE-INVALID_COUNTERPARTY"
    And no core balance changed

  Scenario: the agent's own wallet is never a counterparty
    Given agent01 is signed in to the app
    When agent01 submits CASH_IN of 2000 poisha with counterparty 01811000001 and PIN "1234"
    Then the response status is 400
    And the error code is "MW-VAL-012"
    And no core balance changed

  Scenario Outline: <user> may not initiate <type>
    Given <user> is signed in to the app
    When <user> submits <type> of 2000 poisha with counterparty <counterparty> and PIN "1234"
    Then the response status is 403
    And the error code is "MW-AUTH-002"
    And no core balance changed

    Examples:
      | user       | type     | counterparty |
      | customer01 | CASH_IN  | 01711000002  |
      | agent01    | CASH_OUT | 01711000001  |

  # Ticket 43 (criteria 1 and 2): repeats with the same key, and unknown core outcomes resolved by
  # QueryTransaction (reply lost: applied, COMPLETED; refusal lost: nothing applied, FAILED).
  Scenario: an agent cash-in sent twice with the same idempotency key moves the money once
    Given agent01 is signed in to the app
    When agent01 submits CASH_IN of 2000 poisha with counterparty 01711000001 and PIN "1234"
    And agent01 sends the same request again with the same idempotency key
    Then the response status is 200
    And every response carries the same txnId
    And the transaction status is COMPLETED
    And the core balances changed by:
      | 01811000001 | -2000 |
      | 01711000001 | 2000  |
      | FEE-POOL    | 0     |

  Scenario: a customer cash-out sent twice with the same idempotency key moves the money once
    Given customer01 is signed in to the app
    When customer01 submits CASH_OUT of 2000 poisha with counterparty 8801811000001 and PIN "1234"
    And customer01 sends the same request again with the same idempotency key
    Then the response status is 200
    And every response carries the same txnId
    And the transaction status is COMPLETED
    And the core balances changed by:
      | 01811000001 | 2000  |
      | 01711000001 | -3000 |
      | FEE-POOL    | 1000  |

  @tsq
  Scenario: a cash-in whose core reply is lost is found by QueryTransaction and completes
    Given agent01 is signed in to the app
    And the core drops its reply to the next PostTransfer
    When agent01 submits CASH_IN of 2000 poisha with counterparty 01711000001 and PIN "1234"
    Then the response status is 202
    And the app shows the transaction as COMPLETED within 90 seconds
    And the core balances changed by:
      | 01811000001 | -2000 |
      | 01711000001 | 2000  |
      | FEE-POOL    | 0     |

  @tsq
  Scenario: a cash-in whose core refusal is lost is found by QueryTransaction and fails
    Given agent01 is signed in to the app
    And the core refuses the next PostTransfer and its reply is lost
    When agent01 submits CASH_IN of 2000 poisha with counterparty 01711000001 and PIN "1234"
    Then the response status is 202
    And the app shows the transaction as FAILED with error "MW-TMO-003" within 90 seconds
    And no core balance changed

  @tsq
  Scenario: a cash-out whose core reply is lost is found by QueryTransaction and completes
    Given customer01 is signed in to the app
    And the core drops its reply to the next PostTransfer
    When customer01 submits CASH_OUT of 2000 poisha with counterparty 8801811000001 and PIN "1234"
    Then the response status is 202
    And the app shows the transaction as COMPLETED within 90 seconds
    And the core balances changed by:
      | 01811000001 | 2000  |
      | 01711000001 | -3000 |
      | FEE-POOL    | 1000  |

  @tsq
  Scenario: a cash-out whose core refusal is lost is found by QueryTransaction and fails
    Given customer01 is signed in to the app
    And the core refuses the next PostTransfer and its reply is lost
    When customer01 submits CASH_OUT of 2000 poisha with counterparty 8801811000001 and PIN "1234"
    Then the response status is 202
    And the app shows the transaction as FAILED with error "MW-TMO-003" within 90 seconds
    And no core balance changed
