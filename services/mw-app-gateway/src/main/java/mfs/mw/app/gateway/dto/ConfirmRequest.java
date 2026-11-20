package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Body of {@code POST /app/v1/{group}/confirm} (ticket 105, contract {@code ConfirmRequest}): the
 * previewed transaction again, the preview's id and the PIN ciphertext.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfirmRequest(
    @NotBlank @Size(max = 128) String previewId,
    @Valid TransactionRequest.CounterParty creditParty,
    @Valid TransactionRequest.BankAccount debitParty,
    @Valid TransactionRequest.Partner partner,
    @NotNull @Valid Amount amount,
    @NotNull @Valid EncryptedPin encryptedPin,
    @Pattern(regexp = "bn|en") String locale,
    Boolean confirmDuplicate,
    @Size(max = 20) Map<@Size(max = 64) String, @Size(max = 256) String> attributes,
    @Pattern(regexp = TransactionRequest.LINKED_ACCOUNT_ID) String linkedAccountId) {

  /** A confirm without a linked account. */
  public ConfirmRequest(
      String previewId,
      TransactionRequest.CounterParty creditParty,
      TransactionRequest.BankAccount debitParty,
      TransactionRequest.Partner partner,
      Amount amount,
      EncryptedPin encryptedPin,
      String locale,
      Boolean confirmDuplicate,
      Map<String, String> attributes) {
    this(previewId, creditParty, debitParty, partner, amount, encryptedPin, locale, confirmDuplicate, attributes, null);
  }

  /** The transaction as {@code POST /app/v1/transactions} carries it. */
  public TransactionRequest toTransaction(String txnType) {
    return new TransactionRequest(
        txnType, creditParty, amount, encryptedPin, locale, attributes, partner, debitParty, confirmDuplicate, linkedAccountId);
  }
}
