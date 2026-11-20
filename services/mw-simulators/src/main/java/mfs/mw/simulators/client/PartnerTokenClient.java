package mfs.mw.simulators.client;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * OAuth2 client credentials with {@code private_key_jwt} (ticket 100b, RFC 7523): the simulator
 * signs a short client assertion with the partner's RSA key and exchanges it for an access token.
 * There is no client secret. The key and the token are never logged.
 */
@Component
public class PartnerTokenClient {

  static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

  private final JsonMapper json;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public PartnerTokenClient(JsonMapper json) {
    this.json = json;
  }

  /**
   * An access token for {@code clientId}. {@code tokenUri} is the realm's token endpoint; the
   * assertion's audience is that realm's issuer (the token URI without
   * {@code /protocol/openid-connect/token}), which is what Keycloak checks.
   */
  public String accessToken(String tokenUri, String clientId, String privateKeyPem, Instant now) {
    String assertion = assertion(tokenUri, clientId, privateKeyPem, now);
    String form = "grant_type=client_credentials&client_id=" + enc(clientId)
        + "&client_assertion_type=" + enc(ASSERTION_TYPE)
        + "&client_assertion=" + enc(assertion);
    try {
      HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(tokenUri))
          .timeout(Duration.ofSeconds(5))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(form))
          .build(), HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200) {
        throw unavailable("token endpoint answered " + res.statusCode());
      }
      String token = json.readTree(res.body()).path("access_token").asString("");
      if (token.isBlank()) {
        throw unavailable("token endpoint returned no access token");
      }
      return token;
    } catch (MwException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable("interrupted");
    } catch (Exception e) {
      throw unavailable("token endpoint unreachable");
    }
  }

  /** The client assertion (RS256). Visible to tests; the private key never leaves this method. */
  String assertion(String tokenUri, String clientId, String privateKeyPem, Instant now) {
    long iat = now.getEpochSecond();
    ObjectNode payload = json.createObjectNode();
    payload.put("iss", clientId);
    payload.put("sub", clientId);
    payload.put("aud", audience(tokenUri));
    payload.put("jti", UUID.randomUUID().toString());
    payload.put("iat", iat);
    payload.put("exp", iat + 60);
    String signingInput = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "." + b64(json.writeValueAsString(payload));
    try {
      Signature sig = Signature.getInstance("SHA256withRSA");
      sig.initSign(privateKey(privateKeyPem));
      sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + "." + b64(sig.sign());
    } catch (MwException e) {
      throw e;
    } catch (Exception e) {
      throw unavailable("partner token key is not an RSA private key");
    }
  }

  /** Realm issuer: the token URI up to {@code /protocol/openid-connect/token}. */
  static String audience(String tokenUri) {
    String marker = "/protocol/openid-connect/token";
    int at = tokenUri.indexOf(marker);
    return at > 0 ? tokenUri.substring(0, at) : tokenUri;
  }

  static PrivateKey privateKey(String pem) {
    boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
    String b64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "").replaceAll("-----END [A-Z ]+-----", "").replaceAll("\\s", "");
    try {
      byte[] der = Base64.getDecoder().decode(b64);
      if (pkcs1) {
        der = pkcs1ToPkcs8(der);
      }
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (Exception e) {
      throw unavailable("partner token key is not an RSA private key");
    }
  }

  /** PKCS#1 RSAPrivateKey to PKCS#8 PrivateKeyInfo, so a key from {@code openssl req} loads. */
  private static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
    byte[] version = {0x02, 0x01, 0x00};
    byte[] algId = {
        0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
    return der(0x30, concat(version, algId, der(0x04, pkcs1)));
  }

  private static byte[] der(int tag, byte[] content) {
    byte[] len = derLength(content.length);
    byte[] out = new byte[1 + len.length + content.length];
    out[0] = (byte) tag;
    System.arraycopy(len, 0, out, 1, len.length);
    System.arraycopy(content, 0, out, 1 + len.length, content.length);
    return out;
  }

  private static byte[] derLength(int n) {
    if (n < 0x80) {
      return new byte[] {(byte) n};
    }
    int bytes = 1;
    for (int x = n; x > 0xff; x >>= 8) {
      bytes++;
    }
    byte[] out = new byte[1 + bytes];
    out[0] = (byte) (0x80 | bytes);
    for (int i = bytes; i >= 1; i--) {
      out[i] = (byte) (n & 0xff);
      n >>= 8;
    }
    return out;
  }

  private static byte[] concat(byte[]... parts) {
    int n = 0;
    for (byte[] p : parts) {
      n += p.length;
    }
    byte[] out = new byte[n];
    int i = 0;
    for (byte[] p : parts) {
      System.arraycopy(p, 0, out, i, p.length);
      i += p.length;
    }
    return out;
  }

  private static String b64(String utf8) {
    return b64(utf8.getBytes(StandardCharsets.UTF_8));
  }

  private static String b64(byte[] raw) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static MwException unavailable(String message) {
    return new MwException(ErrorCodes.SYS_INTERNAL, message).withStatus(HttpStatus.SERVICE_UNAVAILABLE).withRetryable(true);
  }
}
