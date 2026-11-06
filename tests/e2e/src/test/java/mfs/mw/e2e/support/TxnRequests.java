package mfs.mw.e2e.support;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

/**
 * Publishes canonical requests on {@code mw.txn.request.v1} directly, as an internal producer
 * would. Used for what no channel API offers yet: a REVERSAL is an operations action (spec 7.2,
 * 9.x admin console with maker-checker, ticket 37); until mw-admin-api exists the suite plays that
 * producer. The broker is not published, so the record goes in through {@code docker compose exec
 * kafka kafka-console-producer.sh}. Test-only.
 */
public final class TxnRequests {

  public static final String TOPIC = "mw.txn.request.v1";

  private static final SecureRandom RANDOM = new SecureRandom();

  private TxnRequests() {}

  /** A UUIDv7 (time-ordered), the format the contract's TxnId requires. */
  public static String uuidV7() {
    byte[] b = new byte[16];
    RANDOM.nextBytes(b);
    long ms = Instant.now().toEpochMilli();
    for (int i = 0; i < 6; i++) {
      b[i] = (byte) (ms >>> (40 - 8 * i));
    }
    b[6] = (byte) ((b[6] & 0x0f) | 0x70);
    b[8] = (byte) ((b[8] & 0x3f) | 0x80);
    String h = HexFormat.of().formatHex(b);
    return UUID.fromString(
            h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-"
                + h.substring(16, 20) + "-" + h.substring(20))
        .toString();
  }

  /**
   * A REVERSAL of {@code originalTxnId} as operations would send it: channel SYSTEM, initiator
   * the operations org, the original's parties swapped (the core reverses from its own ledger).
   */
  public static ObjectNode reversal(
      String txnId, String idempotencyKey, String originalTxnId, String payer, String payee, long amountMinor) {
    ObjectNode r = Http.JSON.createObjectNode();
    r.put("schemaVersion", 1)
        .put("txnId", txnId)
        .put("idempotencyKey", idempotencyKey)
        .put("correlationId", "e2e-" + UUID.randomUUID())
        .put("channel", "SYSTEM")
        .put("txnType", "REVERSAL");
    r.putObject("initiator").put("type", "ORG").put("id", "bits-ops-e2e");
    r.putObject("debitParty").put("type", "WALLET").put("id", payee);
    r.putObject("creditParty").put("type", "WALLET").put("id", payer);
    r.putObject("amount").put("valueMinor", amountMinor).put("currency", "BDT");
    r.put("originalTxnId", originalTxnId).put("requestedAt", Instant.now().toString());
    r.putObject("attributes").put("reason", "e2e reversal");
    return r;
  }

  /** Publishes {@code request} keyed by its txnId (spec 6.2). */
  public static void publish(ObjectNode request) {
    String line = request.path("txnId").asString() + "|" + Http.JSON.writeValueAsString(request) + "\n";
    if (line.chars().filter(c -> c == '|').count() != 1) {
      throw new IllegalArgumentException("request must not contain '|', the key separator");
    }
    Stack.get()
        .composeWithInput(
            line,
            "exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh",
            "--bootstrap-server", "localhost:9092", "--topic", TOPIC,
            "--property", "parse.key=true", "--property", "key.separator=|");
  }
}
