package mfs.mw.checkout.util;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The hosted page's pay token (32 random bytes, base64url). The checkout OTP itself is mw-auth's
 * since ticket 72 (sent, hashed and checked there).
 */
public final class OtpCodes {

  private static final SecureRandom RANDOM = new SecureRandom();

  private OtpCodes() {}

  /** The hosted page's bearer token: 256 random bits, URL-safe. */
  public static String newPayToken() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }
}
