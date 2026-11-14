package mfs.mw.auth.dto;

import java.time.Instant;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * {@code VerifyCustomerFactors} on {@code core.wallet.command.v1} (core.command.v1, ticket 70): the
 * self-service PIN reset's KYC factors as TOKENISE_NID hashes bound to the wallet, never in clear.
 */
public record VerifyCustomerFactorsCommand(
    int schemaVersion,
    String commandType,
    String commandId,
    String txnId,
    String correlationId,
    Instant issuedAt,
    Instant expiresAt,
    Payload payload) {

  public static final String TYPE = "VerifyCustomerFactors";

  public record Payload(Subject subject, String resetId, Factors factors) {}

  public record Subject(String type, @Sensitive(SensitiveType.MSISDN) String id) {}

  public record Factors(
      @Sensitive(SensitiveType.SECRET) String nidLast4Token,
      @Sensitive(SensitiveType.SECRET) String dateOfBirthToken,
      String tokenKid) {}

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
