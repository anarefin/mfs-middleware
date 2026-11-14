package mfs.mw.crypto.util;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;

/**
 * The key ceremony's component arithmetic (ticket 75, spec 18.8, D23). A custodian's component is
 * 32 bytes (64 hex digits) read as a P-256 private scalar {@code d} ({@code 1 <= d < n}); its check
 * value is the first 3 bytes of SHA-256 over the compressed point {@code d·G}, so a custodian can
 * compute it offline from the paper share. The combined key is the ECDH shared secret of the two
 * components, {@code x(d1·d2·G)} (32 bytes, {@code CKM_ECDH1_DERIVE} with {@code CKD_NULL}), made
 * inside the HSM from component 1 (stored there) and component 2's public point: neither component
 * alone gives the key. The key check value (KCV) of an HMAC-SHA256 key is the first 3 bytes of
 * HMAC-SHA256 over 16 zero bytes. Check values are 6 upper-case hex digits; nothing here logs.
 */
public final class CeremonyComponents {

  /** 64 hex digits. */
  public static final Pattern COMPONENT = Pattern.compile("[0-9A-Fa-f]{64}");
  /** 6 hex digits. */
  public static final Pattern CHECK_VALUE = Pattern.compile("[0-9A-Fa-f]{6}");

  private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256r1");
  private static final ECParameterSpec SPEC = jdkSpec();

  private CeremonyComponents() {}

  /** Whether the 32 bytes are a usable P-256 scalar (0 < d < n). */
  public static boolean valid(byte[] component) {
    if (component == null || component.length != 32) {
      return false;
    }
    BigInteger d = new BigInteger(1, component);
    return d.signum() > 0 && d.compareTo(CURVE.getN()) < 0;
  }

  /** The component's check value: SHA-256 of the compressed {@code d·G}, first 3 bytes, upper-case hex. */
  public static String checkValue(byte[] component) {
    byte[] point = CURVE.getG().multiply(new BigInteger(1, component)).normalize().getEncoded(true);
    try {
      return hex3(MessageDigest.getInstance("SHA-256").digest(point));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /** The component's public point {@code d·G} as a JDK key (what the HSM's ECDH takes). */
  public static ECPublicKey publicKey(byte[] component) {
    org.bouncycastle.math.ec.ECPoint q = CURVE.getG().multiply(new BigInteger(1, component)).normalize();
    try {
      return (ECPublicKey) KeyFactory.getInstance("EC")
          .generatePublic(new ECPublicKeySpec(
              new ECPoint(q.getAffineXCoord().toBigInteger(), q.getAffineYCoord().toBigInteger()), SPEC));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("EC key factory failed", e);
    }
  }

  /** The component as a JDK P-256 private key (imported into the HSM as a sensitive token object). */
  public static ECPrivateKey privateKey(byte[] component) {
    try {
      return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, component), SPEC));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("EC key factory failed", e);
    }
  }

  /** The combined key the HSM derives: {@code x(d1·d2·G)}, 32 bytes. For tests and offline checks only. */
  public static byte[] combined(byte[] first, byte[] second) {
    org.bouncycastle.math.ec.ECPoint p =
        CURVE.getG().multiply(new BigInteger(1, first)).multiply(new BigInteger(1, second)).normalize();
    return p.getAffineXCoord().getEncoded();
  }

  /** KCV of an HMAC-SHA256 key from its HMAC over 16 zero bytes. */
  public static String keyCheckValue(byte[] hmacOfZeros) {
    return hex3(hmacOfZeros);
  }

  /** The 16 zero bytes a KCV is computed over. */
  public static byte[] kcvInput() {
    return new byte[16];
  }

  /** KCV of a clear HMAC key (tests and offline checks only). */
  public static String keyCheckValueOf(byte[] key) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return hex3(mac.doFinal(kcvInput()));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 not available", e);
    }
  }

  /** Constant-time compare of two check values, case-insensitive. */
  public static boolean sameCheckValue(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    return MessageDigest.isEqual(
        a.toUpperCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        b.toUpperCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }

  /** Hex to bytes; the caller zeroes the result. */
  public static byte[] parse(String hex) {
    return HexFormat.of().parseHex(hex);
  }

  public static void zero(byte[] b) {
    if (b != null) {
      Arrays.fill(b, (byte) 0);
    }
  }

  private static String hex3(byte[] b) {
    return HexFormat.of().withUpperCase().formatHex(b, 0, 3);
  }

  private static ECParameterSpec jdkSpec() {
    try {
      AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
      p.init(new ECGenParameterSpec("secp256r1"));
      return p.getParameterSpec(ECParameterSpec.class);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("P-256 not available", e);
    }
  }
}
