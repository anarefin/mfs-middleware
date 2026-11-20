package mfs.mw.adapter.card;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import mfs.mw.adapter.card.dto.PartnerRequest;

/**
 * Obviously fake test data: ADD_MONEY_CARD captures (ticket 118) of a card session (debit party
 * {@code CARD_TOKEN}, never card data) to customer wallet {@code ACCOUNT}, at the test-only partner
 * c2wbank (the engine tests' record; the dev seed is cybersource).
 */
public final class CardFixtures {

  public static final Instant NOW = Instant.parse("2026-11-03T08:15:33Z");
  public static final String ACCOUNT = "01911000001";

  private CardFixtures() {}

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
        "card",
        UUID.randomUUID().toString(),
        txnId,
        "corr-0002",
        NOW,
        deadlineAt,
        new PartnerRequest.Payload(
            operation,
            "ADD_MONEY_CARD",
            new PartnerRequest.PartnerRef("c2wbank", "C2W", null),
            "BITS-IGNORED",
            new PartnerRequest.Amount(valueMinor, "BDT"),
            new PartnerRequest.Party("CARD_TOKEN", "sess-fake-0001", null, null),
            new PartnerRequest.Party("WALLET", account, null, "CUSTOMER"),
            null,
            // a capture always carries the session and the webhook's token (ticket 118b)
            "PAY".equals(operation)
                ? java.util.Map.of("sessionId", "sess-fake-0001", "transientToken", "tok-fake-0001")
                : null));
  }
}
