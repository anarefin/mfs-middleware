package mfs.mw.crypto.mapper;

import com.nimbusds.jose.Algorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.crypto.dto.KeyView;
import mfs.mw.crypto.dto.PublicKeyView;
import mfs.mw.crypto.entity.CryptoKey;
import org.springframework.stereotype.Component;

/** Key metadata and public keys to the crypto-internal.v1 responses. */
@Component
public class KeyMapper {

  /** Versions (ordered by keyId, version) grouped into one view per key. */
  public KeyView.Page toPage(List<CryptoKey> versions) {
    Map<String, List<CryptoKey>> byKey = new LinkedHashMap<>();
    for (CryptoKey k : versions) {
      byKey.computeIfAbsent(k.keyId(), id -> new ArrayList<>()).add(k);
    }
    return new KeyView.Page(byKey.values().stream().map(this::toView).toList());
  }

  /** One key: its versions, oldest first; purpose, algorithm and provider of the newest. */
  public KeyView toView(List<CryptoKey> versions) {
    CryptoKey newest = versions.getLast();
    return new KeyView(
        newest.keyId(),
        newest.purpose(),
        newest.algorithm(),
        newest.provider(),
        versions.stream()
            .map(k -> new KeyView.Version(k.version(), k.kid(), k.state().name(), k.createdAt(), k.rotatedAt()))
            .toList());
  }

  /**
   * {@code versions.get(i)}'s public key is {@code keys.get(i)}; {@code jwkAlgorithm} and {@code jwkUse}
   * are the JWK {@code alg} and {@code use} of the key's purpose.
   */
  public PublicKeyView toPublic(
      String purpose, String algorithm, String jwkAlgorithm, String jwkUse, List<CryptoKey> versions,
      List<PublicKey> keys) {
    List<PublicKeyView.Version> out = new ArrayList<>();
    for (int i = 0; i < versions.size(); i++) {
      CryptoKey k = versions.get(i);
      PublicKey pub = keys.get(i);
      out.add(new PublicKeyView.Version(k.version(), k.kid(), k.state().name(), pem(pub), jwk(jwkAlgorithm, jwkUse, k.kid(), pub)));
    }
    CryptoKey first = versions.getFirst();
    List<Object> jwks = out.stream().<Object>map(PublicKeyView.Version::jwk).toList();
    return new PublicKeyView(first.keyId(), purpose, algorithm, out, Map.of("keys", jwks));
  }

  static String pem(PublicKey key) {
    String b64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded());
    return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
  }

  static Map<String, Object> jwk(String alg, String use, String kid, PublicKey key) {
    JWK jwk =
        switch (key) {
          case RSAPublicKey rsa ->
              new RSAKey.Builder(rsa).keyID(kid).keyUse(new KeyUse(use))
                  .algorithm(new Algorithm(alg)).build();
          case ECPublicKey ec ->
              new ECKey.Builder(Curve.P_256, ec).keyID(kid).keyUse(new KeyUse(use))
                  .algorithm(new Algorithm(alg)).build();
          default -> throw new IllegalArgumentException("not an RSA or EC public key: " + key.getAlgorithm());
        };
    return jwk.toPublicJWK().toJSONObject();
  }
}
