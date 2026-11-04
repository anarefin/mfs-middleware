@bulk @mw-portal-api
Feature: Org portal bulk payout: throttled child txns, pause and resume, result file
  An approved batch is paid out row by row: mw-portal-api streams one BULK_DISBURSE child txn per
  row to the orchestrator at the configured rate (mw.portal-api.bulk.dispatch.rate-per-second,
  default 50/s), each with a txnId and idempotency key derived from the batch and the row, and
  settles the rows from their status records (spec 9.4, ticket 34). ORG001 pays from the core
  mock's org wallet 01311000001 (no PIN, no fee); payout files pay the seeded customers
  01711000001..01711001000 in turn.

  Background:
    Given no bulk batch of org-checker01's org is still running

  Scenario: a 10,000-row batch completes, the result file matches and the org balance is correct
    Given org-maker01 has uploaded a payout file of 10000 rows of 1.00 taka
    And the core balances of these wallets are noted:
      | 01311000001 |
      | 01711000001 |
      | 01711001000 |
    When org-checker01 approves the batch
    Then the response status is 200
    And within 15 minutes org-checker01 sees the batch COMPLETED
    And the batch progress shows 10000 completed, 0 failed and 1000000 poisha paid
    And org-maker01's result file lists all 10000 rows as COMPLETED, each with its own txnId
    And the core balances changed by:
      | 01311000001 | -1000000 |
      | 01711000001 | 1000     |
      | 01711001000 | 1000     |

  Scenario: a row the core refuses is reported in the result file and costs the org nothing
    Given org-maker01 has uploaded a bulk file with these lines:
      | msisdn,amount,reference |
      | 01711000002,2.00,OK-1   |
      | 01710000999,3.00,NOPE-2 |
    And the core balances of these wallets are noted:
      | 01311000001 |
      | 01711000002 |
    When org-checker01 approves the batch
    Then within 2 minutes org-checker01 sees the batch COMPLETED
    And the batch progress shows 1 completed, 1 failed and 200 poisha paid
    And org-maker01's result file shows:
      | reference | status    | errorCode                       |
      | OK-1      | COMPLETED |                                 |
      | NOPE-2    | REJECTED  | MW-CORE-CREDIT_WALLET_NOT_FOUND |
    And the core balances changed by:
      | 01311000001 | -200 |
      | 01711000002 | 200  |

  Scenario: a paused batch sends nothing until it is resumed, then completes
    Given org-maker01 has uploaded a payout file of 2000 rows of 1.00 taka
    And the core balances of these wallets are noted:
      | 01311000001 |
    When org-checker01 approves the batch
    And org-checker01 pauses the batch
    Then the response status is 200
    And the batch status is PAUSED
    And org-checker01 sees the batch send no more rows
    When org-maker01 pauses the batch
    Then the response status is 403
    When org-checker01 resumes the batch
    Then the response status is 200
    And the batch status is EXECUTING
    And within 5 minutes org-checker01 sees the batch COMPLETED
    And the batch progress shows 2000 completed, 0 failed and 200000 poisha paid
    And the core balances changed by:
      | 01311000001 | -200000 |
