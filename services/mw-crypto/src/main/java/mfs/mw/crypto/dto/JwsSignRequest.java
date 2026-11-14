package mfs.mw.crypto.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * {@code POST /internal/v1/jws/sign}: a compact JWS over {@code payload} by the ACTIVE version of
 * {@code keyId}, or of the key of {@code purpose} (exactly one of the two).
 *
 * @param alg {@code RS256} (default for an RSA key) or {@code PS256}; {@code ES256} for an EC key
 * @param header extra protected header members ({@code typ}, {@code cty}, private names); {@code alg},
 *     {@code kid} and the key-locating members are set by the service and refused here
 * @param payload the JSON claims
 */
public record JwsSignRequest(
    @Pattern(regexp = Formats.KEY_REF) String keyId,
    @Pattern(regexp = "JWT_SIGN|ASSERTION_SIGN|AUDIT_ANCHOR_SIGN") String purpose,
    @Pattern(regexp = "RS256|PS256|ES256") String alg,
    Map<String, Object> header,
    @NotNull @Sensitive(SensitiveType.SECRET) Map<String, Object> payload) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
