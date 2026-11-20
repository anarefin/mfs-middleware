package mfs.mw.adapter.remit.partner.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * A detached JWS (RFC 7515 appendix F, compact serialisation without the payload) for {@code
 * mw-auth-jws} (ticket 87): {@code BASE64URL(header) + ".." + BASE64URL(signature)}, signed over
 * {@code BASE64URL(header) + "." + BASE64URL(body)}. The protected header is {@code {"alg":...}},
 * with {@code "kid"} when a key id is set. RS256 is RSASSA-PKCS1-v1_5 with SHA-256; ES256 is
 * ECDSA P-256 with SHA-256 in the JOSE form R || S (64 bytes, RFC 7518 3.4), not DER. The key is a
 * PKCS#8 PEM read from the partner's own Vault secret; it is never logged and a failure never
 * shows it.
 */
public final class DetachedJws {

  private static final Map<String, String[]> ALGORITHMS = Map.of(
      "RS256", new String[] {"RSA", "SHA256withRSA"},
      "ES256", new String[] {"EC", "SHA256withECDSAinP1363Format"});

  private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

  private DetachedJws() {}

  /** Whether {@code alg} is one this signer supports. */
  public static boolean supports(String alg) {
    return alg != null && ALGORITHMS.containsKey(alg);
  }

  /**
   * @throws IllegalStateException when the algorithm is not supported or the key cannot be read
   *     or does not fit it (the message names neither key nor body)
   */
  public static String sign(String alg, String keyId, String pkcs8Pem, byte[] body) {
    String[] algorithm = ALGORITHMS.get(alg);
    if (algorithm == null) {
      throw new IllegalStateException("JWS alg " + alg + " is not supported (RS256, ES256)");
    }
    String header = keyId == null || keyId.isEmpty()
        ? "{\"alg\":\"" + alg + "\"}"
        : "{\"alg\":\"" + alg + "\",\"kid\":\"" + jsonEscape(keyId) + "\"}";
    String protectedHeader = B64URL.encodeToString(header.getBytes(StandardCharsets.UTF_8));
    String input = protectedHeader + "." + B64URL.encodeToString(body == null ? new byte[0] : body);
    try {
      Signature signature = Signature.getInstance(algorithm[1]);
      signature.initSign(privateKey(algorithm[0], pkcs8Pem));
      signature.update(input.getBytes(StandardCharsets.US_ASCII));
      return protectedHeader + ".." + B64URL.encodeToString(signature.sign());
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("JWS " + alg + " signing failed (" + e.getClass().getSimpleName() + ")");
    }
  }

  private static PrivateKey privateKey(String type, String pem) throws GeneralSecurityException {
    String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
    return KeyFactory.getInstance(type).generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
  }

  private static String jsonEscape(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      if (c == '"' || c == '\\') {
        out.append('\\').append(c);
      } else if (c < 0x20) {
        out.append(String.format("\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
