@p2p @notify @mw-notify @mw-simulators @mw-app-gateway @mw-orchestrator @mw-core-mock
Feature: Customers get an SMS for their P2P transaction (ticket 24)
  mw-notify runs its rules on mw.txn.status.v1: a COMPLETED P2P sends TXN_P2P_SENT to the sender
  and TXN_P2P_RECEIVED to the receiver, rendered in the transaction's locale (bn by default) with
  the other party's number masked, over SMPP to the SMSC simulator (UCS-2 for Bangla, long text
  in concatenated parts), and the simulator's DLR ends each message DELIVERED.

  Background:
    Given customer01 is signed in to the app

  Scenario: sender and receiver get the right SMS text
    When customer01 sends 1000 poisha P2P to 01711000002 with PIN "1234"
    Then the response status is 200
    And the transaction status is COMPLETED
    And 01711000001 gets the SMS "আপনি 0171****002 নম্বরে ৳10.00 পাঠিয়েছেন। ফি ৳5.00। TrxID {txnId}"
    And 01711000002 gets the SMS "আপনি 0171****001 নম্বর থেকে ৳10.00 পেয়েছেন। TrxID {txnId}"
    And the SMS to 01711000001 about the transaction was sent as UCS2 in 2 parts
