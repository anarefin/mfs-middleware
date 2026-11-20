package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * Body of mw-orchestrator's {@code POST /internal/v1/quotes} (contract {@code txn-query.v1} 1.5.0
 * {@code QuoteRequest}, ticket 105): always the signed-in wallet, from the token. A top-up
 * sends {@code productCode} so the orchestrator can fetch a postpaid bill or check a denomination.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuoteRequest(
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String walletType,
    String txnType,
    String channel,
    Amount amount,
    Counterparty counterparty,
    String productCode) {

  /** A quote that is not a top-up. */
  @JsonIgnore
  public QuoteRequest(
      String walletId, String walletType, String txnType, String channel, Amount amount, Counterparty counterparty) {
    this(walletId, walletType, txnType, channel, amount, counterparty, null);
  }

  /** The payee as the app named it (canonical MSISDN for a wallet or a top-up number). */
  public record Counterparty(String type, @Sensitive(SensitiveType.PARTY_ID) String id) {}
}
