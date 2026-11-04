package mfs.mw.adapter.billpay;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import mfs.mw.adapter.billpay.dto.PartnerRequest;

/** Obviously fake test data: DESCO bills through aggregator billagg, payer 0171100xxxx. */
public final class BillpayFixtures {

  public static final Instant NOW = Instant.parse("2026-11-03T08:15:33Z");
  public static final String BILL = "1234567";

  private BillpayFixtures() {}

  /** A fresh UUIDv7-shaped txnId (the contract pattern needs version 7 and variant 8-b). */
  public static String newTxnId() {
    String tail = String.format("%012x", ThreadLocalRandom.current().nextLong() & 0xffffffffffffL);
    return "01933b8e-7c1a-7b2e-9f3d-" + tail;
  }

  /** A PAY of {@code billNumber} whose deadline is still ahead. */
  public static PartnerRequest pay(String txnId, String billNumber, long valueMinor) {
    return request("PAY", txnId, billNumber, valueMinor, Instant.now().plusSeconds(300));
  }

  /** A PAY whose deadline has passed. */
  public static PartnerRequest expiredPay(String txnId, String billNumber, long valueMinor) {
    return request("PAY", txnId, billNumber, valueMinor, Instant.now().minusSeconds(1));
  }

  /** A STATUS_QUERY about the PAY of {@code txnId} (spec 7.4). */
  public static PartnerRequest statusQuery(String txnId) {
    return request("STATUS_QUERY", txnId, BILL, 66_700, Instant.now().plusSeconds(300));
  }

  private static PartnerRequest request(
      String operation, String txnId, String billNumber, long valueMinor, Instant deadlineAt) {
    return new PartnerRequest(
        1,
        "billpay",
        UUID.randomUUID().toString(),
        txnId,
        "corr-0002",
        NOW,
        deadlineAt,
        new PartnerRequest.Payload(
            operation,
            "BILL_PAY",
            new PartnerRequest.PartnerRef("billagg", "BILL", "DESCO"),
            "UPAY-IGNORED",
            new PartnerRequest.Amount(valueMinor, "BDT"),
            new PartnerRequest.Party("WALLET", "01711000001", null, "CUSTOMER"),
            new PartnerRequest.Party("BILLER_ACCOUNT", billNumber, null, null),
            null,
            null));
  }
}
