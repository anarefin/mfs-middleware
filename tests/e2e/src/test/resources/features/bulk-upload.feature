@bulk @mw-portal-api
Feature: Org portal bulk upload with maker-checker through APISIX
  An org maker uploads a bulk disbursement CSV (at most 10,000 rows); a different user of the
  same org with the checker role approves or rejects it (spec 9.4, ticket 33). Payout of an
  approved batch is ticket 34. Users (realm portals): org-maker01 and org-checker01 of ORG001,
  org-makerchecker01 of ORG001 with both roles, org-checker02 of ORG002.

  Scenario: a valid file is stored awaiting approval and a different checker approves it
    When org-maker01 uploads a bulk file of 3 rows
    Then the response status is 201
    And the batch status is PENDING_APPROVAL
    And the batch has 3 rows totalling 300 poisha
    When org-checker01 approves the batch
    Then the response status is 200
    And the batch status is APPROVED
    And the batch was decided by org-checker01
    And within 60 seconds the audit trail of the batch shows:
      | action              | actor         |
      | bulk.batch.uploaded | org-maker01   |
      | bulk.batch.approved | org-checker01 |

  Scenario: a checker rejects a batch
    Given org-maker01 has uploaded a bulk file of 2 rows
    When org-checker01 rejects the batch because "wrong payroll month"
    Then the response status is 200
    And the batch status is REJECTED
    When org-checker01 approves the batch
    Then the response status is 409
    And the error code is "MW-VAL-032"
    And within 60 seconds the audit trail of the batch shows:
      | action              | actor         |
      | bulk.batch.uploaded | org-maker01   |
      | bulk.batch.rejected | org-checker01 |

  Scenario: the maker cannot approve their own batch, even with the checker role
    Given org-makerchecker01 has uploaded a bulk file of 2 rows
    When org-makerchecker01 approves the batch
    Then the response status is 403
    And the error code is "MW-AUTH-030"
    When org-checker01 looks at the batch
    Then the batch status is PENDING_APPROVAL

  Scenario: another org cannot see or approve the batch
    Given org-maker01 has uploaded a bulk file of 1 rows
    When org-checker02 approves the batch
    Then the response status is 404
    And the error code is "MW-VAL-003"

  Scenario: the org lists its batches by any batch status, the M3 ones included
    # EXPIRED (no decision within 72 h) and COMPLETED_WITH_EXCEPTIONS (a MANUAL_REVIEW or
    # UNRESOLVED row) need hours or core faults to reach; BulkLifecycleIT drives them on a test clock.
    When org-checker01 lists the org's batches with status EXPIRED
    Then the response status is 200
    And every listed batch has status EXPIRED
    When org-checker01 lists the org's batches with status COMPLETED_WITH_EXCEPTIONS
    Then the response status is 200
    And every listed batch has status COMPLETED_WITH_EXCEPTIONS
    When org-checker01 lists the org's batches with status NOPE
    Then the response status is 400
    And the error code is "MW-VAL-001"

  Scenario: 10,000 rows are accepted
    When org-maker01 uploads a bulk file of 10000 rows
    Then the response status is 201
    And the batch has 10000 rows totalling 1000000 poisha

  Scenario: 10,001 rows are rejected
    When org-maker01 uploads a bulk file of 10001 rows
    Then the response status is 400
    And the error code is "MW-VAL-030"

  Scenario: malformed rows are reported per line and nothing is stored
    When org-maker01 uploads a bulk file with these lines:
      | msisdn,amount,reference |
      | 01711000001,1.00,E2E-A  |
      | 0171,1.00,E2E-B         |
      | 01711000003,0,E2E-C     |
      | 01711000004,1.00,E2E-A  |
    Then the response status is 400
    And the error code is "MW-VAL-031"
    And the error details name these fields:
      | line 3: msisdn    |
      | line 4: amount    |
      | line 5: reference |
