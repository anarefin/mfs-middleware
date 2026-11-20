package mfs.mw.compliance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.compliance.util.MaskingPolicy;
import mfs.mw.compliance.util.Sensitive;
import mfs.mw.compliance.util.SensitiveType;

/**
 * An mw-crypto envelope ({@code crypto-internal.v1} Envelope; spec 18.8): stored as it is (jsonb)
 * for an encrypted column and sent back to {@code /envelope/decrypt}. Every byte field is base64.
 *
 * @param kekId the KEK version that wrapped the data key
 * @param wrappedKey the wrapped AES-256 data key
 * @param iv the 12-byte GCM nonce
 * @param ciphertext AES-256-GCM output with its tag
 * @param aad the bound additional authenticated data, if any
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CryptoEnvelope(
    String kekId,
    @Sensitive(SensitiveType.SECRET) String wrappedKey,
    String iv,
    @Sensitive(SensitiveType.SECRET) String ciphertext,
    String aad) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
