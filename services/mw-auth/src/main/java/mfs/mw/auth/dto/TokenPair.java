package mfs.mw.auth.dto;

import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * A login's tokens ({@code auth.v1} TokenResponse): the access JWT (RS256, 5 min) and the opaque
 * refresh token (30 days, device-bound, rotated on every use).
 *
 * @param sessionId the {@code sid} claim (the refresh token family)
 * @param pinSet false = the wallet has no PIN yet; the token's {@code amr} is [otp, device] and only
 *     {@code POST /auth/v1/pin} accepts it
 */
public record TokenPair(
    @Sensitive(SensitiveType.SECRET) String accessToken,
    String tokenType,
    long expiresIn,
    @Sensitive(SensitiveType.SECRET) String refreshToken,
    long refreshExpiresIn,
    String sessionId,
    long idleTimeoutSeconds,
    boolean pinSet) {

  public TokenPair withSession(long idleTimeoutSeconds, boolean pinSet) {
    return new TokenPair(accessToken, tokenType, expiresIn, refreshToken, refreshExpiresIn, sessionId,
        idleTimeoutSeconds, pinSet);
  }

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
