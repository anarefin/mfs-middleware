package mfs.mw.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * mw-crypto {@code POST /internal/v1/envelope/encrypt} body ({@code crypto-internal.v1}
 * EnvelopeEncryptRequest): a KEK purpose, the base64 plaintext and AAD.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CryptoEnvelopeRequest(String purpose, @Sensitive(SensitiveType.SECRET) String plaintext, String aad) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
