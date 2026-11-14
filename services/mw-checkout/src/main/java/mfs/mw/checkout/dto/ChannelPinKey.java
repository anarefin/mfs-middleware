package mfs.mw.checkout.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * mw-auth {@code GET /internal/v1/pin-key} (contract {@code auth-internal.v1.openapi.yaml}, security
 * review of ticket 72): the PIN transport key over the internal mTLS port, with the mw-crypto key
 * purpose it is of. Only a {@code PIN_TRANSPORT} key is accepted ({@link #isPinTransport}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChannelPinKey(String keyId, String algorithm, String publicKeyPem, String purpose) {

  public static final String PIN_TRANSPORT = "PIN_TRANSPORT";

  /** A usable transport key: the right purpose, a kid and a PEM. */
  public boolean isPinTransport() {
    return PIN_TRANSPORT.equals(purpose) && keyId != null && !keyId.isBlank() && publicKeyPem != null
        && !publicKeyPem.isBlank();
  }

  public TransportKey toTransportKey() {
    return new TransportKey(keyId, algorithm, publicKeyPem);
  }
}
