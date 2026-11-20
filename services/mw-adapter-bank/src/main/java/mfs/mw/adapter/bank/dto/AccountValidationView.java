package mfs.mw.adapter.bank.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Answer of {@code POST /bank/v1/accounts/validate} (ticket 117b): never the full account number,
 * only its last 4 digits, and the holder name as its first initial (the profile mapping masks it
 * and the service masks it again). {@code nameMatch}: only when the request named a {@code matchName},
 * whether the bank's full holder name is that person ({@code util/HolderNameMatch}); the full name
 * itself never leaves the adapter.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountValidationView(String bankCode, String accountLast4, String holderName, Boolean nameMatch) {

  public AccountValidationView(String bankCode, String accountLast4, String holderName) {
    this(bankCode, accountLast4, holderName, null);
  }
}
