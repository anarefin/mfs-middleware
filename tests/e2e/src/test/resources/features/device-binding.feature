@device @mw-app-gateway @mw-orchestrator @mw-core-mock
Feature: Sensitive app requests come only from a bound device, signed, never replayed
  The app binds its device (EC P-256 key) to the wallet, then signs every transaction submit over
  method, path, body hash, timestamp and a one-time nonce (spec 9.1, 18.6; ticket 29). The suite's
  AppApi binds a device per user on first use and signs every submit (support/Devices).

  Background:
    Given customer01 is signed in to the app
    And the core balances of these wallets are noted:
      | 01711000001 |
      | 01711000002 |
      | FEE-POOL    |

  Scenario: a P2P with a valid signature passes
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234"
    Then the response status is 200
    And the transaction status is COMPLETED
    And the core balances changed by:
      | 01711000001 | -1500 |
      | 01711000002 | 1000  |
      | FEE-POOL    | 500   |

  Scenario: a replayed signed request is rejected and moves no money again
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234"
    And customer01 replays the last signed request
    Then the response status is 401
    And the error code is "MW-AUTH-006"
    And the core balances changed by:
      | 01711000001 | -1500 |
      | 01711000002 | 1000  |
      | FEE-POOL    | 500   |

  Scenario: a body changed after signing is rejected
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234" with the body changed after signing
    Then the response status is 401
    And the error code is "MW-AUTH-004"
    And no core balance changed
