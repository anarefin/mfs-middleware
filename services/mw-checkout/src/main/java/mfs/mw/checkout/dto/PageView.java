package mfs.mw.checkout.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * {@code GET /checkout/pay/v1/{token}}: what the hosted page shows. {@code pinKey} is the core's
 * public key the page encrypts the PIN with (RSA-OAEP SHA-256); {@code redirectUrl} is set once
 * the session is terminal (the signed result for the merchant).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageView(
    String merchantName,
    String orderId,
    String description,
    Amount amount,
    SessionStatus status,
    Instant expiresAt,
    String wallet,
    PinKey pinKey,
    String txnId,
    String redirectUrl) {

  /**
   * The core's PIN encryption key; {@code publicKey} is left out, not null, while none is
   * configured (contract {@code PageView}: an optional string).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PinKey(String keyId, String publicKey) {}
}
