package mfs.mw.auth.entity;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * A row of {@code auth.pin_reset} (ticket 70): a self-service ({@code SELF}) or care ({@code CARE})
 * PIN reset. SELF: PENDING_KYC -> VERIFIED | REJECTED; CARE: APPROVED -> VERIFIED (after the
 * customer's OTP); VERIFIED -> DONE; any open one past {@code expiresAt} is EXPIRED.
 */
public record PinReset(
    UUID id,
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String kind,
    String status,
    String deviceId,
    Instant expiresAt,
    UUID changeRequestId,
    String reason) {

  public static final String SELF = "SELF";
  public static final String CARE = "CARE";
  public static final String PENDING_KYC = "PENDING_KYC";
  public static final String APPROVED = "APPROVED";
  public static final String VERIFIED = "VERIFIED";
  public static final String DONE = "DONE";
  public static final String REJECTED = "REJECTED";
  public static final String EXPIRED = "EXPIRED";
  /** Statuses of an open reset (one per wallet: the partial unique index). */
  public static final Set<String> OPEN = Set.of(PENDING_KYC, "PENDING_OTP", APPROVED, VERIFIED);

  public boolean open() {
    return OPEN.contains(status);
  }

  /** The status the caller sees at {@code now}: an open reset past its end is EXPIRED. */
  public String statusAt(Instant now) {
    return open() && !now.isBefore(expiresAt) ? EXPIRED : status;
  }

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
