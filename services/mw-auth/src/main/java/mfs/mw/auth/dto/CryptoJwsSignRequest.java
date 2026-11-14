package mfs.mw.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * mw-crypto {@code POST /internal/v1/jws/sign} body ({@code crypto-internal.v1} JwsSignRequest): by
 * key purpose (JWT_SIGN for the access token), the algorithm, extra protected header members and the
 * claims.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CryptoJwsSignRequest(
    String purpose, String alg, Map<String, Object> header, @Sensitive(SensitiveType.SECRET) Map<String, Object> payload) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
