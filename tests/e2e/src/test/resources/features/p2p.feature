@p2p @mw-app-gateway @mw-orchestrator @mw-core-mock
Feature: P2P send money from the app through APISIX
  A customer sends money to another wallet. The core (mw-core-mock) holds the ledger; its
  balances prove that money moves exactly once (MVP acceptance criterion 2). Every scenario
  uses a fresh Idempotency-Key and compares balances before and after, so reruns are stable.

  Background:
    Given customer01 is signed in to the app
    And the core balances of these wallets are noted:
      | 01711000001 |
      | 01711000002 |
      | FEE-POOL    |

  Scenario: a P2P completes and moves the money once
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234"
    Then the response status is 200
    And the transaction status is COMPLETED
    And the core balances changed by:
      | 01711000001 | -1500 |
      | 01711000002 | 1000  |
      | FEE-POOL    | 500   |
    And the app shows the transaction as COMPLETED

  Scenario Outline: a core rejection (<reason>) moves no money
    When customer01 sends <amount> poisha P2P to 01711000002 with PIN "<pin>"
    Then the response status is 422
    And the error code is "<code>"
    And the response carries a txnId
    And no core balance changed
    And the app shows the transaction as REJECTED

    Examples:
      | reason              | amount | pin  | code                           |
      | wrong PIN           | 1000   | 9999 | MW-CORE-INVALID_PIN            |
      | over per-txn limit  | 600000 | 1234 | MW-CORE-LIMIT_EXCEEDED_PER_TXN |

  Scenario: the same idempotency key sent twice debits once and returns the same txnId
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234"
    And customer01 sends the same request again with the same idempotency key
    Then the response status is 200
    And every response carries the same txnId
    And the transaction status is COMPLETED
    And the core balances changed by:
      | 01711000001 | -1500 |
      | 01711000002 | 1000  |
      | FEE-POOL    | 500   |

  Scenario: the same idempotency key with a different body is refused
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234"
    And customer01 sends 2000 poisha P2P to 01711000002 with PIN "1234" reusing the idempotency key
    Then the response status is 409
    And the error code is "MW-IDEM-001"
    And the core balances changed by:
      | 01711000001 | -1500 |
      | 01711000002 | 1000  |
      | FEE-POOL    | 500   |

  @mw-audit-dw
  Scenario: a completed P2P leaves a verifiable audit trail
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234"
    Then the response status is 200
    And the audit trail of the transaction records the states "RECEIVED, VALIDATING, POSTING, COMPLETED"
    And the audit hash chain of the transaction's month verifies
