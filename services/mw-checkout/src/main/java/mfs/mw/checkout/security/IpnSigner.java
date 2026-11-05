package mfs.mw.checkout.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Signs the server-to-server IPN (spec 9.3 step 3, ticket 32). Header {@code X-Upay-Signature} =
 * lower-case hex HMAC-SHA256 with the merchant's checkout secret over the UTF-8 text
 *
 * <pre>
 * upay-checkout-ipn.v1\n&lt;X-Upay-Timestamp&gt;\n&lt;the exact request body&gt;
 * </pre>
 *
 * The timestamp is the epoch second of the attempt (each retry is signed afresh), so a merchant
 * can refuse an old one; the prefix keeps it apart from the redirect result ({@link
 * ResultSigner#PREFIX}) signed with the same secret.
 */
public final class IpnSigner {

  public static final String PREFIX = "upay-checkout-ipn.v1";

  private IpnSigner() {}

  public static String sign(String secret, long epochSeconds, String body) {
    return ResultSigner.hmacHex(secret, PREFIX + "\n" + epochSeconds + "\n" + body);
  }

  /** What a merchant does with a received IPN: true only for an untouched body and timestamp. */
  public static boolean verify(String secret, String timestamp, String body, String signature) {
    if (signature == null || timestamp == null || body == null) {
      return false;
    }
    try {
      String expected = sign(secret, Long.parseLong(timestamp), body);
      return MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
    } catch (RuntimeException e) {
      return false;
    }
  }
}
