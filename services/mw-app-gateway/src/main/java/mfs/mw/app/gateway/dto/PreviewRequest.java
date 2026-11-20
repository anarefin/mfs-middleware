package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Body of {@code POST /app/v1/{group}/preview} (ticket 105, contract {@code PreviewRequest}): the
 * transaction of the group as in {@link TransactionRequest}, without txnType (the group's) and PIN.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PreviewRequest(
    @Valid TransactionRequest.CounterParty creditParty,
    @Valid TransactionRequest.BankAccount debitParty,
    @Valid TransactionRequest.Partner partner,
    @NotNull @Valid Amount amount,
    @Pattern(regexp = "bn|en") String locale,
    @Size(max = 20) Map<@Size(max = 64) String, @Size(max = 256) String> attributes,
    @Pattern(regexp = TransactionRequest.LINKED_ACCOUNT_ID) String linkedAccountId) {

  /** A preview without a linked account. */
  public PreviewRequest(
      TransactionRequest.CounterParty creditParty,
      TransactionRequest.BankAccount debitParty,
      TransactionRequest.Partner partner,
      Amount amount,
      String locale,
      Map<String, String> attributes) {
    this(creditParty, debitParty, partner, amount, locale, attributes, null);
  }

  /** The transaction as {@code POST /app/v1/transactions} would carry it, without a PIN. */
  public TransactionRequest toTransaction(String txnType) {
    return new TransactionRequest(txnType, creditParty, amount, null, locale, attributes, partner, debitParty, null, linkedAccountId);
  }
}
