package mfs.mw.crypto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import mfs.mw.crypto.config.CryptoProperties;
import mfs.mw.crypto.dto.JwsSignRequest;
import mfs.mw.crypto.dto.JwsSignResponse;
import mfs.mw.crypto.entity.KeyState;
import mfs.mw.crypto.exception.MwException;
import mfs.mw.crypto.mapper.KeyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** JWS sign above the provider SPI (spec 18.8): RS256 and PS256 with RSA, ES256 with EC, kid, JWKS. */
class JwsServiceTest {

  OperationFixture f;
  JwsService jws;

  @BeforeEach
  void setUp() {
    f = new OperationFixture()
        .key("jwt-sign", 1, "JWT_SIGN", KeyState.VERIFY_ONLY)
        .key("jwt-sign", 2, "JWT_SIGN", KeyState.ACTIVE)
        .key("assertion-sign", 1, "ASSERTION_SIGN", KeyState.ACTIVE)
        .key("pin-hmac", 1, "PIN_HMAC", KeyState.ACTIVE)
        .key("xmldsig", 1, "XMLDSIG", KeyState.ACTIVE);
    CryptoProperties props = new CryptoProperties("pkcs11", false, List.of(), Duration.ofSeconds(30),
        Duration.ofSeconds(10), new CryptoProperties.Pkcs11("/nope", 0, "", 1, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    KeyService keyService = new KeyService(f.repo, List.of(f.hsm), props, new KeyMapper(), Clock.systemUTC(), f.meters);
    jws = new JwsService(f.keys, keyService, f.callers);
  }

  RSAPublicKey rsa(String label) {
    return (RSAPublicKey) ((KeyPair) f.hsm.key(label)).getPublic();
  }

  static String code(Throwable e) {
    return ((MwException) e).code();
  }

  @Test
  void signsRs256ByDefaultWithTheActiveVersionAsKid() throws Exception {
    JwsSignResponse r = jws.sign(new JwsSignRequest(null, "JWT_SIGN", null, Map.of("typ", "at+jwt"),
        Map.of("sub", "w-1", "exp", 1_800_000_000L)));
    JWSObject parsed = JWSObject.parse(r.jws());
    assertThat(r.kid()).isEqualTo("jwt-sign.v2");
    assertThat(r.alg()).isEqualTo("RS256");
    assertThat(parsed.getHeader().getKeyID()).isEqualTo("jwt-sign.v2");
    assertThat(parsed.getHeader().getType().getType()).isEqualTo("at+jwt");
    assertThat(parsed.getPayload().toJSONObject()).containsEntry("sub", "w-1").containsEntry("exp", 1_800_000_000L);
    assertThat(parsed.verify(new RSASSAVerifier(rsa("jwt-sign.v2")))).isTrue();
    assertThat(parsed.verify(new RSASSAVerifier(rsa("jwt-sign.v1")))).isFalse();
  }

  @Test
  void signsPs256WithAnRsaKeyAndEs256WithAnEcKey() throws Exception {
    JWSObject ps = JWSObject.parse(jws.sign(new JwsSignRequest("jwt-sign", null, "PS256", null, Map.of("a", 1))).jws());
    assertThat(ps.getHeader().getAlgorithm().getName()).isEqualTo("PS256");
    assertThat(ps.verify(new RSASSAVerifier(rsa("jwt-sign.v2")))).isTrue();

    JWSObject es = JWSObject.parse(jws.sign(new JwsSignRequest(null, "ASSERTION_SIGN", null, null, Map.of("a", 1))).jws());
    assertThat(es.getHeader().getAlgorithm().getName()).isEqualTo("ES256");
    assertThat(es.getSignature().decode()).hasSize(64);
    assertThat(es.verify(new ECDSAVerifier((ECPublicKey) ((KeyPair) f.hsm.key("assertion-sign.v1")).getPublic())))
        .isTrue();
  }

  @Test
  void refusesAWrongAlgorithmKeyOrHeader() {
    assertThatThrownBy(() -> jws.sign(new JwsSignRequest("assertion-sign", null, "RS256", null, Map.of())))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
    assertThatThrownBy(() -> jws.sign(new JwsSignRequest("jwt-sign.v1", null, null, null, Map.of())))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
    assertThatThrownBy(() -> jws.sign(new JwsSignRequest("pin-hmac", null, null, null, Map.of())))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
    assertThatThrownBy(() -> jws.sign(new JwsSignRequest("xmldsig", null, null, null, Map.of())))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
    assertThatThrownBy(() -> jws.sign(new JwsSignRequest("jwt-sign", "JWT_SIGN", null, null, Map.of())))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
    assertThatThrownBy(() -> jws.sign(new JwsSignRequest("jwt-sign", null, null, Map.of("jku", "http://evil"), Map.of())))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
  }

  @Test
  void theJwksHoldsTheCurrentAndThePreviousVersion() throws Exception {
    JWKSet set = JWKSet.parse(jws.jwks("JWT_SIGN"));
    assertThat(set.getKeys()).extracting(JWK::getKeyID).containsExactly("jwt-sign.v1", "jwt-sign.v2");
    assertThat(set.getKeys()).allSatisfy(k -> assertThat(k.isPrivate()).isFalse());
    assertThatThrownBy(() -> jws.jwks("PIN_HMAC")).satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-013"));
    assertThat(JWKSet.parse(jws.jwks("AUDIT_ANCHOR_SIGN")).getKeys()).isEmpty();
  }
}
