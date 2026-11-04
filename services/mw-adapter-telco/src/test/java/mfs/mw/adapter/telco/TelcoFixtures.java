package mfs.mw.adapter.telco;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import mfs.mw.adapter.telco.dto.PartnerRequest;

/** Obviously fake test data; MSISDNs are from the unused 0171100xxxx range. */
public final class TelcoFixtures {

  public static final Instant NOW = Instant.parse("2026-11-03T08:15:33Z");

  private TelcoFixtures() {}

  /** A fresh UUIDv7-shaped txnId (the contract pattern needs version 7 and variant 8-b). */
  public static String newTxnId() {
    String tail = String.format("%012x", ThreadLocalRandom.current().nextLong() & 0xffffffffffffL);
    return "01933b8e-7c1a-7b2e-9f3d-" + tail;
  }

  /** A PAY whose deadline is still ahead (an expired PAY is refused unsent, ticket 18). */
  public static PartnerRequest pay(String txnId, String msisdn, long valueMinor) {
    return request("PAY", txnId, msisdn, valueMinor, Instant.now().plusSeconds(300));
  }

  /** A PAY whose deadline has passed. */
  public static PartnerRequest expiredPay(String txnId, String msisdn, long valueMinor) {
    return request("PAY", txnId, msisdn, valueMinor, Instant.now().minusSeconds(1));
  }

  /** A STATUS_QUERY about the PAY of {@code txnId} (spec 7.4). */
  public static PartnerRequest statusQuery(String txnId) {
    return request("STATUS_QUERY", txnId, "01711000001", 2000, Instant.now().plusSeconds(300));
  }

  private static PartnerRequest request(
      String operation, String txnId, String msisdn, long valueMinor, Instant deadlineAt) {
    return new PartnerRequest(
        1,
        "telco",
        UUID.randomUUID().toString(),
        txnId,
        "corr-0002",
        NOW,
        deadlineAt,
        new PartnerRequest.Payload(
            operation,
            "TOPUP",
            new PartnerRequest.PartnerRef("gp", "PREPAID", null),
            "UPAY-IGNORED",
            new PartnerRequest.Amount(valueMinor, "BDT"),
            new PartnerRequest.Party("WALLET", "01711000002", null, null),
            new PartnerRequest.Party("MSISDN", msisdn, null, null),
            null,
            null));
  }
}
