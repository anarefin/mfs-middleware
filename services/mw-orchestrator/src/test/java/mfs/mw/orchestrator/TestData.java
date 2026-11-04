package mfs.mw.orchestrator;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.EncryptedPin;
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
