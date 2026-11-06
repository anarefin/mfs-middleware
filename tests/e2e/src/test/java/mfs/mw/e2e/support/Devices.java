package mfs.mw.e2e.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * The app's bound device (ticket 29, spec 9.1): one EC P-256 key pair and device id per user for
 * the run, bound on first use with a signed {@code POST /app/v1/devices} carrying the binding OTP
 * (review A5: asked for with {@code POST /app/v1/devices/otp} and read, as the customer would, from
 * the SMS the SMSC simulator received for the token's wallet); a session is reopened
 * ({@code POST /app/v1/sessions}) when the user was idle long enough for the gateway's 5 min idle
 * timeout to end it. {@link #signedHeaders} builds the signature headers exactly as an app would
 * (canonical text in contracts/api/mw-app-gateway/app.v1.openapi.yaml).
 */
public final class Devices {

  /** Reopen before the gateway's idle timeout (5 min) can have ended the session. */
  private static final Duration REOPEN_AFTER = Duration.ofMinutes(4);

  /** A device of one user: id, key and when it last talked to the gateway. */
  public static final class Device {
    final String id;
    final KeyPair key;
    volatile Instant lastUse = Instant.EPOCH;
    volatile boolean bound;

    Device(String id, KeyPair key) {
      this.id = id;
      this.key = key;
    }

    public String id() {
      return id;
    }
  }

  private static final Map<String, Device> DEVICES = new ConcurrentHashMap<>();
  /** The code of the English binding OTP SMS (mw-notify template DEVICE_BIND_OTP). */
  private static final Pattern OTP = Pattern.compile("Your Bits code to bind a new device is ([0-9]{6})");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Devices() {}

  /** The user's device, bound and with a live session. */
  public static synchronized Device ready(String user) {
    Device d = DEVICES.computeIfAbsent(user, u -> new Device("e2e-" + UUID.randomUUID(), newKey()));
    if (!d.bound) {
      String otp = bindingOtp(user, d);
      String body = "{\"publicKey\":\"" + Base64.getEncoder().encodeToString(d.key.getPublic().getEncoded())
          + "\",\"otp\":\"" + otp + "\"}";
      Http.Response r = Http.postRaw(Stack.get().url("app/v1/devices"), signedHeaders(user, d, "POST", "/app/v1/devices", body), body);
      if (r.status() != 201) {
        throw new IllegalStateException("binding the device of " + user + " failed: " + r);
      }
      d.bound = true;
      d.lastUse = Instant.now();
    } else if (Duration.between(d.lastUse, Instant.now()).compareTo(REOPEN_AFTER) > 0) {
      Http.Response r = Http.postRaw(Stack.get().url("app/v1/sessions"), signedHeaders(user, d, "POST", "/app/v1/sessions", ""), "");
      if (r.status() != 200) {
        throw new IllegalStateException("opening a session for " + user + " failed: " + r);
      }
      d.lastUse = Instant.now();
    }
    return d;
  }

  /**
   * Asks for a binding OTP for {@code d} and waits for its SMS to the token's wallet at the SMSC
   * simulator (mw.notify.request.v1 -> mw-notify -> SMPP); returns the new code.
   */
  private static String bindingOtp(String user, Device d) {
    String wallet = walletOf(Keycloak.customerToken(user));
    List<String> before = otpsTo(wallet);
    Map<String, String> h = new LinkedHashMap<>();
    h.put("Authorization", "Bearer " + Keycloak.customerToken(user));
    h.put("X-Device-Id", d.id);
    Http.Response r = Http.postRaw(Stack.get().url("app/v1/devices/otp"), h, "{\"locale\":\"en\"}");
    if (r.status() != 202) {
      throw new IllegalStateException("asking for the binding OTP of " + user + " failed: " + r);
    }
    List<String> fresh = Waits.until(
        () -> otpsTo(wallet).stream().filter(c -> !before.contains(c)).toList(), l -> !l.isEmpty(),
        Duration.ofSeconds(30));
    if (fresh.isEmpty()) {
      throw new IllegalStateException("no binding OTP SMS reached the wallet of " + user);
    }
    return fresh.getLast();
  }

  private static List<String> otpsTo(String wallet) {
    return Smsc.messagesTo(wallet).stream()
        .map(m -> OTP.matcher(m.text()))
        .filter(Matcher::find)
        .map(m -> m.group(1))
        .toList();
  }

  /** The {@code msisdn} claim of a customer token (the wallet the gateway sends the OTP to). */
  private static String walletOf(String token) {
    String[] parts = token.split("\\.");
    byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
    return JSON.readTree(new String(payload, StandardCharsets.UTF_8)).path("msisdn").asString();
  }

  /** Bearer token and {@code X-Device-Id} of the user's ready device (requests that are not signed). */
  public static Map<String, String> headers(String user) {
    Device d = ready(user);
    d.lastUse = Instant.now();
    Map<String, String> h = new LinkedHashMap<>();
    h.put("Authorization", "Bearer " + Keycloak.customerToken(user));
    h.put("X-Device-Id", d.id);
    return h;
  }

  /** Bearer token plus the four signature headers over {@code method}, {@code path} and the exact body. */
  public static Map<String, String> signed(String user, String method, String path, String body) {
    Device d = ready(user);
    d.lastUse = Instant.now();
    return signedHeaders(user, d, method, path, body);
  }

  private static Map<String, String> signedHeaders(String user, Device d, String method, String path, String body) {
    String ts = Long.toString(Instant.now().toEpochMilli());
    String nonce = "e2e-" + UUID.randomUUID();
    String canonical = String.join("\n", method, path, sha256Hex(body), ts, nonce, d.id);
    Map<String, String> h = new LinkedHashMap<>();
    h.put("Authorization", "Bearer " + Keycloak.customerToken(user));
    h.put("X-Device-Id", d.id);
    h.put("X-Timestamp", ts);
    h.put("X-Nonce", nonce);
    h.put("X-Signature", sign(d.key, canonical));
    return h;
  }

  private static String sign(KeyPair key, String canonical) {
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

  private static KeyPair newKey() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"));
      return g.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
