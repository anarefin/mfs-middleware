@admin @partner-config @mw-admin-api @mw-adapter-telco @mw-orchestrator @mw-core-mock @mw-simulators @topup
Feature: Partner config through maker-checker, without an adapter restart
  Ticket 38 (spec 15.3, 20.4): an operator (ops-e2e-maker01) asks to disable the telco partner or
  change its time limit; a second operator (ops-e2e-checker01) approves. mw-admin-api then publishes
  the partner's full config on the compacted topic mw.config.changed.v1 and mw-adapter-telco swaps it
  in while it runs. The telco partner of the dev stack is the seed instance `ocs`, which serves every
  operator (gp, robi, ...). A disabled partner refuses new top-ups fast, before anything is sent, with
  the mapped MW-SYS-PARTNER_DISABLED. Every scenario puts the partner back to its seed afterwards.

  Background:
    Given customer01 is signed in to the app
    And the core balances of these wallets are noted:
      | 01711000001 |
      | SETTLEMENT  |
      | FEE-POOL    |

  Scenario: a disabled telco partner refuses top-ups, and works again once enabled
    When the operators disable partner ocs
    Then the partner change has reached the adapters
    When customer01 tops up 2000 poisha gp PREPAID airtime on 01711000002 with PIN "1234"
    Then the error code is "MW-SYS-PARTNER_DISABLED"
    And the app shows the transaction as FAILED
    And no core balance changed
    And no funds are held on 01711000001
    When the operators enable partner ocs
    Then the partner change has reached the adapters
    When customer01 tops up 2000 poisha gp PREPAID airtime on 01711000002 with PIN "1234"
    Then the response status is 200
    And the transaction status is COMPLETED

  # The OCS takes 6 s and applies the top-up: within the seed's 20 s limit that is a plain
  # success; with 3 s the adapter gives up first (UNKNOWN), and status inquiry later finds it applied.
  @tsq @mw-audit-dw
  Scenario: a lowered time limit applies to the next call
    Given the OCS answers every top-up after 6 seconds
    When the operators set the time limit of partner ocs to 3000 ms
    Then the partner change has reached the adapters
    When customer01 tops up 2000 poisha gp PREPAID airtime on 01711000002 with PIN "1234"
    Then the OCS behaves normally again
    And the app shows the transaction as COMPLETED within 120 seconds
    And the audit trail of the transaction records the states "RECEIVED, VALIDATING, HOLDING, PARTNER_PENDING, UNKNOWN, COMMITTING, COMPLETED"
