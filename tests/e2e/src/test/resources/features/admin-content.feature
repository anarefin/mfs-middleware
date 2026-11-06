@admin @mw-admin-api @mw-ussd @mw-notify @mw-simulators
Feature: USSD menu and SMS template changes with maker-checker in the admin API
  Ticket 39 (spec 9.2, 10.3, 15.2, 15.3): an operator (ops-e2e-maker01, ops-maker) proposes a new
  version of a USSD menu or an SMS template; nothing changes until a second operator
  (ops-e2e-checker01, ops-checker) approves it. The approval publishes the version on
  mw.config.changed.v1: mw-ussd activates the menu for the MNO at its go-live, and a session that
  is already running finishes on the version it started with; mw-notify renders the next
  notification with the new template. {token} is a random marker of the scenario, so a re-run
  never reads an earlier run's text. The menu scenario uses bl, which no other feature walks; the
  template scenario puts the seeded English text back at the end.

  Scenario: an approved menu text change shows in the next USSD session but not in a running one
    Given 01711000001 has a USSD session open on bl after dialling *268#
    When ops-e2e-maker01 proposes the main USSD menu for bl with the title "Bits {token}"
    And ops-e2e-checker01 approves the change request
    Then the change request is APPROVED
    And a new USSD session of 01711000003 on bl starts with "Bits {token}\n1 সেন্ড মানি" within 30 seconds
    When the open USSD session continues with "9"
    Then the open USSD session shows the English main menu without "Bits {token}"

  @p2p @mw-orchestrator @mw-core-mock
  Scenario: an approved SMS template change is used for the next notification
    When ops-e2e-maker01 proposes the SMS template TXN_P2P_RECEIVED in en as "Bits {token}: you got Tk {{amount}} from {{counterparty}}. TrxID {{txnId}}"
    And ops-e2e-checker01 approves the change request
    Then the change request is APPROVED
    When the services have had 5 seconds to take the published version
    And 01711000001 dials *268# on robi and enters "9, 1, 01711000002, 10, 1, 1234"
    Then the USSD session ends with "Tk 10.00 sent to 01711000002. TxnID "
    And 01711000002 gets an SMS starting with "Bits {token}: you got Tk 10.00 from 0171****001. TrxID " within 30 seconds
    When ops-e2e-maker01 proposes the SMS template TXN_P2P_RECEIVED in en as "You have received Tk {{amount}} from {{counterparty}}. TrxID {{txnId}}"
    And ops-e2e-checker01 approves the change request
    Then the change request is APPROVED
