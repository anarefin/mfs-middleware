package mfs.mw.core.mock.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
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
import java.util.UUID;
import java.util.function.UnaryOperator;
import mfs.mw.core.mock.util.TxnHash;

/**
 * mw-auth's transaction assertions as the core sees them (ticket 71), for tests: an in-test P-256
 * key ({@code assertion-sign.v1}) whose public JWK Set is served by a JDK {@link HttpServer} at
 * {@link #jwksUri()} (TestInfrastructure points {@code mw.coremock.assertion.jwks-uri} at it), and
 * {@link #mint} signing claims as mw-auth does. {@link #FOREIGN} signs with a key not in the set.
 */
public final class TestAssertions {

  public static final ECKey KEY = newKey("assertion-sign.v1");
  public static final ECKey FOREIGN = newKey("assertion-sign.v1");
  private static final HttpServer JWKS = startJwks();

  private TestAssertions() {}

  public static String jwksUri() {
    return "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/auth/v1/.well-known/assertion-jwks.json";
  }

  /** The claims mw-auth puts in an assertion for a transfer (iat now, 60 s, channel CUSTOMER_APP). */
  public static JWTClaimsSet.Builder claims(
      String walletId, String txnType, TxnHash.Party payer, TxnHash.Party payee, long amount, String idempotencyKey) {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .issuer("mw-auth")
        .audience("core")
        .subject(walletId)
        .jwtID(UUID.randomUUID().toString())
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(60)))
        .claim("txnHash", TxnHash.of(txnType, amount, "BDT", payer, payee, idempotencyKey, "CUSTOMER_APP"))
        .claim("channel", "CUSTOMER_APP")
        .claim("authMethod", "PIN")
        .claim("newDevice", false);
  }

  public static String mint(JWTClaimsSet claims) {
    return sign(KEY, claims, c -> c);
  }

  public static String sign(ECKey key, JWTClaimsSet claims, UnaryOperator<JWSHeader.Builder> header) {
    try {
      SignedJWT jwt = new SignedJWT(header.apply(new JWSHeader.Builder(JWSAlgorithm.ES256)
          .type(new JOSEObjectType("txn-assertion+jwt")).keyID(key.getKeyID())).build(), claims);
      jwt.sign(new ECDSASigner(key));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ECKey newKey(String kid) {
    try {
      return new ECKeyGenerator(Curve.P_256).keyID(kid).algorithm(JWSAlgorithm.ES256).generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static HttpServer startJwks() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      s.createContext("/auth/v1/.well-known/assertion-jwks.json", ex -> {
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
