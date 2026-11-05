package mfs.mw.e2e.support;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP as realm {@code ops} is configured (HmacSHA256, 6 digits, 30 s; ticket 37).
 * Keycloak keys the HMAC with the stored secret's UTF-8 bytes (the base32 form is only what an
 * authenticator app is shown), so the seeded {@code KC_OPS_TEST_TOTP_SECRET} is used as is.
 */
public final class Totp {

  private Totp() {}

  public static String now(String secret) {
    return at(secret, System.currentTimeMillis() / 1000 / 30);
  }

  static String at(String secret, long step) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int o = h[h.length - 1] & 0x0f;
      int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
      return String.format("%06d", bin % 1_000_000);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
