package mfs.mw.checkout.entity;

import java.time.Instant;
import java.util.UUID;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.util.Sensitive;
import mfs.mw.checkout.util.SensitiveType;

/**
 * A row of {@code checkout.session} (spec 9.3, 16.2). Never leaves the service layer. The pay
 * token is the hosted page's bearer secret (in the checkout URL); the OTP is kept only as a hash.
 */
public record CheckoutSession(
    UUID sessionId,
    @Sensitive(SensitiveType.SECRET) String payToken,
    String merchantId,
    String merchantWallet,
    String orderId,
    long amountMinor,
    String currency,
    String description,
    String locale,
    String successUrl,
    String failUrl,
    String cancelUrl,
    String ipnUrl,
    SessionStatus status,
    @Sensitive(SensitiveType.MSISDN) String payerMsisdn,
    @Sensitive(SensitiveType.SECRET) String otpHash,
    Instant otpExpiresAt,
    int otpAttempts,
    int otpSends,
    UUID txnId,
    String errorCode,
    Instant createdAt,
    Instant expiresAt,
    Instant updatedAt) {

  /** The status a reader sees at {@code now}: an OPEN session past its expiry is EXPIRED. */
  public SessionStatus statusAt(Instant now) {
    return status == SessionStatus.OPEN && !now.isBefore(expiresAt) ? SessionStatus.EXPIRED : status;
  }

  /** Where the browser goes back to for a terminal status. */
  public String returnUrlFor(SessionStatus s) {
    return switch (s) {
      case PAID -> successUrl;
      case CANCELLED -> cancelUrl;
      default -> failUrl;
    };
  }
}
