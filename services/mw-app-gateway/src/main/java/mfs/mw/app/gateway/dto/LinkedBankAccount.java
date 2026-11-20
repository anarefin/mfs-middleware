package mfs.mw.app.gateway.dto;

import java.time.Instant;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * One linked bank account of the signed-in wallet (ticket 117b, FRS FL-08). Never the full number:
 * the last 4 digits and the holder's first initial only.
 */
public record LinkedBankAccount(
    String id,
    String bankCode,
    String accountLast4,
    @Sensitive(SensitiveType.NAME) String holderName,
    Instant verifiedAt) {

  /** The list answer of {@code GET /app/v1/bank-accounts}. */
  public record Page(java.util.List<LinkedBankAccount> accounts) {}
}
