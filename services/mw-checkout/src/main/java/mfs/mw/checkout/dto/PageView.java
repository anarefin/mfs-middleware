package mfs.mw.checkout.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * {@code GET /checkout/pay/v1/{token}}: what the hosted page shows. {@code pinKey} is the PIN
 * transport key (mw-crypto {@code PIN_TRANSPORT} from mw-auth, ticket 72) the page encrypts the PIN
 * with (WebCrypto RSA-OAEP SHA-256), left out while none can be fetched; {@code redirectUrl} is set
 * once the session is terminal (the signed result for the merchant).
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

  /** The PIN transport key: kid to send as {@code encryptedPin.keyId}, algorithm, PEM. */
  public record PinKey(String keyId, String algorithm, String publicKey) {

    public static PinKey of(TransportKey k) {
      return new PinKey(k.keyId(), k.algorithm(), k.publicKeyPem());
    }
  }
}
