package mfs.mw.app.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import mfs.mw.app.gateway.config.AppGatewayProperties;
import mfs.mw.app.gateway.config.SecurityConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * The app token as mw-app-gateway verifies it since ticket 69 (spec 18.7, D21): an mw-auth access
 * token, RS256 (the D21 amendment), header {@code typ at+jwt}, signed by a key of mw-auth's JWK Set
 * (served here by an in-test HTTP server, as {@code /auth/v1/.well-known/jwks.json}), the exact
 * mw-auth issuer and audience {@code mw-app}, not expired. A Keycloak {@code customers} token (another
 * issuer) or an ES256 token is refused.
 */
class AppTokenDecoderTest {

  static RSAKey key;
  static HttpServer jwks;
  static JwtDecoder decoder;

  @BeforeAll
  static void start() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("jwt-sign.v1").generate();
    jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
    jwks.createContext("/auth/v1/.well-known/jwks.json", ex -> {
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });
    jwks.start();
    decoder = SecurityConfig.appTokenDecoder(new AppGatewayProperties.Jwt(
        "http://127.0.0.1:" + jwks.getAddress().getPort() + "/auth/v1/.well-known/jwks.json", "mw-auth", "mw-app"));
  }

  @AfterAll
  static void stop() {
    jwks.stop(0);
  }

  static JWTClaimsSet.Builder claims() {
    return new JWTClaimsSet.Builder().issuer("mw-auth").audience("mw-app").subject("01711000001")
        .claim("msisdn", "01711000001").claim("role", "customer").claim("amr", List.of("pin", "device"))
        .claim("deviceId", "device-0001").claim("sid", "019a4f6e-0000-7a3b-8c4d-5e6f7a8b9c0d")
        .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)));
  }

  static String rs256(String typ, JWTClaimsSet.Builder c) throws Exception {
    SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType(typ)).keyID(key.getKeyID()).build(), c.build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  @Test
  void anMwAuthAccessTokenIsAccepted() throws Exception {
    var jwt = decoder.decode(rs256("at+jwt", claims()));
    assertThat(jwt.getClaimAsString("role")).isEqualTo("customer");
    assertThat(jwt.getClaimAsString("deviceId")).isEqualTo("device-0001");
  }

  @Test
  void aKeycloakCustomersTokenIsRefused() throws Exception {
    assertThatThrownBy(() -> decoder.decode(rs256("JWT", claims().issuer("http://keycloak:8080/realms/customers"))))
        .isInstanceOf(JwtException.class);
    assertThatThrownBy(() -> decoder.decode(rs256("at+jwt", claims().issuer("http://keycloak:8080/realms/customers"))))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void anotherAudienceTypeOrAnExpiredTokenIsRefused() throws Exception {
    for (String bad : List.of(
        rs256("at+jwt", claims().audience("core")),
        rs256("JWT", claims()),
        rs256("at+jwt", claims().expirationTime(Date.from(Instant.now().minusSeconds(120)))))) {
      assertThatThrownBy(() -> decoder.decode(bad)).isInstanceOf(JwtException.class);
    }
  }

  @Test
  void anEs256TokenIsRefused() throws Exception {
    ECKey ec = new ECKeyGenerator(Curve.P_256).keyID(key.getKeyID()).generate();
    SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType("at+jwt"))
        .keyID(ec.getKeyID()).build(), claims().build());
    jwt.sign(new ECDSASigner(ec));
    assertThatThrownBy(() -> decoder.decode(jwt.serialize())).isInstanceOf(JwtException.class);
  }
}
