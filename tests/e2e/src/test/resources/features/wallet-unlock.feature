@admin @wallet-unlock @mw-admin-api @mw-core-mock @mw-app-gateway @mw-orchestrator
Feature: Ops unlock of a PIN-locked wallet with maker-checker
  Ticket 52 (M2 gate decision): the core locks a wallet after 5 wrong PINs in a row; operations
  unlock it through the admin API. An ops-maker asks, a second operator (ops-checker) approves, and
  only then mw-admin-api sends UnlockWallet to the core, which clears the lock and the failure
  count. customer02 (01711000002) is the payer here so customer01's scenarios are not locked out;
  other scenarios only ever credit 01711000002, which a locked wallet still accepts.

  Scenario: five wrong PINs lock the wallet, an approved unlock lets the next correct-PIN P2P succeed
    Given customer02 is signed in to the app
    And the core shows wallet 01711000002 as ACTIVE
    When customer02 sends 1000 poisha P2P to 01711000001 with PIN "9999"
    Then the error code is "MW-CORE-INVALID_PIN"
    When customer02 sends 1000 poisha P2P to 01711000001 with PIN "9999"
    Then the error code is "MW-CORE-INVALID_PIN"
    When customer02 sends 1000 poisha P2P to 01711000001 with PIN "9999"
    Then the error code is "MW-CORE-INVALID_PIN"
    When customer02 sends 1000 poisha P2P to 01711000001 with PIN "9999"
    Then the error code is "MW-CORE-INVALID_PIN"
    When customer02 sends 1000 poisha P2P to 01711000001 with PIN "9999"
    Then the error code is "MW-CORE-INVALID_PIN"
    And the core shows wallet 01711000002 as LOCKED
    When customer02 sends 1000 poisha P2P to 01711000001 with PIN "1234"
    Then the response status is 422
    And the error code is "MW-CORE-ACCOUNT_LOCKED"
    When ops-e2e-maker01 asks to unlock wallet 01711000002 because "customer identified at the branch"
    Then the change request is PENDING
    When ops-e2e-maker01 approves the change request
    Then the admin API answers 403 with code "MW-AUTH-002"
    And the core shows wallet 01711000002 as LOCKED
    When ops-e2e-checker01 approves the change request
    Then the change request is APPROVED
    And the core shows wallet 01711000002 as ACTIVE within 30 seconds
    When customer02 sends 1000 poisha P2P to 01711000001 with PIN "1234"
    Then the response status is 200
    And the transaction status is COMPLETED
