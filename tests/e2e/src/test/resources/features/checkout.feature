@checkout @mw-checkout @mw-notify @mw-simulators @mw-orchestrator @mw-core-mock
Feature: Web checkout: session, OTP and PIN, MERCHANT_PAY, signed redirect (ticket 31)
  The dev merchant (APISIX hmac-auth consumer merchant_demo, wallet 01911000001 "Demo Shop")
  creates a checkout session; the customer (customer01, wallet 01711000001) opens the hosted page,
  gets an OTP by SMS (OTP priority) and pays with OTP and PIN; the browser goes back to the
  merchant with a result signed with the merchant's secret, which the merchant verifies.
  Session expiry and the OTP limits are covered in mw-checkout's CheckoutFlowIT.

  Background:
    Given the core balances of these wallets are noted:
      | 01711000001 |
      | 01911000001 |

  Scenario: create session, pay with OTP and PIN, signed redirect the merchant verifies
    Given the merchant creates a checkout session for 2500 poisha
    Then the checkout page shows 2500 poisha for Demo Shop with the core's PIN key
    When the customer asks for an OTP for wallet 01711000001
    Then the OTP was sent with OTP priority
    When the customer confirms with a wrong OTP and PIN "1234"
    Then the response status is 401
    And the error code is "MW-AUTH-010"
    And no core balance changed
    When the customer confirms with the OTP and PIN "1234"
    Then the response status is 200
    And the checkout result is PAID
    And the core balances changed by:
      | 01711000001 | -2500 |
      | 01911000001 | 2500  |
    And the browser is redirected to the merchant's ok URL with a PAID result the merchant verifies
    And the merchant sees the session as PAID paid by 0171****001

  Scenario: a payment the core rejects fails the session and sends the browser to the fail URL
    Given the merchant creates a checkout session for 1500 poisha
    And the core rejects the next ValidateTransaction with reason INSUFFICIENT_BALANCE
    When the customer asks for an OTP for wallet 01711000001
    And the customer confirms with the OTP and PIN "1234"
    Then the response status is 200
    And the checkout result is FAILED
    And no core balance changed
    And the browser is redirected to the merchant's fail URL with a FAILED result the merchant verifies
    And the merchant sees the session as FAILED

  # Ticket 32. The dev stack compresses the IPN retry schedule to 10s, 20s, 30s, 1m, 2m (compose.yml
  # MW_CHECKOUT_IPN_RETRY_DELAYS); the spec schedule 1m, 5m, 30m, 2h, 6h is IpnRetryScheduleTest / IpnDeliveryIT.
  @ipn
  Scenario: the merchant's IPN endpoint fails twice, then the signed IPN is delivered once
    Given the merchant creates a checkout session for 1200 poisha
    And the merchant's IPN endpoint fails the next 2 IPNs of the session with status 503
    When the customer asks for an OTP for wallet 01711000001
    And the customer confirms with the OTP and PIN "1234"
    Then the response status is 200
    And the checkout result is PAID
    And within 90 seconds the merchant has received the session's IPN 3 times, answering "503, 503, 200"
    And the merchant accepted exactly one IPN of the session, a PAID result with a valid signature
    And the merchant receives no further IPN of the session within 15 seconds

  Scenario: a merchant request with a bad HMAC signature is refused at the edge
    When the merchant creates a checkout session signed with a wrong secret
    Then the response status is 401
    And the error code is "MW-AUTH-001"

  Scenario: a merchant request without a signature is refused at the edge
    When the merchant creates a checkout session without a signature
    Then the response status is 401
    And the error code is "MW-AUTH-001"
