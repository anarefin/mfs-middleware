package mfs.mw.perf.app;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An app device of one perf user (ticket 29, spec 9.1): an EC P-256 key and a device id, bound
 * once per run ({@code mfs.mw.perf.stack.Provisioning}). Signs requests exactly as the app does:
 * {@code METHOD\nPATH\nhex(sha256(body))\nTIMESTAMP\nNONCE\nDEVICE-ID}, SHA256withECDSA, Base64.
 */
public final class Device {

  private final String id;
  private final KeyPair key;

  private Device(String id, KeyPair key) {
    this.id = id;
    this.key = key;
  }

  public static Device create() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"));
      return new Device("perf-" + UUID.randomUUID(), g.generateKeyPair());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public String id() {
    return id;
  }

  public PublicKey publicKey() {
    return key.getPublic();
  }

  /** The public key as the binding request carries it (X.509 SubjectPublicKeyInfo, Base64). */
  public String publicKeyBase64() {
    return Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
  }

  /** Bearer token and {@code X-Device-Id} (requests that are not signed). */
  public Map<String, String> headers(String token) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("Authorization", "Bearer " + token);
    h.put("X-Device-Id", id);
    return h;
  }

  /** {@link #headers} plus timestamp, a fresh nonce and the signature over method, path and the exact body. */
  public Map<String, String> signedHeaders(String token, String method, String path, String body) {
    String ts = Long.toString(Instant.now().toEpochMilli());
    String nonce = "perf-" + UUID.randomUUID();
    String canonical = String.join("\n", method, path, sha256Hex(body), ts, nonce, id);
    Map<String, String> h = headers(token);
    h.put("X-Timestamp", ts);
    h.put("X-Nonce", nonce);
    h.put("X-Signature", sign(canonical));
    return h;
  }

  private String sign(String canonical) {
    try {
      Signature s = Signature.getInstance("SHA256withECDSA");
      s.initSign(key.getPrivate());
      s.update(canonical.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(s.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha256Hex(String body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
