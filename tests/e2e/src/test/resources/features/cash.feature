@cash @mw-app-gateway @mw-orchestrator @mw-core-mock
Feature: Agent cash in and cash out from the Agent app through APISIX
  An agent (agent01, wallet 01811000001) cashes a customer in (agent float pays the customer)
  or out (the customer pays the agent, with the customer's PIN and the CASH_OUT fee). Only the
  agent role may initiate either. Balances are compared before and after, never absolute.

  Background:
    Given agent01 is signed in to the app
    And the core balances of these wallets are noted:
      | 01811000001 |
      | 01711000010 |
      | FEE-POOL    |

  Scenario: a cash-in moves the amount from the agent's float to the customer
    When agent01 submits CASH_IN of 2000 poisha with counterparty 01711000010 and PIN "1234"
    Then the response status is 200
    And the transaction status is COMPLETED
    And the core balances changed by:
      | 01811000001 | -2000 |
      | 01711000010 | 2000  |
      | FEE-POOL    | 0     |
    And the app shows the transaction as COMPLETED

  Scenario: a cash-out moves the amount from the customer to the agent, the fee to the pool
    When agent01 submits CASH_OUT of 2000 poisha with counterparty 8801711000010 and PIN "1234"
    Then the response status is 200
    And the transaction status is COMPLETED
    And the core balances changed by:
      | 01811000001 | 2000  |
      | 01711000010 | -3000 |
      | FEE-POOL    | 1000  |
    And the app shows the transaction as COMPLETED

  Scenario: a cash-in the agent's float cannot cover is rejected and moves no money
    Given the core rejects the next ValidateTransaction with reason INSUFFICIENT_BALANCE
    When agent01 submits CASH_IN of 2000 poisha with counterparty 01711000010 and PIN "1234"
    Then the response status is 422
    And the error code is "MW-CORE-INSUFFICIENT_BALANCE"
    And the response carries a txnId
    And no core balance changed
    And the app shows the transaction as REJECTED

  Scenario Outline: a customer may not initiate <type>
    When customer01 submits <type> of 2000 poisha with counterparty 01711000010 and PIN "1234"
    Then the response status is 403
    And the error code is "MW-AUTH-002"
    And no core balance changed

    Examples:
      | type     |
      | CASH_IN  |
      | CASH_OUT |
