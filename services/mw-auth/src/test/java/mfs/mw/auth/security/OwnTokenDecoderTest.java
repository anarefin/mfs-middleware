package mfs.mw.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import mfs.mw.auth.config.TokenProperties;
import mfs.mw.auth.service.JwksService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** mw-auth's verifier of its own access tokens: RS256 by a JWT_SIGN key, typ, issuer, audience, sid. */
class OwnTokenDecoderTest {

  static final TokenProperties PROPS = new TokenProperties("mw-auth", "mw-app", Duration.ofMinutes(5),
      Duration.ofDays(30), Duration.ofMinutes(5), "http://mw-auth:8080/auth/v1/.well-known/jwks.json");
  static RSAKey key;
  static RSAKey other;

  final JwksService jwks = mock(JwksService.class);
  JwtDecoder decoder;

  @BeforeAll
  static void keys() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("jwt-sign.v1").generate();
    other = new RSAKeyGenerator(2048).keyID("jwt-sign.v1").generate();
  }

  JwtDecoder decoder() {
    when(jwks.signingKeySet(any())).thenReturn(new JWKSet(key.toPublicJWK()));
    return OwnTokenDecoder.create(jwks, PROPS);
  }

  static String token(RSAKey signer, String typ, JWTClaimsSet.Builder claims) throws Exception {
    SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType(typ))
        .keyID("jwt-sign.v1").build(), claims.build());
    jwt.sign(new RSASSASigner(signer));
    return jwt.serialize();
  }

  static JWTClaimsSet.Builder claims() {
    return new JWTClaimsSet.Builder().issuer("mw-auth").audience("mw-app").subject("01711000001")
        .claim("sid", "019a4f6e-0000-7a3b-8c4d-5e6f7a8b9c0d").claim("amr", List.of("pin", "device"))
        .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)));
  }

  @Test
  void aTokenOfThisServiceIsAccepted() throws Exception {
    assertThat(decoder().decode(token(key, "at+jwt", claims())).getSubject()).isEqualTo("01711000001");
  }

  @Test
  void anythingElseIsRefused() throws Exception {
    JwtDecoder d = decoder();
    for (String bad : List.of(
        token(other, "at+jwt", claims()),
        token(key, "JWT", claims()),
        token(key, "at+jwt", claims().issuer("http://keycloak:8080/realms/customers")),
        token(key, "at+jwt", claims().audience("core")),
        token(key, "at+jwt", claims().claim("sid", null)),
        token(key, "at+jwt", claims().expirationTime(Date.from(Instant.now().minusSeconds(120)))))) {
      assertThatThrownBy(() -> d.decode(bad)).isInstanceOf(JwtException.class);
    }
  }
}
