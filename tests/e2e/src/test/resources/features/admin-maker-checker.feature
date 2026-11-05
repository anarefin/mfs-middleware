@admin @mw-admin-api @mw-orchestrator @mw-core-mock @mw-audit-dw
Feature: Operator actions with maker-checker in the admin API
  Acceptance criterion 4 (ticket 37, spec 15.3): an operator (ops-e2e-maker01, ops-maker) asks for an
  action on a transaction; nothing happens until a second operator (ops-e2e-checker01, ops-checker)
  approves it. On approval mw-admin-api sends the action to mw-orchestrator (mw.txn.action.v1, or a
  REVERSAL on mw.txn.request.v1), which moves the transaction; a rejected request changes nothing.
  Every decision is in the transaction's audit trail with before and after. Tokens of realm ops
  need the TOTP code; the dev-only e2e operators have a seeded secret (KC_OPS_TEST_TOTP_SECRET).

  Background:
    Given customer01 is signed in to the app

  @topup @mw-adapter-telco @mw-simulators
  Scenario: a top-up in MANUAL_REVIEW is force-committed after a second operator approves
    Given the core balances of these wallets are noted:
      | 01711000001 |
      | SETTLEMENT  |
      | FEE-POOL    |
    And the core rejects the next CommitHold with reason INJECTED_REJECT
    When customer01 tops up 2000 poisha gp PREPAID airtime on 01711000002 with PIN "1234"
    Then the app shows the transaction as MANUAL_REVIEW within 30 seconds
    When ops-e2e-maker01 asks to FORCE_COMMIT the transaction because "OCS confirmed the top-up"
    Then the change request is PENDING
    When ops-e2e-maker01 approves the change request
    Then the admin API answers 403 with code "MW-AUTH-002"
    And the admin view shows the transaction as MANUAL_REVIEW within 5 seconds
    When ops-e2e-checker01 approves the change request
    Then the change request is APPROVED
    And the app shows the transaction as COMPLETED within 30 seconds
    And the core balances changed by:
      | 01711000001 | -2200 |
      | SETTLEMENT  | 2000  |
      | FEE-POOL    | 200   |
    And no funds are held on 01711000001
    And the admin audit trail of the transaction records the APPROVED change request

  @reversal @mw-app-gateway
  Scenario: a completed P2P is reversed after a second operator approves
    Given the core balances of these wallets are noted:
      | 01711000001 |
      | 01711000002 |
      | FEE-POOL    |
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234"
    Then the response status is 200
    When ops-e2e-maker01 asks to REVERSE the transaction because "customer complaint, wrong number"
    And ops-e2e-checker01 approves the change request
    Then the change request is APPROVED
    And the app shows the transaction as REVERSED within 30 seconds
    And no core balance changed
    And the admin audit trail of the transaction records the APPROVED change request

  @reversal @mw-app-gateway
  Scenario: a rejected reversal changes nothing
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234"
    Then the response status is 200
    When ops-e2e-maker01 asks to REVERSE the transaction because "customer complaint"
    And ops-e2e-checker01 rejects the change request because "transfer was correct"
    Then the change request is REJECTED
    And the admin audit trail of the transaction records the REJECTED change request
    And the admin view shows the transaction as COMPLETED within 5 seconds
    When ops-e2e-checker01 approves the change request
    Then the admin API answers 409 with code "MW-VAL-033"
