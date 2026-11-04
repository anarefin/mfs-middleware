@topup @mw-app-gateway @mw-orchestrator @mw-core-mock @mw-adapter-telco @mw-simulators
Feature: TOPUP airtime from the app (hold, partner, commit or release)
  A customer recharges a mobile number. The orchestrator validates, holds the payer's funds at the
  core, asks the operator's OCS (mw-simulators) through mw-adapter-telco, then commits the hold on
  success or releases it on a refusal. The core (mw-core-mock) balances prove that money moves
  exactly once, or not at all. The OCS simulator refuses every MSISDN ending in 999.

  Background:
    Given customer01 is signed in to the app
    And the core balances of these wallets are noted:
      | 01711000001 |
      | SETTLEMENT  |
      | FEE-POOL    |

  Scenario: a successful top-up debits the payer once and leaves nothing held
    When customer01 tops up 2000 poisha gp PREPAID airtime on 01711000002 with PIN "1234"
    Then the response status is 200
    And the transaction status is COMPLETED
    And the core balances changed by:
      | 01711000001 | -2200 |
      | SETTLEMENT  | 2000  |
      | FEE-POOL    | 200   |
    And no funds are held on 01711000001
    And the app shows the transaction as COMPLETED

  Scenario: an operator refusal releases the hold and moves no money
    When customer01 tops up 2000 poisha gp PREPAID airtime on 01711000999 with PIN "1234"
    Then the response status is 502
    And the error code is "MW-PTR-GP-TOPUP_REJECTED"
    And the response carries a txnId
    And no core balance changed
    And no funds are held on 01711000001
    And the app shows the transaction as FAILED

  Scenario: a wrong PIN is refused by the core before anything is held
    When customer01 tops up 2000 poisha gp PREPAID airtime on 01711000002 with PIN "9999"
    Then the response status is 422
    And the error code is "MW-CORE-INVALID_PIN"
    And no core balance changed

  Scenario: a hold whose reply is lost is found by a status query and released
    Given the core drops its reply to the next HoldFunds
    When customer01 tops up 2000 poisha gp PREPAID airtime on 01711000002 with PIN "1234"
    Then the response status is 504
    And the error code is "MW-TMO-002"
    And no core balance changed
    And no funds are held on 01711000001
    And the app shows the transaction as FAILED

  Scenario: the same idempotency key sent twice tops up once and returns the same txnId
    When customer01 tops up 2000 poisha gp PREPAID airtime on 01711000002 with PIN "1234"
    And customer01 sends the same request again with the same idempotency key
    Then the response status is 200
    And every response carries the same txnId
    And the transaction status is COMPLETED
    And the core balances changed by:
      | 01711000001 | -2200 |
      | SETTLEMENT  | 2000  |
      | FEE-POOL    | 200   |
    And no funds are held on 01711000001

  Scenario: a top-up without operator is refused by the app API
    When customer01 tops up 2000 poisha airtime on 01711000002 without an operator
    Then the response status is 400
    And the error code is "MW-VAL-001"
    And no core balance changed

  @mw-audit-dw
  Scenario: a completed top-up leaves an audit trail of the hold path
    When customer01 tops up 2000 poisha gp PREPAID airtime on 01711000002 with PIN "1234"
    Then the response status is 200
    And the audit trail of the transaction records the states "RECEIVED, VALIDATING, HOLDING, PARTNER_PENDING, COMMITTING, COMPLETED"
