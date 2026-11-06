@ussd @smpp @mw-ussd @mw-simulators @mw-orchestrator @mw-core-mock
Feature: USSD over SMPP for GP (SmppUssdTransport)
  GP's USSD runs over SMPP (spec 9.2, WP19): mw-ussd holds transceiver binds to the USSD gateway
  simulator, which plays the subscriber with deliver_sm (ussd_service_op, its_session_info) and
  reads each screen from mw-ussd's submit_sm. The menu walk is the same as over HTTP (robi in
  ussd.feature); only the MNO's transport configuration differs. Bind handling: enquire_link,
  reconnect after a dropped bind, and throttling errors retried after a delay.

  Background:
    Given mw-ussd is bound to the SMPP USSD gateway
    And the core balances of these wallets are noted:
      | 01711000001 |
      | 01711000002 |
      | FEE-POOL    |

  Scenario: a P2P over SMPP USSD walks the same menu and moves the money once
    When 01711000001 dials *268# on gp and enters "9, 1, 01711000002, 10, 1, 1234"
    Then the first USSD screens were:
      | Bits\n1 সেন্ড মানি\n2 ক্যাশ আউট\n3 মোবাইল রিচার্জ\n4 বিল পে\n9 English |
      | Bits\n1 Send Money\n2 Cash Out\n3 Mobile Recharge\n4 Pay Bill\n9 বাংলা |
      | Enter recipient Bits number                                           |
      | Enter amount (Tk)                                                     |
      | Send Tk 10.00 to 01711000002?\n1 Confirm\n2 Cancel                    |
      | Enter your PIN                                                        |
    And the USSD session ends with "Tk 10.00 sent to 01711000002. TxnID "
    And the core balances changed by:
      | 01711000001 | -1500 |
      | 01711000002 | 1000  |
      | FEE-POOL    | 500   |

  Scenario: a bad number is asked again over SMPP and no money moves
    When 01711000001 dials *268# on gp and enters "9, 1, 12345"
    Then the USSD session is still open on "Enter a valid mobile number.\nEnter recipient Bits number"
    And no core balance changed

  Scenario: the binds are kept alive with enquire_link
    Then mw-ussd keeps its SMPP binds alive with enquire_link

  Scenario: after the gateway drops the binds mw-ussd reconnects and USSD works again
    When the SMPP USSD gateway drops every bind
    Then mw-ussd binds to the SMPP USSD gateway again
    When 01711000001 dials *268# on gp and enters "9, 1"
    Then the USSD session is still open on "Enter recipient Bits number"

  Scenario: throttling, queue full and generic_nack are retried after a delay
    Given the SMPP USSD gateway refuses the next screens with "throttled, queue full, generic_nack"
    When 01711000001 dials *268# on gp and enters "9, 1"
    Then the USSD session is still open on "Enter recipient Bits number"
    And the SMPP USSD gateway refused 3 screens
