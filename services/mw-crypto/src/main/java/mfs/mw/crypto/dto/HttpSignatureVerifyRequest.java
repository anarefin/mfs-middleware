package mfs.mw.crypto.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import mfs.mw.crypto.util.MaskingPolicy;

/**
 * {@code POST /internal/v1/http-signature/verify} (ticket 65): checks the RFC 9421 signature {@code
 * label} of the received {@code Signature-Input} and {@code Signature} field values against exactly
 * one of {@code keyId} (a key here: its ACTIVE and VERIFY_ONLY versions, or the one a kid in {@code
 * keyid} names) and {@code certificatePem} (the partner's certificate or public key).
 *
 * @param alg the algorithm the caller expects; else the signature's {@code alg} parameter, else by key
 * @param label default: the only signature in the fields
 * @param components the values of the covered components, taken from the received message
 * @param requiredComponents component names the signature must cover (e.g. {@code @method}, {@code
 *     content-digest})
 * @param expectedKeyid when set, the signature's {@code keyid} parameter must equal it (a partner's own key name)
 * @param maxAgeSeconds when set, {@code created} must be present and at most this old
 */
public record HttpSignatureVerifyRequest(
    @Pattern(regexp = Formats.KEY_REF) String keyId,
    @Size(max = Formats.MAX_PEM) String certificatePem,
    @Pattern(regexp = Formats.HTTP_SIGNATURE_ALG) String alg,
    @Pattern(regexp = Formats.SF_KEY) String label,
    @NotBlank @Size(max = Formats.MAX_SIGNATURE_FIELD) String signatureInput,
    @NotBlank @Size(max = Formats.MAX_SIGNATURE_FIELD) String signature,
    @NotNull @Size(max = 64) List<@Valid @NotNull HttpComponent> components,
    @Size(max = 64) List<@NotNull @Pattern(regexp = Formats.HTTP_COMPONENT) String> requiredComponents,
    @Size(max = 256) @Pattern(regexp = Formats.SF_STRING) String expectedKeyid,
    @Positive Long maxAgeSeconds) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
