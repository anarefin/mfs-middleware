@reversal @mw-app-gateway @mw-orchestrator @mw-core-mock @mw-audit-dw
Feature: Reversal of a completed transaction
  Operations compensates a COMPLETED transaction with a REVERSAL request on mw.txn.request.v1
  (until the admin console's maker-checker trigger exists, ticket 37, the suite publishes it).
  The original goes COMPLETED -> REVERSING -> REVERSED and the core undoes every entry of it,
  fee included, so every balance is back where it was; a core refusal parks it in MANUAL_REVIEW.

  Background:
    Given customer01 is signed in to the app
    And the core balances of these wallets are noted:
      | 01711000001 |
      | 01711000002 |
      | FEE-POOL    |

  Scenario: a completed P2P is reversed and every balance is restored
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234"
    Then the response status is 200
    And the transaction status is COMPLETED
    When operations reverses the transaction
    Then the app shows the transaction as REVERSED
    And no core balance changed
    And the audit trail of the transaction records the states "RECEIVED, VALIDATING, POSTING, COMPLETED, REVERSING, REVERSED"
    And the audit hash chain of the transaction's month verifies

  Scenario: a second reversal of the same transaction moves no money again
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234"
    Then the response status is 200
    When operations reverses the transaction
    Then the app shows the transaction as REVERSED
    When operations reverses the transaction
    Then the orchestrator has handled the reversal request
    And the app shows the transaction as REVERSED
    And no core balance changed
    And the audit trail of the transaction records the states "RECEIVED, VALIDATING, POSTING, COMPLETED, REVERSING, REVERSED"

  Scenario: a reversal the core refuses goes to manual review with an audit entry
    Given the core rejects the next ReverseTransaction with reason INSUFFICIENT_BALANCE
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234"
    Then the response status is 200
    When operations reverses the transaction
    Then the app shows the transaction as MANUAL_REVIEW
    And the core balances changed by:
      | 01711000001 | -1500 |
      | 01711000002 | 1000  |
      | FEE-POOL    | 500   |
    And the audit trail of the transaction records the states "RECEIVED, VALIDATING, POSTING, COMPLETED, REVERSING, MANUAL_REVIEW"
    And the audit trail of the transaction records MANUAL_REVIEW with error code "MW-CORE-INSUFFICIENT_BALANCE"
