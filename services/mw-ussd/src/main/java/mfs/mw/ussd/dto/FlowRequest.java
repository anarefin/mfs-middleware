package mfs.mw.ussd.dto;

import java.util.Map;
import mfs.mw.ussd.util.Sensitive;
import mfs.mw.ussd.util.SensitiveType;

/**
 * An ACTION node's call into a flow: the session, the menu path taken, the canonical fields from
 * the node's mapping ({@code creditParty}, {@code amount} in taka) and the PIN block
 * encrypted on receipt under the transport key (never stored; sent to mw-auth only).
 */
public record FlowRequest(
    String flow,
    UssdSession session,
    String menuPath,
    @Sensitive(SensitiveType.SECRET) Map<String, String> fields,
    @Sensitive(SensitiveType.SECRET) EncryptedPin pin) {

  public static final String CREDIT_PARTY = "creditParty";
  /** ADD_MONEY_BANK: the linked bank account the money is drawn from. */
  public static final String DEBIT_PARTY = "debitParty";
  public static final String AMOUNT = "amount";
  /** TOPUP: the operator; BILL_PAY: the aggregator serving the biller. */
  public static final String PARTNER_ID = "partnerId";
  /** TOPUP: the operator's product ({@code PREPAID}). */
  public static final String PRODUCT_CODE = "productCode";
  /**
   * Session input (not a mapping field): the user confirmed a suspected business duplicate
   * (ticket 99), so the request carries {@code confirmDuplicate}.
   */
  public static final String CONFIRM_DUPLICATE = "_confirmDuplicate";
  /** BILL_PAY: the biller. */
  public static final String BILLER_CODE = "billerCode";

  public FlowRequest {
    fields = fields == null ? Map.of() : Map.copyOf(fields);
  }
}
