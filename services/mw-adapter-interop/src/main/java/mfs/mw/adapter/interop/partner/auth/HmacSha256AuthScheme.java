package mfs.mw.adapter.interop.partner.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import mfs.mw.adapter.interop.partner.AuthScheme;
import mfs.mw.adapter.interop.partner.CredentialRef;
import mfs.mw.adapter.interop.partner.CredentialResolver;
import mfs.mw.adapter.interop.partner.InboundCall;
import mfs.mw.adapter.interop.partner.OutboundCall;

/**
 * {@code hmac-sha256}: the request is signed with a shared secret. String to sign, one item per
 * line: HTTP method, path and query, timestamp (epoch seconds), lower-case hex SHA-256 of the
 * body. Headers: the Base64 HMAC-SHA256 in {@code X-Signature}, the timestamp in {@code
 * X-Timestamp}, and {@code X-Key-Id} when a {@code key-id} is configured. Verification rejects a
 * timestamp more than {@code max-skew-seconds} (default 300) away from now, a wrong key id, and a
 * wrong signature (constant-time comparison).
 *
 * <p>Replay protection (ticket 99, FRS PLT-008): an inbound request (callback) must also carry a
 * nonce in {@code X-Nonce} (16 to 128 of {@code A-Z a-z 0-9 . _ -}), signed as its own line between
 * the timestamp and the body hash; {@link #replayNonce} hands it to the gateway, which lets each
 * nonce through once per partner ({@code partner/auth/CallbackNonces}) for {@link #replayWindow}
 * (twice the skew plus 30 s, so a nonce is never forgotten while its timestamp is acceptable). An
 * outbound request carries no nonce (the partner's own scheme), so its string to sign keeps four
 * lines.
 */
public final class HmacSha256AuthScheme implements AuthScheme {

  public static final String SIGNATURE_HEADER = "X-Signature";
  public static final String TIMESTAMP_HEADER = "X-Timestamp";
  public static final String KEY_ID_HEADER = "X-Key-Id";
  public static final String NONCE_HEADER = "X-Nonce";
  private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9._-]{16,128}");
  private static final Duration WINDOW_MARGIN = Duration.ofSeconds(30);

  private final CredentialResolver credentials;
  private final Clock clock;
  private final String signatureHeader;
  private final String timestampHeader;
  private final String keyId;
  private final Duration maxSkew;

  public HmacSha256AuthScheme(
      CredentialResolver credentials,
      Clock clock,
      String signatureHeader,
      String timestampHeader,
      String keyId,
      Duration maxSkew) {
    this.credentials = credentials;
    this.clock = clock;
    this.signatureHeader = signatureHeader;
    this.timestampHeader = timestampHeader;
    this.keyId = keyId;
    this.maxSkew = maxSkew;
  }

  @Override
  public String name() {
    return "hmac-sha256";
  }

  @Override
  public void apply(OutboundCall call, CredentialRef ref) {
    String timestamp = Long.toString(clock.instant().getEpochSecond());
    call.header(timestampHeader, timestamp);
    if (keyId != null) {
      call.header(KEY_ID_HEADER, keyId);
    }
    call.header(
        signatureHeader,
        sign(credentials.secret(ref), call.method(), call.pathAndQuery(), timestamp, null, call.body()));
  }

  @Override
  public boolean verify(InboundCall call, CredentialRef ref) {
    String signature = call.header(signatureHeader);
    String timestamp = call.header(timestampHeader);
    String nonce = call.header(NONCE_HEADER);
    if (signature == null || timestamp == null || nonce == null || !NONCE.matcher(nonce).matches()) {
      return false;
    }
    if (keyId != null && !keyId.equals(call.header(KEY_ID_HEADER))) {
      return false;
    }
    long seconds;
    try {
      seconds = Long.parseLong(timestamp);
    } catch (NumberFormatException e) {
      return false;
    }
    if (Math.abs(clock.instant().getEpochSecond() - seconds) > maxSkew.toSeconds()) {
      return false;
    }
    String expected =
        sign(credentials.secret(ref), call.method(), call.pathAndQuery(), timestamp, nonce, call.body());
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
  }

  @Override
  public Optional<String> replayNonce(InboundCall call) {
    return Optional.ofNullable(call.header(NONCE_HEADER));
  }

  @Override
  public Duration replayWindow() {
    return maxSkew.multipliedBy(2).plus(WINDOW_MARGIN);
  }

  /**
   * Base64 HMAC-SHA256 over the string to sign (see the class comment); {@code nonce} null for an
   * outbound request (no nonce line).
   */
  public static String sign(
      String secret, String method, String pathAndQuery, String timestamp, String nonce, byte[] body) {
    try {
      String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
      String toSign =
          method + "\n" + pathAndQuery + "\n" + timestamp + "\n" + (nonce == null ? "" : nonce + "\n") + bodyHash;
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }
}
