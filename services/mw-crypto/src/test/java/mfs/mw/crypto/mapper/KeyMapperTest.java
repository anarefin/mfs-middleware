package mfs.mw.crypto.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import mfs.mw.crypto.dto.KeyView;
import mfs.mw.crypto.dto.PublicKeyView;
import mfs.mw.crypto.entity.CryptoKey;
import mfs.mw.crypto.entity.KeyState;
import org.junit.jupiter.api.Test;

class KeyMapperTest {

  static final Instant T0 = Instant.parse("2026-11-14T08:00:00Z");
  final KeyMapper mapper = new KeyMapper();

  static CryptoKey key(String id, int v, String purpose, String alg, KeyState s, Instant rotated) {
    return new CryptoKey(id, v, purpose, alg, "pkcs11", id + ".v" + v, s, T0.plusSeconds(v), rotated);
  }

  @Test
  void groupsVersionsByKey() {
    KeyView.Page page = mapper.toPage(List.of(
        key("jwt-sign", 1, "JWT_SIGN", "RSA_3072", KeyState.VERIFY_ONLY, T0.plusSeconds(9)),
        key("jwt-sign", 2, "JWT_SIGN", "RSA_3072", KeyState.ACTIVE, null),
        key("pin-hmac", 1, "PIN_HMAC", "HMAC_SHA256", KeyState.ACTIVE, null)));
    assertThat(page.items()).extracting(KeyView::keyId).containsExactly("jwt-sign", "pin-hmac");
    KeyView jwt = page.items().getFirst();
    assertThat(jwt.purpose()).isEqualTo("JWT_SIGN");
    assertThat(jwt.algorithm()).isEqualTo("RSA_3072");
    assertThat(jwt.provider()).isEqualTo("pkcs11");
    assertThat(jwt.versions())
        .containsExactly(
            new KeyView.Version(1, "jwt-sign.v1", "VERIFY_ONLY", T0.plusSeconds(1), T0.plusSeconds(9)),
            new KeyView.Version(2, "jwt-sign.v2", "ACTIVE", T0.plusSeconds(2), null));
  }

  @Test
  void publishesAnRsaKeyAsPemAndJwk() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(3072);
    PublicKey pub = g.generateKeyPair().getPublic();
    CryptoKey k = key("jwt-sign", 1, "JWT_SIGN", "RSA_3072", KeyState.ACTIVE, null);

    PublicKeyView view = mapper.toPublic("JWT_SIGN", "RSA_3072", "RS256", "sig", List.of(k), List.of(pub));

    PublicKeyView.Version v = view.versions().getFirst();
    assertThat(v.pem()).startsWith("-----BEGIN PUBLIC KEY-----\n").endsWith("-----END PUBLIC KEY-----\n");
    String body = v.pem().replaceAll("-----[A-Z ]+-----", "").replace("\n", "");
    PublicKey parsed = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
    assertThat(parsed).isEqualTo(pub);
    RSAKey jwk = JWK.parse(v.jwk()).toRSAKey();
    assertThat(jwk.toRSAPublicKey()).isEqualTo(pub);
    assertThat(jwk.getKeyID()).isEqualTo("jwt-sign.v1");
    assertThat(jwk.getAlgorithm().getName()).isEqualTo("RS256");
    assertThat(jwk.getKeyUse().identifier()).isEqualTo("sig");
    assertThat(jwk.isPrivate()).isFalse();
    assertThat(view.jwks()).containsOnlyKeys("keys");
    assertThat(view.jwks().get("keys")).isEqualTo(List.of(v.jwk()));
  }

  @Test
  void publishesAnEcKeyAsAP256Jwk() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    PublicKey pub = g.generateKeyPair().getPublic();
    CryptoKey k = key("assertion-sign", 3, "ASSERTION_SIGN", "EC_P256", KeyState.ACTIVE, null);

    Map<String, Object> jwkJson =
        mapper.toPublic("ASSERTION_SIGN", "EC_P256", "ES256", "sig", List.of(k), List.of(pub)).versions().getFirst().jwk();

    ECKey jwk = JWK.parse(jwkJson).toECKey();
    assertThat(jwk.getCurve().getName()).isEqualTo("P-256");
    assertThat(jwk.toECPublicKey()).isEqualTo(pub);
    assertThat(jwk.getKeyID()).isEqualTo("assertion-sign.v3");
    assertThat(jwk.getAlgorithm().getName()).isEqualTo("ES256");
  }
}
