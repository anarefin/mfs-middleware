package mfs.mw.orchestrator.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Test-only Keycloak stand-in for the internal query API (spec 18.2): one RSA key, served as a JWKS
 * on a local port for the service's JWT decoder, and client-credentials tokens signed with it.
 * {@code TestInfrastructure} points {@code mw.internal-auth.jwk-set-uri} here.
 */
public final class InternalTokens {

  /** Matches {@code mw.internal-auth.issuer} in the test config. */
  public static final String ISSUER = "http://keycloak.test/realms/services";

  public static final String AUDIENCE = "mw-orchestrator";

  private static final RSAKey KEY = newKey();
  private static final RSAKey OTHER_KEY = newKey();
  private static final HttpServer JWKS = startJwks();

  private InternalTokens() {}

  public static String jwkSetUri() {
    return "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/certs";
  }

  /** A valid token of client {@code clientId} for this service. */
  public static String forClient(String clientId) {
    return sign(KEY, ISSUER, List.of(AUDIENCE, "account"), clientId, Instant.now().plusSeconds(300));
  }

  /** Bearer header value for {@link #forClient}. */
  public static String bearer(String clientId) {
    return "Bearer " + forClient(clientId);
  }

  public static String withIssuer(String clientId, String issuer) {
    return sign(KEY, issuer, List.of(AUDIENCE), clientId, Instant.now().plusSeconds(300));
  }

  public static String withAudience(String clientId, String audience) {
    return sign(KEY, ISSUER, List.of(audience), clientId, Instant.now().plusSeconds(300));
  }

  public static String expired(String clientId) {
    return sign(KEY, ISSUER, List.of(AUDIENCE), clientId, Instant.now().minusSeconds(600));
  }

  /** Signed with a key the JWKS does not hold. */
  public static String foreignKey(String clientId) {
    return sign(OTHER_KEY, ISSUER, List.of(AUDIENCE), clientId, Instant.now().plusSeconds(300));
  }

  private static String sign(RSAKey key, String issuer, List<String> aud, String azp, Instant exp) {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(aud)
            .subject("service-account-" + azp)
            .claim("azp", azp)
            .issueTime(Date.from(exp.minusSeconds(300)))
            .expirationTime(Date.from(exp))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    try {
      jwt.sign(new RSASSASigner(key));
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
    return jwt.serialize();
  }

  private static RSAKey newKey() {
    try {
      return new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static HttpServer startJwks() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      s.createContext(
          "/certs",
          ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
              out.write(body);
            }
          });
      s.start();
      return s;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
