package mfs.mw.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Answer of {@code POST /internal/v1/xmldsig/verify} and {@code /http-signature/verify} (ticket 65):
 * {@code valid}, the key version that matched (when verified under a mw-crypto key), or why it is not
 * valid ({@code reason}: {@code SIGNATURE_MISSING}, {@code MULTIPLE_SIGNATURES}, {@code
 * SIGNATURE_PLACEMENT}, {@code ALGORITHM_NOT_ALLOWED}, {@code REFERENCE_NOT_ALLOWED}, {@code
 * MALFORMED_SIGNATURE}, {@code DIGEST_MISMATCH}, {@code SIGNATURE_MISMATCH}, {@code
 * CERTIFICATE_NOT_VALID}, {@code COMPONENT_MISSING}, {@code REQUIRED_COMPONENT_NOT_COVERED}, {@code
 * KEY_MISMATCH}, {@code CREATED_MISSING}, {@code TOO_OLD}, {@code CREATED_IN_FUTURE}, {@code EXPIRED}).
 * A signature that does not verify is not an error. Holds no value of the message.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SignatureCheck(boolean valid, String kid, String reason) {

  public static SignatureCheck valid(String kid) {
    return new SignatureCheck(true, kid, null);
  }

  public static SignatureCheck invalid(String reason) {
    return new SignatureCheck(false, null, reason);
  }
}
