package mfs.mw.auth.dto;

/**
 * {@code GET /internal/v1/pin-key} (security review of ticket 72): the newest PIN transport key for the
 * channels, over the internal mTLS port, with the mw-crypto key purpose it is of ({@code
 * PIN_TRANSPORT}) so a channel can refuse any other key.
 */
public record InternalPinKey(String keyId, String algorithm, String publicKeyPem, String purpose) {

  /** The mw-crypto key purpose of the PIN transport key. */
  public static final String PIN_TRANSPORT = "PIN_TRANSPORT";

  public static InternalPinKey of(PinKey k) {
    return new InternalPinKey(k.keyId(), k.algorithm(), k.publicKeyPem(), PIN_TRANSPORT);
  }
}
