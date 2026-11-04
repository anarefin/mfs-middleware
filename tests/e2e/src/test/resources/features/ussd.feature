@ussd @mw-ussd @mw-simulators @mw-orchestrator @mw-core-mock
Feature: P2P send money over USSD (HTTP transport)
  A feature-phone customer sends money through the USSD menu (spec 9.2). The USSD gateway
  simulator plays the MNO (robi, transport HTTP) and POSTs every step to mw-ussd through APISIX;
  mw-ussd encrypts the PIN with the core's public key and submits a USSD-channel P2P. The core's
  balances prove that money moves exactly once. Amounts on USSD are in taka (10 Tk = 1000 poisha).

  Background:
    Given the core balances of these wallets are noted:
      | 01711000001 |
      | 01711000002 |
      | FEE-POOL    |

  Scenario: a P2P over USSD in English completes and moves the money once
    When 01711000001 dials *268# on robi and enters "9, 1, 01711000002, 10, 1, 1234"
    Then the first USSD screens were:
      | upay\n1 সেন্ড মানি\n2 ক্যাশ আউট\n3 মোবাইল রিচার্জ\n4 বিল পে\n9 English |
      | upay\n1 Send Money\n2 Cash Out\n3 Mobile Recharge\n4 Pay Bill\n9 বাংলা |
      | Enter recipient upay number                          |
      | Enter amount (Tk)                                    |
      | Send Tk 10.00 to 01711000002?\n1 Confirm\n2 Cancel   |
      | Enter your PIN                                       |
    And the USSD session ends with "Tk 10.00 sent to 01711000002. TxnID "
    And the core balances changed by:
      | 01711000001 | -1500 |
      | 01711000002 | 1000  |
      | FEE-POOL    | 500   |

  Scenario: a P2P over USSD in Bangla completes and moves the money once
    When 01711000001 dials *268# on robi and enters "1, 01711000002, 10, 1, 1234"
    Then the USSD session ends with "01711000002 নম্বরে 10.00 টাকা পাঠানো হয়েছে।"
    And the core balances changed by:
      | 01711000001 | -1500 |
      | 01711000002 | 1000  |
      | FEE-POOL    | 500   |

  Scenario: a bad number is refused and asked again, and no money moves
    When 01711000001 dials *268# on robi and enters "9, 1, 12345"
    Then the USSD session is still open on "Enter a valid mobile number.\nEnter recipient upay number"
    And no core balance changed

  Scenario: a wrong PIN is refused by the core and no money moves
    When 01711000001 dials *268# on robi and enters "9, 1, 01711000002, 10, 1, 9999"
    Then the USSD session ends with "Send Money failed: "
    And no core balance changed
