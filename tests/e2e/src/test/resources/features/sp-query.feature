@sp-query @mw-portal-api
Feature: SP transaction query on the org portal
  An org (the service provider) looks up its own transactions through the portal edge route
  /portal/org/v1/transactions (ticket 35, WP22). mw-portal-api asks mw-orchestrator's internal
  query API, which is not on the edge, always for the token's own org; customer MSISDNs are masked.
  The org sees what it initiated (role INITIATOR) and what it collects as payee (role PAYEE): in
  the dev stack ORG002 collects for biller DESCO and merchant wallet 01911000001
  (mw-orchestrator's org-payees config).

  Background:
    Given no bulk batch of org-checker01's org is still running

  Scenario: an org sees its batch children with their timeline, another org sees none of them
    Given org-maker01 has uploaded a bulk file with these lines:
      | msisdn,amount,reference |
      | 01711000004,1.00,SPQ-1  |
    When org-checker01 approves the batch
    Then within 2 minutes org-checker01 sees the batch COMPLETED
    When org-checker01 lists the org's transactions of the batch
    Then the response status is 200
    And the transaction list has 1 COMPLETED BULK_DISBURSE transaction paying 0171****004
    And every listed transaction is tagged INITIATOR
    When org-maker01 opens the first listed transaction
    Then the response status is 200
    And the timeline ends "RECEIVED -> VALIDATING -> POSTING -> COMPLETED"
    When org-checker02 opens the first listed transaction
    Then the response status is 404
    And the error code is "MW-VAL-003"
    When org-checker02 lists the org's transactions of the batch
    Then the response status is 404

  @mw-app-gateway @mw-orchestrator @mw-adapter-billpay @mw-simulators @mw-core-mock
  Scenario: the org a biller belongs to sees the bill payments it collects, tagged PAYEE
    Given customer01 is signed in to the app
    When customer01 pays 66700 poisha on bill 1234567 of biller DESCO with PIN "1234"
    Then the response status is 200
    And the transaction status is COMPLETED
    When org-checker02 lists the org's transactions
    Then the response status is 200
    And the org's transaction list has the transaction tagged PAYEE
    When org-checker02 opens the transaction
    Then the response status is 200
    And the opened transaction is tagged PAYEE
    And the timeline ends "COMMITTING -> COMPLETED"
    When org-checker01 lists the org's transactions
    Then the org's transaction list does not have the transaction
    When org-checker01 opens the transaction
    Then the response status is 404

  Scenario: a portal user without an org role may not query
    When sp-viewer01 lists the org's transactions
    Then the response status is 403
