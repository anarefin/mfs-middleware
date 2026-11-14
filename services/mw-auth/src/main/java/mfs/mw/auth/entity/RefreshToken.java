package mfs.mw.auth.entity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * A row of {@code refresh_token} (D21): the SHA-256 of the opaque token, its family (= the session
 * id, {@code sid}), wallet and device, expiry, when it was rotated (null while current), revoked, and
 * the authentication methods of its session.
 */
public record RefreshToken(
    @Sensitive(SensitiveType.SECRET) String tokenHash,
    UUID family,
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String deviceId,
    Instant expiresAt,
    Instant rotatedAt,
    boolean revoked,
    List<String> amr) {

  public RefreshToken {
    amr = List.copyOf(amr);
  }

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
