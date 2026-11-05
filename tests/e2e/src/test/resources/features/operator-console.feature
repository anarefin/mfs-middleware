@admin @operator @mw-admin-api @mw-orchestrator @mw-core-mock @mw-audit-dw @mw-app-gateway
Feature: An operator finds a transaction, reads its timeline and audit, and reverses it with a second approval
  Acceptance criterion 4 (ticket 43; admin API tickets 36, 37): an operator of realm ops searches
  the transactions by txnId or by the customer's MSISDN (any accepted form), opens the transaction
  and sees its saga timeline (state changes and core commands, from mw-orchestrator) and its audit
  trail (from mw-audit-dw), masked. A reversal asked by one operator (ops-e2e-maker01) runs only
  after a second one (ops-e2e-checker01) approves it, and every balance comes back.

  Background:
    Given customer01 is signed in to the app
    And the core balances of these wallets are noted:
      | 01711000001 |
      | 01711000002 |
      | FEE-POOL    |

  @p2p
  Scenario: an operator finds a P2P by txnId and by MSISDN and sees its timeline and audit trail
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234"
    Then the response status is 200
    When ops-e2e-checker01 searches the transactions by txnId
    Then the search lists only the transaction
    And the search finds the transaction as COMPLETED with the initiator masked as 0171****001
    When ops-e2e-checker01 searches the P2P transactions by MSISDN +8801711000001
    Then the search finds the transaction as COMPLETED with the initiator masked as 0171****001
    When ops-e2e-checker01 opens the transaction in the admin view
    Then the admin timeline shows the states "VALIDATING, POSTING, COMPLETED"
    And the admin timeline shows the core command PostTransfer
    And the admin audit trail shows the states "RECEIVED, VALIDATING, POSTING, COMPLETED"

  @reversal
  Scenario: an operator finds a P2P by MSISDN and reverses it once a second operator approves
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234"
    Then the response status is 200
    When ops-e2e-maker01 searches the P2P transactions by MSISDN 01711000001
    Then the search finds the transaction as COMPLETED with the initiator masked as 0171****001
    When ops-e2e-maker01 asks to REVERSE the transaction because "customer reported a wrong number"
    Then the change request is PENDING
    And the admin view shows the transaction as COMPLETED within 5 seconds
    When ops-e2e-checker01 approves the change request
    Then the change request is APPROVED
    And the admin view shows the transaction as REVERSED within 30 seconds
    And no core balance changed
    When ops-e2e-checker01 searches the transactions by txnId
    Then the search finds the transaction as REVERSED with the initiator masked as 0171****001
    And the admin audit trail of the transaction records the APPROVED change request
    And the admin audit trail shows the states "RECEIVED, VALIDATING, POSTING, COMPLETED, REVERSING, REVERSED"
