package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * What the app sees of a transaction ({@code POST} 200/202 and {@code GET
 * /app/v1/transactions/{txnId}}). {@code status} is a saga state of {@code mw.txn.status.v1},
 * or {@code PROCESSING} on a 202 when the answer did not arrive within the budget (spec 7.5).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionView(
    String txnId,
    String status,
    String txnType,
    Party debitParty,
    Party creditParty,
    Amount amount,
    Amount fee,
    String coreRef,
    ErrorInfo error,
    Instant requestedAt,
    Instant updatedAt) {

  /**
   * A bank account (ADD_MONEY_BANK's payer, W2B's payee; ticket 117c) is never shown whole: the last
   * 4 digits only, so neither the 200 answer, the projection row nor a replayed answer carry the number.
   */
  public TransactionView {
    debitParty = maskedAccount(debitParty);
    creditParty = maskedAccount(creditParty);
  }

  private static Party maskedAccount(Party p) {
    if (p == null || !"BANK_ACCOUNT".equals(p.type()) || p.id() == null) {
      return p;
    }
    return new Party(p.type(), mfs.mw.app.gateway.util.MaskingPolicy.maskPartyId(p.type(), p.id()), p.name(), p.walletType());
  }

  public static final String PROCESSING = "PROCESSING";

  public static TransactionView processing(String txnId) {
    return new TransactionView(txnId, PROCESSING, null, null, null, null, null, null, null, null, null);
  }

  public static TransactionView of(StatusMessage s) {
    return new TransactionView(
        s.txnId(), s.status(), s.txnType(), s.debitParty(), s.creditParty(), s.amount(), s.fee(),
        s.coreRef(), s.error(), s.requestedAt(), s.updatedAt());
  }

  /**
   * The merchant wallet a MERCHANT_PAY pays (ticket 30, collections view), or null for any other
   * transaction or credit party.
   */
  public String merchantPayee() {
    if (!"MERCHANT_PAY".equals(txnType) || creditParty == null || !"WALLET".equals(creditParty.type())) {
      return null;
    }
    return creditParty.id();
  }

  public TransactionView withStatus(String newStatus) {
    return new TransactionView(
        txnId, newStatus, txnType, debitParty, creditParty, amount, fee, coreRef, error,
        requestedAt, updatedAt);
  }
}
