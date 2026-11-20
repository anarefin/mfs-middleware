package mfs.mw.simulators.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import mfs.mw.simulators.exception.MwException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The simulator's OAuth2 client-credentials token (ticket 100b): a private_key_jwt assertion, no
 * client secret, exchanged for the token endpoint's access token.
 */
class PartnerTokenClientTest {

  static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");
  static final String TOKEN = "edge-token-100b";

  @Test
  void aPrivateKeyJwtAssertionHasNoClientSecretAndReturnsTheAccessToken() throws Exception {
    KeyPair keys = rsa();
    JsonMapper json = JsonMapper.builder().build();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/realms/partners/protocol/openid-connect/token", ex -> {
      String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(form).contains("grant_type=client_credentials");
      assertThat(form).contains("client_id=partner-sim");
      assertThat(form).contains("client_assertion_type=" + enc(PartnerTokenClient.ASSERTION_TYPE));
      assertThat(form).doesNotContain("client_secret");
      String assertion = value(form, "client_assertion");
      JsonNode claims;
      try {
        claims = verify(assertion, keys);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      assertThat(claims.path("iss").asString()).isEqualTo("partner-sim");
      assertThat(claims.path("sub").asString()).isEqualTo("partner-sim");
      assertThat(claims.path("aud").asString())
          .isEqualTo("http://127.0.0.1:" + server.getAddress().getPort() + "/realms/partners");
      assertThat(claims.path("iat").asLong()).isEqualTo(NOW.getEpochSecond());
      assertThat(claims.path("exp").asLong()).isEqualTo(NOW.getEpochSecond() + 60);
      byte[] body = ("{\"access_token\":\"" + TOKEN + "\",\"expires_in\":300}").getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });
    server.start();
    try {
      String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/partners/protocol/openid-connect/token";
      PartnerTokenClient client = new PartnerTokenClient(json);
      assertThat(client.accessToken(uri, "partner-sim", pem("PRIVATE KEY", keys.getPrivate().getEncoded()), NOW))
          .isEqualTo(TOKEN);
      assertThat(client.accessToken(uri, "partner-sim", pem("RSA PRIVATE KEY", pkcs1(keys.getPrivate().getEncoded())), NOW))
          .isEqualTo(TOKEN);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void aTokenEndpointThatRefusesAnswersUnavailableWithoutTheKey() {
    PartnerTokenClient client = new PartnerTokenClient(JsonMapper.builder().build());
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> client.accessToken("http://127.0.0.1:1/realms/partners/protocol/openid-connect/token",
                "partner-sim", pem("PRIVATE KEY", rsa().getPrivate().getEncoded()), NOW))
        .isInstanceOf(MwException.class)
        .hasMessageContaining("unreachable");
  }

  private static JsonNode verify(String jwt, KeyPair keys) throws Exception {
    String[] parts = jwt.split("\\.");
    assertThat(parts).hasSize(3);
    Signature sig = Signature.getInstance("SHA256withRSA");
    sig.initVerify(keys.getPublic());
    sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(sig.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    return JsonMapper.builder().build().readTree(Base64.getUrlDecoder().decode(parts[1]));
  }

  private static String value(String form, String name) {
    for (String part : form.split("&")) {
      if (part.startsWith(name + "=")) {
        return java.net.URLDecoder.decode(part.substring(name.length() + 1), StandardCharsets.UTF_8);
      }
    }
    throw new AssertionError("no " + name);
  }

  private static String enc(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static KeyPair rsa() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    return g.generateKeyPair();
  }

  private static String pem(String kind, byte[] der) {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    return "-----BEGIN " + kind + "-----\n" + body + "\n-----END " + kind + "-----\n";
  }

  /** The PKCS#1 RSAPrivateKey inside a PKCS#8 PrivateKeyInfo (Java's getEncoded). */
  private static byte[] pkcs1(byte[] pkcs8) {
    int i = content(pkcs8, 0, 0x30);
    i = after(pkcs8, i, 0x02);
    i = after(pkcs8, i, 0x30);
    int start = content(pkcs8, i, 0x04);
    int[] len = length(pkcs8, i + 1);
    byte[] out = new byte[len[0]];
    System.arraycopy(pkcs8, start, out, 0, len[0]);
    return out;
  }

  private static int content(byte[] der, int i, int tag) {
    if ((der[i++] & 0xff) != tag) {
      throw new IllegalStateException("tag " + tag);
    }
    return length(der, i)[1];
  }

  private static int after(byte[] der, int i, int tag) {
    if ((der[i++] & 0xff) != tag) {
      throw new IllegalStateException("tag " + tag);
    }
    int[] len = length(der, i);
    return len[1] + len[0];
  }

  private static int[] length(byte[] der, int i) {
    int n = der[i++] & 0xff;
    if (n < 0x80) {
      return new int[] {n, i};
    }
    int bytes = n & 0x7f;
    int len = 0;
    for (int b = 0; b < bytes; b++) {
      len = (len << 8) | (der[i++] & 0xff);
    }
    return new int[] {len, i};
  }
}
