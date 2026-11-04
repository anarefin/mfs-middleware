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
