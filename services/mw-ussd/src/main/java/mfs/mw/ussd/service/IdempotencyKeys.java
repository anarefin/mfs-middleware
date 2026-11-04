package mfs.mw.ussd.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The idempotency key of a USSD transaction (spec 7.3 rule 1): USSD has no client key, so it is
 * {@code sha256(channel | msisdn | sessionId | menuPath | amount | target)} in hex (64 chars, the
 * contract's maximum). A repeated step of the same session submits the same key, which the
 * orchestrator answers with the stored transaction.
 */
public final class IdempotencyKeys {

  private IdempotencyKeys() {}

  public static String derive(
      String channel, String msisdn, String sessionId, String menuPath, String amountMinor, String target) {
    String material = String.join("|", channel, msisdn, sessionId, menuPath, amountMinor, target);
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
