package mfs.mw.core.mock.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The transaction hash an assertion is bound to (spec 18.7, D22; ticket 71): base64url (no padding)
 * SHA-256 of the RFC 8785 canonical JSON of
 *
 * <pre>{txnType, amount (valueMinor), currency, payer {type, id}, payee {type, id}, idempotencyKey, channel}</pre>
 *
 * The values are those of the {@code mw.txn.request.v1} the channel publishes (payer = debitParty,
 * payee = creditParty, only their type and id), so the core recomputes from a command the hash
 * mw-auth signed (copied from mw-auth, D14; the same literals in both TxnHashTests), with the
 * payload's idempotencyKey and the assertion's channel.
 */
public final class TxnHash {

  /** A party as hashed: its type and id only. */
  public record Party(String type, String id) {
    Map<String, Object> json() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("type", type);
      m.put("id", id);
      return m;
    }
  }

  private TxnHash() {}

  /** The canonical JSON that is hashed. */
  public static String canonical(
      String txnType, long amountMinor, String currency, Party payer, Party payee, String idempotencyKey, String channel) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("txnType", txnType);
    m.put("amount", amountMinor);
    m.put("currency", currency);
    m.put("payer", payer.json());
    m.put("payee", payee.json());
    m.put("idempotencyKey", idempotencyKey);
    m.put("channel", channel);
    return CanonicalJson.write(m);
  }

  public static String of(
      String txnType, long amountMinor, String currency, Party payer, Party payee, String idempotencyKey, String channel) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(canonical(txnType, amountMinor, currency, payer, payee, idempotencyKey, channel)
              .getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 missing", e);
    }
  }
}
