package mfs.mw.adapter.bank.dto;

/**
 * Answer of {@code POST /bank/v1/accounts/validate} (ticket 117b): never the full account number,
 * only its last 4 digits, and the holder name as its first initial (the profile mapping masks it
 * and the service masks it again).
 */
public record AccountValidationView(String bankCode, String accountLast4, String holderName) {}
