package mfs.mw.checkout.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * One-time codes and tokens of web checkout: 6-digit OTPs (stored only as a hash bound to their
 * session), and the hosted page's pay token (32 random bytes, base64url).
 */
public final class OtpCodes {

  private static final SecureRandom RANDOM = new SecureRandom();

  private OtpCodes() {}

  /** A uniformly random 6-digit code, leading zeros kept. */
  public static String newOtp() {
    return String.format("%06d", RANDOM.nextInt(1_000_000));
  }

  /** The stored form: hex SHA-256 of {@code sessionId:otp}, so a hash never matches another session. */
  public static String hash(UUID sessionId, String otp) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256")
          .digest((sessionId + ":" + otp).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Constant-time comparison of two hashes (null never matches). */
  public static boolean matches(String expectedHash, String actualHash) {
    return expectedHash != null
        && actualHash != null
        && MessageDigest.isEqual(
            expectedHash.getBytes(StandardCharsets.US_ASCII), actualHash.getBytes(StandardCharsets.US_ASCII));
  }

  /** The hosted page's bearer token: 256 random bits, URL-safe. */
  public static String newPayToken() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }
}
