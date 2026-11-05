@billpay @mw-app-gateway @mw-adapter-billpay @mw-simulators
Feature: BILL_PAY from the app (bill fetch, then hold, partner, commit or release)
  A customer looks up a bill and pays it. The bill fetch is a synchronous read: mw-app-gateway
  asks mw-adapter-billpay's internal API, which asks the utility aggregator simulator (billagg in
  mw-simulators). The payment runs like a TOPUP: the orchestrator validates, holds the payer's
  funds at the core, asks the aggregator through mw-adapter-billpay, then commits or releases.
  The aggregator simulator: amount due = 100 taka + the bill number's last three digits; bill
  numbers ending 404 are unknown, ending 999 are refused on payment; payment amounts with the
  fraction .13 time out (applied, reply held 30 s, past the adapter's 25 s limit).

  Background:
    Given customer01 is signed in to the app
    And the core balances of these wallets are noted:
      | 01711000001 |
      | SETTLEMENT  |
      | FEE-POOL    |

  Scenario: the customer sees the billers and fetches the amount due on a bill
    When customer01 lists the billers
    Then the response status is 200
    And the billers include DESCO
    When customer01 fetches bill 1234567 of biller DESCO
    Then the response status is 200
    And the amount due is 66700 poisha
    And no core balance changed

  Scenario: an unknown bill number is a mapped aggregator error
    When customer01 fetches bill 1234404 of biller DESCO
    Then the response status is 502
    And the error code is "MW-PTR-BILLAGG-BILL_NOT_FOUND"

  Scenario: an unknown biller is not found
    When customer01 fetches bill 1234567 of biller NOPE
    Then the response status is 404
    And the error code is "MW-VAL-003"

  @mw-orchestrator @mw-core-mock
  Scenario: a paid bill debits the payer once and leaves nothing held
    When customer01 pays 66700 poisha on bill 1234567 of biller DESCO with PIN "1234"
    Then the response status is 200
    And the transaction status is COMPLETED
    And the core balances changed by:
      | 01711000001 | -67000 |
      | SETTLEMENT  | 66700  |
      | FEE-POOL    | 300    |
    And no funds are held on 01711000001
    And the app shows the transaction as COMPLETED

  @mw-orchestrator @mw-core-mock
  Scenario: a biller refusal releases the hold and moves no money
    When customer01 pays 10000 poisha on bill 1234999 of biller DESCO with PIN "1234"
    Then the response status is 502
    And the error code is "MW-PTR-BILLAGG-PAYMENT_REFUSED"
    And the response carries a txnId
    And no core balance changed
    And no funds are held on 01711000001
    And the app shows the transaction as FAILED

  @mw-orchestrator
  Scenario: a bill payment without a biller is refused by the app API
    When customer01 pays 10000 poisha on bill 1234567 without a biller
    Then the response status is 400
    And the error code is "MW-VAL-001"
    And no core balance changed

  # Unknown outcome (spec 7.4): the aggregator answers after the adapter's 25 s limit, the app
  # gets 202 PROCESSING, and the status inquiry 30 s after UNKNOWN finds the applied payment.
  @mw-orchestrator @mw-core-mock @tsq
  Scenario: an aggregator timeout is resolved by status inquiry and the hold is committed
    When customer01 pays 10013 poisha on bill 1234567 of biller DESCO with PIN "1234"
    Then the response status is 202
    And the transaction status is PROCESSING
    And the app shows the transaction as COMPLETED within 120 seconds
    And the core balances changed by:
      | 01711000001 | -10313 |
      | SETTLEMENT  | 10013  |
      | FEE-POOL    | 300    |
    And no funds are held on 01711000001

  @mw-orchestrator @mw-audit-dw
  Scenario: a paid bill leaves an audit trail of the hold path
    When customer01 pays 5000 poisha on bill 1234567 of biller DESCO with PIN "1234"
    Then the response status is 200
    And the audit trail of the transaction records the states "RECEIVED, VALIDATING, HOLDING, PARTNER_PENDING, COMMITTING, COMPLETED"
    And the audit trail of the transaction records the partner call by mw-adapter-billpay
