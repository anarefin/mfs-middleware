@ussd @mw-ussd @mw-simulators @mw-orchestrator @mw-core-mock
Feature: Cash out, top-up and bill pay over USSD (HTTP transport)
  The other MVP flows of the USSD menu (ticket 28). The USSD gateway simulator plays the MNO
  (robi, transport HTTP). Top-up of the own number goes to the dialling MNO's operator, of another
  number to the operator chosen from the list; bill pay lists the billers of mw-adapter-billpay
  (sorted by code: 1 DESCO), fetches the amount due (simulator: 100 taka + the bill number's last
  three digits) and pays it. Amounts on USSD are in taka.

  Background:
    Given the core balances of these wallets are noted:
      | 01711000001 |
      | 01811000001 |
      | SETTLEMENT  |
      | FEE-POOL    |

  @topup @mw-adapter-telco
  Scenario: a top-up of the own number over USSD in English completes
    When 01711000001 dials *268# on robi and enters "9, 3, 1, 20, 1, 1234"
    Then the first USSD screens were:
      | upay\n1 Send Money\n2 Cash Out\n3 Mobile Recharge\n4 Pay Bill\n9 বাংলা |
      | Mobile Recharge\n1 Own number\n2 Other number                          |
      | Enter recharge amount (Tk)                                             |
      | Recharge Tk 20.00 to 01711000001?\n1 Confirm\n2 Cancel                 |
      | Enter your PIN                                                         |
    And the USSD session ends with "Tk 20.00 recharged to 01711000001. TxnID "
    And the core balances changed by:
      | 01711000001 | -2200 |
      | SETTLEMENT  | 2000  |
      | FEE-POOL    | 200   |
    And no funds are held on 01711000001

  @topup @mw-adapter-telco
  Scenario: a top-up of another number over USSD in Bangla completes
    When 01711000001 dials *268# on robi and enters "3, 2, 01711000002, 1, 20, 1, 1234"
    Then the USSD session ends with "01711000002 নম্বরে 20.00 টাকা রিচার্জ হয়েছে।"
    And the core balances changed by:
      | 01711000001 | -2200 |
      | SETTLEMENT  | 2000  |
      | FEE-POOL    | 200   |

  @topup @mw-adapter-telco
  Scenario: an operator refusal over USSD moves no money
    When 01711000001 dials *268# on robi and enters "9, 3, 2, 01711000999, 1, 20, 1, 1234"
    Then the USSD session ends with "Recharge failed: "
    And no core balance changed
    And no funds are held on 01711000001

  @billpay @mw-adapter-billpay
  Scenario: a bill pay over USSD in English fetches the bill and pays it
    When 01711000001 dials *268# on robi and enters "9, 4, 1, 1234567, 1, 1234"
    Then the first USSD screens were:
      | upay\n1 Send Money\n2 Cash Out\n3 Mobile Recharge\n4 Pay Bill\n9 বাংলা |
      | Select biller\n1 DESCO\n2 DPDC\n3 TITAS\n4 WASA                        |
      | Enter DESCO bill number                                                |
      | Pay Tk 667.00 for DESCO bill 1234567?\n1 Confirm\n2 Cancel             |
      | Enter your PIN                                                         |
    And the USSD session ends with "Tk 667.00 paid for DESCO bill 1234567. TxnID "
    And the core balances changed by:
      | 01711000001 | -67000 |
      | SETTLEMENT  | 66700  |
      | FEE-POOL    | 300    |
    And no funds are held on 01711000001

  @billpay @mw-adapter-billpay
  Scenario: a bill pay over USSD in Bangla completes
    When 01711000001 dials *268# on robi and enters "4, 1, 1234567, 1, 1234"
    Then the USSD session ends with "DESCO বিল 1234567: 667.00 টাকা দেওয়া হয়েছে।"
    And the core balances changed by:
      | 01711000001 | -67000 |
      | SETTLEMENT  | 66700  |
      | FEE-POOL    | 300    |

  @billpay @mw-adapter-billpay
  Scenario: an unknown bill number is refused and asked again, and no money moves
    When 01711000001 dials *268# on robi and enters "9, 4, 1, 1234404"
    Then the USSD session is still open on a screen ending with "\nEnter DESCO bill number"
    And no core balance changed

  @cash
  Scenario: a customer cash-out to an agent over USSD completes
    When 01711000001 dials *268# on robi and enters "9, 2, 01811000001, 50, 1, 1234"
    Then the USSD session ends with "Tk 50.00 cashed out at agent 01811000001. TxnID "
    And the core balances changed by:
      | 01711000001 | -6000 |
      | 01811000001 | 5000  |
      | FEE-POOL    | 1000  |
