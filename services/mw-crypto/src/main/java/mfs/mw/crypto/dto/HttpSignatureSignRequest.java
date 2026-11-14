package mfs.mw.crypto.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import mfs.mw.crypto.util.MaskingPolicy;

/**
 * {@code POST /internal/v1/http-signature/sign} (ticket 65): an RFC 9421 signature over {@code
 * components}, in their order, by the ACTIVE version of {@code keyId} ({@code HTTP_SIGN_EC}, {@code
 * HTTP_SIGN_RSA} or an {@code HMAC_<use>} key).
 *
 * @param alg {@code ecdsa-p256-sha256} (EC key), {@code rsa-pss-sha512} (RSA default) or {@code
 *     rsa-v1_5-sha256}, {@code hmac-sha256} (HMAC key); default by the key
 * @param label the signature's label in the {@code Signature} and {@code Signature-Input} fields
 *     (default {@code sig1})
 * @param created Unix seconds (default now); {@code expires}, {@code nonce}, {@code tag} optional
 */
public record HttpSignatureSignRequest(
    @NotBlank @Pattern(regexp = Formats.KEY_REF) String keyId,
    @Pattern(regexp = Formats.HTTP_SIGNATURE_ALG) String alg,
    @Pattern(regexp = Formats.SF_KEY) String label,
    @NotNull @Size(min = 1, max = 64) List<@Valid @NotNull HttpComponent> components,
    @PositiveOrZero Long created,
    @PositiveOrZero Long expires,
    @Size(max = 256) @Pattern(regexp = Formats.SF_STRING) String nonce,
    @Size(max = 256) @Pattern(regexp = Formats.SF_STRING) String tag) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
