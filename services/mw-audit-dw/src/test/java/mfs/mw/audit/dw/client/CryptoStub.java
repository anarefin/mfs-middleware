package mfs.mw.audit.dw.client;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only stand-in for the two remote parties of the anchor (ticket 74): the Keycloak token
 * endpoint ({@code /token}) and mw-crypto's {@code /internal/v1/jws/sign} and {@code
 * /internal/v1/jwks/AUDIT_ANCHOR_SIGN}, with one EC P-256 key (kid {@link #KID}). Plain HTTP: the
 * mTLS side is proven by {@code MtlsClientIT}. No real secrets.
 */
public final class CryptoStub implements AutoCloseable {

  public static final String KID = "audit-anchor.v1";
  public static final String TOKEN = "stub-service-token";

  private final ECKey key = newKey(KID);
  private final HttpServer server;
  /** Bodies of the sign requests, in order. */
  public final List<Map<String, Object>> signRequests = new CopyOnWriteArrayList<>();
  /** Authorization headers of the token requests. */
  public final List<String> tokenAuth = new CopyOnWriteArrayList<>();
  private volatile int signStatus = 200;

  private CryptoStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", this::token);
    server.createContext("/internal/v1/jws/sign", this::sign);
    server.createContext("/internal/v1/jwks/AUDIT_ANCHOR_SIGN", this::jwks);
    server.start();
  }

  public static CryptoStub start() {
    try {
      return new CryptoStub();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public String tokenUri() {
    return baseUrl() + "/token";
  }

  /** The next sign calls answer this status (503: mw-crypto down). */
  public void signStatus(int status) {
    this.signStatus = status;
  }

  /** A fresh signature by the stub's own (the "real") key: what a re-signing insider would make. */
  public String signWithStubKey(String payloadJson) {
    return signWith(key, payloadJson);
  }

  /** The same payload, signed by a key mw-crypto's JWKS does not hold (but the same kid). */
  public static String signWithForeignKey(String payloadJson) {
    return signWith(newKey(KID), payloadJson);
  }

  private static String signWith(ECKey key, String payloadJson) {
    try {
      JWSObject jws =
          new JWSObject(
              new JWSHeader.Builder(JWSAlgorithm.ES256)
                  .keyID(key.getKeyID())
                  .type(new JOSEObjectType("mw-audit-anchor+jws"))
                  .build(),
              new Payload(payloadJson));
      jws.sign(new ECDSASigner(key));
      return jws.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private void token(HttpExchange ex) throws IOException {
    tokenAuth.add(ex.getRequestHeaders().getFirst("Authorization"));
    reply(ex, 200, "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":300,\"token_type\":\"Bearer\"}");
  }

  private void sign(HttpExchange ex) throws IOException {
    if (!("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
      reply(ex, 401, "{\"code\":\"MW-AUTH-001\"}");
      return;
    }
    if (signStatus != 200) {
      reply(ex, signStatus, "{\"code\":\"MW-SYS-010\"}");
      return;
    }
    try {
      Map<String, Object> body =
          JSONObjectUtils.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      signRequests.add(body);
      @SuppressWarnings("unchecked")
      Map<String, Object> payload = (Map<String, Object>) body.get("payload");
      String jws = signWith(key, JSONObjectUtils.toJSONString(payload));
      reply(ex, 200, "{\"jws\":\"" + jws + "\",\"kid\":\"" + KID + "\",\"alg\":\"ES256\"}");
    } catch (ParseException e) {
      reply(ex, 400, "{\"code\":\"MW-VAL-001\"}");
    }
  }

  private void jwks(HttpExchange ex) throws IOException {
    reply(ex, 200, new JWKSet(key.toPublicJWK()).toString());
  }

  private static void reply(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static ECKey newKey(String kid) {
    try {
      return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
