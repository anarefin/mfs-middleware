@billpay @callback @mw-app-gateway @mw-adapter-billpay @mw-simulators @mw-orchestrator @mw-core-mock @mw-audit-dw
Feature: BILL_PAY confirmed by the aggregator's callback (ticket 22, spec 11.2 inbound path)
  The aggregator simulator (billagg) in callback-only mode accepts every payment with 202 PENDING
  and posts the outcome, signed hmac-sha256, to /partner/billagg/callback/payments through APISIX
  (http://apisix:9080, ip allow-list, body cap, quota). mw-adapter-billpay verifies the
  signature, deduplicates on partner, reference and outcome, writes the event to
  mw.partner.inbound.v1 and only then answers 200; the orchestrator resolves PARTNER_PENDING or
  UNKNOWN from it. A callback for a transaction already resolved changes nothing and is
  recorded on the audit trail (partner.callback.late, or txn.outcome.conflict when it
  contradicts the resolution).

  Background:
    Given customer01 is signed in to the app
    And the core balances of these wallets are noted:
      | 01711000001 |
      | SETTLEMENT  |
      | FEE-POOL    |

  Scenario: a callback-only payment is completed by the aggregator's callback
    Given the aggregator confirms payments only by callback
    When customer01 pays 66700 poisha on bill 1234567 of biller DESCO with PIN "1234"
    Then the app shows the transaction as COMPLETED within 25 seconds
    And the core balances changed by:
      | 01711000001 | -67000 |
      | SETTLEMENT  | 66700  |
      | FEE-POOL    | 300    |
    And no funds are held on 01711000001
    And the audit trail of the transaction records the states "RECEIVED, VALIDATING, HOLDING, PARTNER_PENDING, COMMITTING, COMPLETED" once each
    And the audit trail of the transaction records no late callback

  Scenario: a refusal reported by callback releases the hold
    Given the aggregator confirms payments only by callback
    When customer01 pays 10000 poisha on bill 1234999 of biller DESCO with PIN "1234"
    Then the app shows the transaction as FAILED with error "MW-PTR-BILLAGG-PAYMENT_REFUSED" within 25 seconds
    And no core balance changed
    And no funds are held on 01711000001

  Scenario: a duplicate callback has no second effect
    Given the aggregator confirms payments only by callback, sending each callback twice
    When customer01 pays 5100 poisha on bill 1234567 of biller DESCO with PIN "1234"
    Then the app shows the transaction as COMPLETED within 25 seconds
    When the aggregator replays the transaction's callback
    Then the replayed callback was acknowledged
    And the app shows the transaction as COMPLETED
    And the core balances changed by:
      | 01711000001 | -5400 |
      | SETTLEMENT  | 5100  |
      | FEE-POOL    | 300   |
    And the audit trail of the transaction records the states "RECEIVED, VALIDATING, HOLDING, PARTNER_PENDING, COMMITTING, COMPLETED" once each
    And the audit trail of the transaction records no late callback

  Scenario: a stale PENDING callback after the outcome changes nothing
    Given the aggregator confirms payments only by callback, out of order
    When customer01 pays 5200 poisha on bill 1234567 of biller DESCO with PIN "1234"
    Then the app shows the transaction as COMPLETED within 25 seconds
    And the audit trail of the transaction records a late PENDING callback within 30 seconds
    And the app shows the transaction as COMPLETED
    And the core balances changed by:
      | 01711000001 | -5500 |
      | SETTLEMENT  | 5200  |
      | FEE-POOL    | 300   |

  # No callback within the 30 s partner deadline: UNKNOWN, the status inquiry 30 s later finds
  # the payment applied and the hold is committed; the callback arrives 90 s after the payment.
  @tsq
  Scenario: a late callback after the status inquiry resolved the payment is recorded and changes nothing
    Given the aggregator confirms payments only by callback, 90 seconds late
    When customer01 pays 5300 poisha on bill 1234567 of biller DESCO with PIN "1234"
    Then the app shows the transaction as COMPLETED within 120 seconds
    And the audit trail of the transaction records a late SUCCESS callback within 90 seconds
    And the app shows the transaction as COMPLETED
    And the core balances changed by:
      | 01711000001 | -5600 |
      | SETTLEMENT  | 5300  |
      | FEE-POOL    | 300   |
    And no funds are held on 01711000001

  Scenario: a late callback contradicting the resolution is recorded as a conflict and changes nothing
    Given the aggregator confirms payments only by callback
    When customer01 pays 5400 poisha on bill 1234567 of biller DESCO with PIN "1234"
    Then the app shows the transaction as COMPLETED within 25 seconds
    When the aggregator calls back FAILED with code 3003 for the transaction
    Then the callback is answered 200
    And the audit trail of the transaction records a conflicting FAILED callback within 30 seconds
    And the app shows the transaction as COMPLETED
    And the core balances changed by:
      | 01711000001 | -5700 |
      | SETTLEMENT  | 5400  |
      | FEE-POOL    | 300   |

  Scenario: a callback with a bad signature is rejected and changes nothing
    Given the aggregator confirms payments only by callback
    When customer01 pays 5500 poisha on bill 1234567 of biller DESCO with PIN "1234"
    Then the app shows the transaction as COMPLETED within 25 seconds
    When someone calls back FAILED with code 3003 for the transaction with a bad signature
    Then the callback is answered 401 with error code "MW-AUTH-001"
    And the audit trail of the transaction records no late callback
    And the app shows the transaction as COMPLETED
