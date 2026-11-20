package mfs.mw.adapter.atm;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import mfs.mw.adapter.atm.dto.PartnerRequest;

/**
 * Obviously fake test data: ATM_CASH_IN credits (ticket 120a) from the UCB settlement (debit party
 * {@code ORG}) to customer wallet {@code ACCOUNT}, at the test-only partner c2wbank (the engine
 * tests' record; the dev seed is ucbatm).
 */
public final class AtmFixtures {

  public static final Instant NOW = Instant.parse("2026-11-03T08:15:33Z");
  public static final String ACCOUNT = "01911000001";

  private AtmFixtures() {}

  /** A fresh UUIDv7-shaped txnId (the contract pattern needs version 7 and variant 8-b). */
  public static String newTxnId() {
    String tail = String.format("%012x", ThreadLocalRandom.current().nextLong() & 0xffffffffffffL);
    return "01933b8e-7c1a-7b2e-9f3d-" + tail;
  }

  /** A PAY (debit of {@code account}) whose deadline is still ahead. */
  public static PartnerRequest pay(String txnId, String account, long valueMinor) {
    return request("PAY", txnId, account, valueMinor, Instant.now().plusSeconds(300));
  }

  /** {@code request} as sent for partnerId {@code partnerId} (ADR D28 aliases). */
  public static PartnerRequest forPartner(PartnerRequest request, String partnerId) {
    PartnerRequest.Payload p = request.payload();
    return new PartnerRequest(
        request.schemaVersion(), request.family(), request.requestId(), request.txnId(), request.correlationId(),
        request.issuedAt(), request.deadlineAt(),
        new PartnerRequest.Payload(
            p.operation(), p.txnType(),
            new PartnerRequest.PartnerRef(partnerId, p.partner().productCode(), p.partner().billerCode()),
            p.partnerTxnRef(), p.amount(), p.debitParty(), p.creditParty(), p.originalPartnerRef(), p.attributes()));
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
        "atm",
        UUID.randomUUID().toString(),
        txnId,
        "corr-0002",
        NOW,
        deadlineAt,
        new PartnerRequest.Payload(
            operation,
            "ATM_CASH_IN",
            new PartnerRequest.PartnerRef("c2wbank", "C2W", null),
            "BITS-IGNORED",
            new PartnerRequest.Amount(valueMinor, "BDT"),
            new PartnerRequest.Party("ORG", "ucb-atm-settlement", null, null),
            new PartnerRequest.Party("WALLET", account, null, "CUSTOMER"),
            null,
            null));
  }
}
