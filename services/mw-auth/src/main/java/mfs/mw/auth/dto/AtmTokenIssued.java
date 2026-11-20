package mfs.mw.auth.dto;

import java.time.Instant;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/** Answer of {@code POST /internal/v1/atm-tokens}: the 6-digit token (shown once, never stored) and its expiry. */
public record AtmTokenIssued(@Sensitive(SensitiveType.SECRET) String token, Instant expiresAt) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
