package mfs.mw.simulators.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The wire bodies of the CyberSource-style card acquirer mock (ticket 118). The middleware side sends
 * only references, the session id and the transient token: no card data ever crosses this API.
 */
public final class CyberSourceRequests {

  private CyberSourceRequests() {}

  /** {@code POST /cybersource/v1/sessions}: a hosted page session for an amount. */
  public record Session(
      @NotBlank String clientReference,
      @NotBlank @Pattern(regexp = "\\d{1,13}\\.\\d{2}") String amount,
      String currency) {}

  /** {@code POST /cybersource/v1/payments}: authorise + capture of an authenticated session. */
  public record Payment(
      @NotBlank String clientReference,
      @NotBlank String sessionId,
      @NotBlank String transientToken,
      @NotBlank @Pattern(regexp = "\\d{1,13}\\.\\d{2}") String amount,
      String currency) {}

  /** {@code POST /cybersource/v1/payments/{reference}/refunds}. */
  public record Refund(
      @NotBlank String refundId,
      @NotBlank @Pattern(regexp = "\\d{1,13}\\.\\d{2}") String amount,
      String currency) {}
}
