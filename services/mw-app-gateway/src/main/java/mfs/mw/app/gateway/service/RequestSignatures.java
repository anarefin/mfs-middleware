package mfs.mw.app.gateway.service;

import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import mfs.mw.app.gateway.dto.ErrorDetail;
import mfs.mw.app.gateway.dto.SignedRequest;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;

/**
 * Device request signatures (spec 9.1, 18.6). The device signs, with its P-256 key registered at
 * binding, the UTF-8 text of six lines joined by {@code \n}:
 *
 * <pre>
 * METHOD
 * PATH                 (request path, plus "?query" when there is one)
 * hex(SHA-256(body))   (lower case; of the empty string when there is no body)
 * TIMESTAMP            (X-Timestamp, epoch milliseconds)
 * NONCE                (X-Nonce)
 * DEVICE-ID            (X-Device-Id)
 * </pre>
 *
 * with ECDSA SHA-256; the signature is base64, ASN.1 DER or the 64-byte P1363 {@code r||s}. The
 * timestamp must be within {@code clockSkew} of the gateway's clock; the nonce is checked for reuse
 * by the caller (the cache in Valkey), after the signature verified.
 */
public class RequestSignatures {

  private static final ECParameterSpec P256 = p256();

  private final Duration clockSkew;

  public RequestSignatures(Duration clockSkew) {
    this.clockSkew = clockSkew;
  }

  public static String canonical(SignedRequest r) {
    return String.join("\n", r.method(), r.path(), r.bodySha256(), r.timestamp(), r.nonce(), r.deviceId());
  }

  public static String sha256Hex(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * A device public key: base64 X.509 SubjectPublicKeyInfo of an EC key on P-256. Anything else is
   * 400 MW-VAL-001 naming {@code publicKey}.
   */
  public static PublicKey parsePublicKey(String base64) {
    try {
      PublicKey key =
          KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
      if (key instanceof ECPublicKey ec && sameCurve(ec.getParams(), P256)) {
        return key;
      }
    } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
      // falls through to the validation error
    }
    throw new MwException(ErrorCodes.VAL_INVALID, "Invalid device key")
        .withDetails(List.of(new ErrorDetail("publicKey", "must be a base64 X.509 EC P-256 public key")));
  }

  /** Throws 401 MW-AUTH-005 (timestamp) or MW-AUTH-004 (signature); returns when both hold. */
  public void verify(PublicKey key, SignedRequest r, Instant now) {
    Instant at;
    try {
      at = Instant.ofEpochMilli(Long.parseLong(r.timestamp()));
    } catch (NumberFormatException | NullPointerException e) {
      throw new MwException(ErrorCodes.AUTH_STALE_TIMESTAMP, "Request timestamp is not readable");
    }
    if (Duration.between(at, now).abs().compareTo(clockSkew) > 0) {
      throw new MwException(ErrorCodes.AUTH_STALE_TIMESTAMP, "Request timestamp is outside the allowed window");
    }
    if (!signatureHolds(key, r)) {
      throw new MwException(ErrorCodes.AUTH_BAD_SIGNATURE, "Request signature is not valid");
    }
  }

  private static boolean signatureHolds(PublicKey key, SignedRequest r) {
    byte[] sig;
    try {
      sig = Base64.getDecoder().decode(r.signature());
    } catch (IllegalArgumentException | NullPointerException e) {
      return false;
    }
    if (sig.length == 0) {
      return false;
    }
    String algorithm = sig.length == 64 ? "SHA256withECDSAinP1363Format" : "SHA256withECDSA";
    try {
      Signature s = Signature.getInstance(algorithm);
      s.initVerify(key);
      s.update(canonical(r).getBytes(StandardCharsets.UTF_8));
      return s.verify(sig);
    } catch (GeneralSecurityException e) {
      return false; // malformed DER
    }
  }

  private static boolean sameCurve(ECParameterSpec a, ECParameterSpec b) {
    return a.getCurve().equals(b.getCurve())
        && a.getGenerator().equals(b.getGenerator())
        && a.getOrder().equals(b.getOrder())
        && a.getCofactor() == b.getCofactor();
  }

  private static ECParameterSpec p256() {
    try {
      AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
      p.init(new ECGenParameterSpec("secp256r1"));
      return p.getParameterSpec(ECParameterSpec.class);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
