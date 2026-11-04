package mfs.mw.orchestrator;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.EncryptedPin;
import mfs.mw.orchestrator.dto.PartnerRef;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.TxnRequest;

/** Fake, obviously non-real request fixtures. */
public final class TestData {

  public static final Instant NOW = Instant.parse("2026-11-03T08:15:30Z");

  private TestData() {}

  /** A UUIDv7-shaped id (version 7, variant 8), as the contract's TxnId pattern requires. */
  public static String txnId() {
    String u = UUID.randomUUID().toString();
    return u.substring(0, 14) + "7" + u.substring(15, 19) + "8" + u.substring(20);
  }

  /** A TOPUP of {@code msisdn} (operator gp, prepaid) paid from customer 01711000001's wallet. */
  public static TxnRequest topup(String txnId, String idemKey, long amountMinor, String msisdn) {
    return new TxnRequest(
        1,
        txnId,
        idemKey,
        "corr-" + txnId.substring(0, 8),
        "CUSTOMER_APP",
        "TOPUP",
        new Party("MSISDN", "01711000001", null, null),
        new Party("WALLET", "01711000001", null, "CUSTOMER"),
        new Party("MSISDN", msisdn, null, null),
        Amount.bdt(amountMinor),
        new PartnerRef("gp", "PREPAID", null),
        null,
        new EncryptedPin("core-pin-test-1", "ZmFrZS1jaXBoZXJ0ZXh0"),
        "en",
        null,
        null,
        NOW);
  }

  /** A BILL_PAY of DESCO bill {@code billNumber} through aggregator billagg, from 01711000001. */
  public static TxnRequest billPay(String txnId, String idemKey, long amountMinor, String billNumber) {
    return new TxnRequest(
        1,
        txnId,
        idemKey,
        "corr-" + txnId.substring(0, 8),
        "CUSTOMER_APP",
        "BILL_PAY",
        new Party("MSISDN", "01711000001", null, null),
        new Party("WALLET", "01711000001", null, "CUSTOMER"),
        new Party("BILLER_ACCOUNT", billNumber, null, null),
        Amount.bdt(amountMinor),
        new PartnerRef("billagg", "BILL", "DESCO"),
        null,
        new EncryptedPin("core-pin-test-1", "ZmFrZS1jaXBoZXJ0ZXh0"),
        "en",
        null,
        null,
        NOW);
  }

  /**
   * A REVERSAL of {@code originalTxnId} asked by operations (channel SYSTEM): the parties are the
   * original P2P's, swapped; no PIN.
   */
  public static TxnRequest reversal(String txnId, String idemKey, String originalTxnId, long amountMinor) {
    return new TxnRequest(
        1,
        txnId,
        idemKey,
        "corr-" + txnId.substring(0, 8),
        "SYSTEM",
        "REVERSAL",
        new Party("ORG", "upay-ops", null, null),
        new Party("WALLET", "01711000002", null, "CUSTOMER"),
        new Party("WALLET", "01711000001", null, "CUSTOMER"),
        Amount.bdt(amountMinor),
        null,
        originalTxnId,
        null,
        "en",
        null,
        Map.of("reason", "customer dispute"),
        NOW);
  }

  public static TxnRequest p2p(String txnId, String idemKey, long amountMinor) {
    return new TxnRequest(
        1,
        txnId,
        idemKey,
        "corr-" + txnId.substring(0, 8),
        "CUSTOMER_APP",
        "P2P",
        new Party("MSISDN", "01711000001", null, null),
        new Party("WALLET", "01711000001", null, "CUSTOMER"),
        new Party("WALLET", "01711000002", null, "CUSTOMER"),
        Amount.bdt(amountMinor),
        null,
        null,
        new EncryptedPin("core-pin-test-1", "ZmFrZS1jaXBoZXJ0ZXh0"),
        "en",
        Map.of("deviceId", "dev-test-1"),
        null,
        NOW);
  }
}
