@notify @mw-notify @mw-simulators @mw-app-gateway @mw-orchestrator @mw-core-mock
Feature: Every flow sends the right SMS, and an email receipt where configured (ticket 25)
  mw-notify's seeded rules (notify.rule) send an SMS on success, failure and processing (outcome
  unknown) of P2P, CASH_IN, CASH_OUT, TOPUP, BILL_PAY and ADD_MONEY_BANK, in Bangla when the
  request names no locale, the other party masked (an MSISDN as 0171****002, an account by its
  last 4 digits). BILL_PAY and ADD_MONEY_BANK success also send an email receipt (subject, text and
  HTML parts) over SMTP to GreenMail, to the initiator's registered address (the dev stack seeds
  customer01@example.test for 01711000001).

  @p2p
  Scenario: a P2P the core refuses tells the sender it failed
    Given customer01 is signed in to the app
    And the core rejects the next ValidateTransaction with reason INSUFFICIENT_BALANCE
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234"
    Then the response status is 422
    And 01711000001 gets the SMS "0171****002 নম্বরে ৳10.00 পাঠানো ব্যর্থ হয়েছে। TrxID {txnId}"

  @p2p @tsq
  Scenario: a P2P whose core reply is lost tells the sender it is processing, then that it was sent
    Given customer01 is signed in to the app
    And the core drops its reply to the next PostTransfer
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234"
    Then the response status is 202
    And 01711000001 gets the SMS "0171****002 নম্বরে ৳10.00 পাঠানো প্রক্রিয়াধীন। ফলাফল SMS-এ জানানো হবে। TrxID {txnId}" within 60 seconds
    And 01711000001 gets the SMS "আপনি 0171****002 নম্বরে ৳10.00 পাঠিয়েছেন। ফি ৳5.00। TrxID {txnId}" within 90 seconds

  @cash
  Scenario: a cash-in notifies the agent and the customer
    Given agent01 is signed in to the app
    When agent01 submits CASH_IN of 2000 poisha with counterparty 01711000001 and PIN "1234"
    Then the response status is 200
    And 01811000001 gets the SMS "0171****001 নম্বরে ৳20.00 ক্যাশ ইন সফল হয়েছে। TrxID {txnId}"
    And 01711000001 gets the SMS "এজেন্ট 0181****001 থেকে ৳20.00 ক্যাশ ইন পেয়েছেন। TrxID {txnId}"

  @cash
  Scenario: a cash-in the core refuses tells the agent it failed
    Given agent01 is signed in to the app
    And the core rejects the next ValidateTransaction with reason INSUFFICIENT_BALANCE
    When agent01 submits CASH_IN of 2000 poisha with counterparty 01711000001 and PIN "1234"
    Then the response status is 422
    And 01811000001 gets the SMS "0171****001 নম্বরে ৳20.00 ক্যাশ ইন ব্যর্থ হয়েছে। TrxID {txnId}"

  @cash
  Scenario: a cash-out notifies the customer (with the fee) and the agent
    Given customer01 is signed in to the app
    When customer01 submits CASH_OUT of 2000 poisha with counterparty 8801811000001 and PIN "1234"
    Then the response status is 200
    And 01711000001 gets the SMS "এজেন্ট 0181****001 থেকে ৳20.00 ক্যাশ আউট সফল হয়েছে। ফি ৳10.00। TrxID {txnId}"
    And 01811000001 gets the SMS "0171****001 নম্বর থেকে ৳20.00 ক্যাশ আউট পেয়েছেন। TrxID {txnId}"

  @cash
  Scenario: a cash-out the core refuses tells the customer it failed
    Given customer01 is signed in to the app
    And the core rejects the next ValidateTransaction with reason INSUFFICIENT_BALANCE
    When customer01 submits CASH_OUT of 2000 poisha with counterparty 8801811000001 and PIN "1234"
    Then the response status is 422
    And 01711000001 gets the SMS "এজেন্ট 0181****001 থেকে ৳20.00 ক্যাশ আউট ব্যর্থ হয়েছে। TrxID {txnId}"

  @topup @mw-adapter-telco
  Scenario: a top-up notifies the payer and the recharged number
    Given customer01 is signed in to the app
    When customer01 tops up 2000 poisha gp PREPAID airtime on 01711000002 with PIN "1234"
    Then the response status is 200
    And 01711000001 gets the SMS "0171****002 নম্বরে ৳20.00 রিচার্জ সফল হয়েছে। TrxID {txnId}"
    And 01711000002 gets the SMS "0171****001 আপনার নম্বরে ৳20.00 রিচার্জ করেছেন। TrxID {txnId}"

  @topup @mw-adapter-telco
  Scenario: a top-up the operator refuses tells the payer it failed
    Given customer01 is signed in to the app
    When customer01 tops up 2000 poisha gp PREPAID airtime on 01711000999 with PIN "1234"
    Then the response status is 502
    And 01711000001 gets the SMS "0171****999 নম্বরে ৳20.00 রিচার্জ ব্যর্থ হয়েছে। TrxID {txnId}"

  @topup @mw-adapter-telco @tsq
  Scenario: a top-up the operator answers too late tells the payer it is processing, then that it succeeded
    Given customer01 is signed in to the app
    When customer01 tops up 2013 poisha gp PREPAID airtime on 01711000002 with PIN "1234"
    Then the response status is 202
    And 01711000001 gets the SMS "0171****002 নম্বরে ৳20.13 রিচার্জ প্রক্রিয়াধীন। ফলাফল SMS-এ জানানো হবে। TrxID {txnId}" within 60 seconds
    And 01711000001 gets the SMS "0171****002 নম্বরে ৳20.13 রিচার্জ সফল হয়েছে। TrxID {txnId}" within 120 seconds

  @billpay @mw-adapter-billpay
  Scenario: a bill payment sends an SMS and an email receipt
    Given customer01 is signed in to the app
    When customer01 pays 66700 poisha on bill 1234567 of biller DESCO with PIN "1234"
    Then the response status is 200
    And 01711000001 gets the SMS "DESCO বিল (****4567) ৳667.00 পরিশোধ সফল হয়েছে। ফি ৳3.00। TrxID {txnId}"
    And customer01@example.test gets the email "upay বিল পরিশোধের রসিদ, TrxID {txnId}"

  @billpay @mw-adapter-billpay
  Scenario: a bill payment the aggregator refuses tells the payer it failed, without a receipt
    Given customer01 is signed in to the app
    When customer01 pays 10000 poisha on bill 1234999 of biller DESCO with PIN "1234"
    Then the response status is 502
    And 01711000001 gets the SMS "DESCO বিল (****4999) ৳100.00 পরিশোধ ব্যর্থ হয়েছে। TrxID {txnId}"
    And customer01@example.test gets no email about the transaction

  @billpay @mw-adapter-billpay @tsq
  Scenario: a bill payment the aggregator answers too late tells the payer it is processing
    Given customer01 is signed in to the app
    When customer01 pays 10013 poisha on bill 1234567 of biller DESCO with PIN "1234"
    Then the response status is 202
    And 01711000001 gets the SMS "DESCO বিল (****4567) ৳100.13 পরিশোধ প্রক্রিয়াধীন। ফলাফল SMS-এ জানানো হবে। TrxID {txnId}" within 60 seconds
    And the app shows the transaction as COMPLETED within 120 seconds
    And customer01@example.test gets the email "upay বিল পরিশোধের রসিদ, TrxID {txnId}"

  @addmoney @c2w @mw-adapter-bank
  Scenario: money added from a bank account sends an SMS and an email receipt
    Given customer01 is signed in to the app
    And the bank confirms debits by callback
    When customer01 adds 50000 poisha from bank account 1234567890123 at c2wbank with PIN "1234"
    Then the app shows the transaction as COMPLETED within 25 seconds
    And 01711000001 gets the SMS "c2wbank অ্যাকাউন্ট ****0123 থেকে আপনার upay অ্যাকাউন্টে ৳500.00 যোগ হয়েছে। TrxID {txnId}"
    And customer01@example.test gets the email "upay অ্যাড মানি রসিদ, TrxID {txnId}"

  @addmoney @c2w @mw-adapter-bank
  Scenario: a debit the bank refuses tells the customer it failed
    Given customer01 is signed in to the app
    And the bank confirms debits by callback
    When customer01 adds 50000 poisha from bank account 1234567890999 at c2wbank with PIN "1234"
    Then the app shows the transaction as FAILED with error "MW-PTR-C2WBANK-DEBIT_REFUSED" within 25 seconds
    And 01711000001 gets the SMS "c2wbank অ্যাকাউন্ট ****0999 থেকে ৳500.00 যোগ করা ব্যর্থ হয়েছে। TrxID {txnId}"

  @addmoney @c2w @mw-adapter-bank @tsq
  Scenario: a bank that does not answer in time tells the customer it is processing
    Given customer01 is signed in to the app
    And the bank confirms debits by callback
    When customer01 adds 50013 poisha from bank account 1234567890123 at c2wbank with PIN "1234"
    Then 01711000001 gets the SMS "c2wbank অ্যাকাউন্ট ****0123 থেকে ৳500.13 যোগ করা প্রক্রিয়াধীন। ফলাফল SMS-এ জানানো হবে। TrxID {txnId}" within 60 seconds
    And the app shows the transaction as COMPLETED within 120 seconds
    And customer01@example.test gets the email "upay অ্যাড মানি রসিদ, TrxID {txnId}"
