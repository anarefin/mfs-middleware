package mfs.mw.app.gateway.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Device binding OTPs (review A5; as mw-checkout's {@code OtpCodes}, copied): 6-digit codes, stored
 * only as a hash bound to the wallet and the device they were asked for.
 */
public final class OtpCodes {

  private static final SecureRandom RANDOM = new SecureRandom();

  private OtpCodes() {}

  /** A uniformly random 6-digit code, leading zeros kept. */
  public static String newOtp() {
    return String.format("%06d", RANDOM.nextInt(1_000_000));
  }

  /** The stored form: hex SHA-256 of {@code wallet:deviceId:otp}, so a hash never fits another device. */
  public static String hash(String wallet, String deviceId, String otp) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256")
          .digest((wallet + ":" + deviceId + ":" + otp).getBytes(StandardCharsets.UTF_8));
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
}
