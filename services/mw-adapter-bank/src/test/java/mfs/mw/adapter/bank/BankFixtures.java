package mfs.mw.adapter.bank;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import mfs.mw.adapter.bank.dto.PartnerRequest;

/**
 * Obviously fake test data: ADD_MONEY_BANK (C2W) debits of bank account {@code 1234567890123}
 * at bank c2wbank, into customer wallet 01711000001.
 */
public final class BankFixtures {

  public static final Instant NOW = Instant.parse("2026-11-03T08:15:33Z");
  public static final String ACCOUNT = "1234567890123";

  private BankFixtures() {}

  /** A fresh UUIDv7-shaped txnId (the contract pattern needs version 7 and variant 8-b). */
  public static String newTxnId() {
    String tail = String.format("%012x", ThreadLocalRandom.current().nextLong() & 0xffffffffffffL);
    return "01933b8e-7c1a-7b2e-9f3d-" + tail;
  }

  /** A PAY (debit of {@code account}) whose deadline is still ahead. */
  public static PartnerRequest pay(String txnId, String account, long valueMinor) {
    return request("PAY", txnId, account, valueMinor, Instant.now().plusSeconds(300));
  }

  /** A PAY whose deadline has passed. */
  public static PartnerRequest expiredPay(String txnId, String account, long valueMinor) {
    return request("PAY", txnId, account, valueMinor, Instant.now().minusSeconds(1));
  }

  /** A STATUS_QUERY about the PAY of {@code txnId} (spec 7.4). */
  public static PartnerRequest statusQuery(String txnId) {
    return request("STATUS_QUERY", txnId, ACCOUNT, 50_000, Instant.now().plusSeconds(300));
  }

  private static PartnerRequest request(
      String operation, String txnId, String account, long valueMinor, Instant deadlineAt) {
    return new PartnerRequest(
        1,
        "bank",
        UUID.randomUUID().toString(),
        txnId,
        "corr-0002",
        NOW,
        deadlineAt,
        new PartnerRequest.Payload(
            operation,
            "ADD_MONEY_BANK",
            new PartnerRequest.PartnerRef("c2wbank", "C2W", null),
            "BITS-IGNORED",
            new PartnerRequest.Amount(valueMinor, "BDT"),
            new PartnerRequest.Party("BANK_ACCOUNT", account, null, null),
            new PartnerRequest.Party("WALLET", "01711000001", null, "CUSTOMER"),
            null,
            null));
  }
}
