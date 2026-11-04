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

  public static PartnerRequest pay(String txnId, String msisdn, long valueMinor) {
    return new PartnerRequest(
        1,
        "telco",
        UUID.randomUUID().toString(),
        txnId,
        "corr-0002",
        NOW,
        NOW.plusSeconds(30),
        new PartnerRequest.Payload(
            "PAY",
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
