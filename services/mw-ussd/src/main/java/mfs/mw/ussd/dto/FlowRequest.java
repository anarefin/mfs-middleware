package mfs.mw.ussd.dto;

import java.util.Map;
import mfs.mw.ussd.util.Sensitive;
import mfs.mw.ussd.util.SensitiveType;

/**
 * An ACTION node's call into a flow: the session, the menu path taken, the canonical fields from
 * the node's mapping ({@code creditParty}, {@code amount} in taka) and the PIN ciphertext
 * encrypted on receipt (never stored).
 */
public record FlowRequest(
    String flow,
    UssdSession session,
    String menuPath,
    @Sensitive(SensitiveType.SECRET) Map<String, String> fields,
    @Sensitive(SensitiveType.SECRET) EncryptedPin pin) {

  public static final String CREDIT_PARTY = "creditParty";
  public static final String AMOUNT = "amount";

  public FlowRequest {
    fields = fields == null ? Map.of() : Map.copyOf(fields);
  }
}
