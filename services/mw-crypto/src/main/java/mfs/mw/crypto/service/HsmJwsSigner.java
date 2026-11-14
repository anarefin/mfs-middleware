package mfs.mw.crypto.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.impl.ECDSA;
import com.nimbusds.jose.jca.JCAContext;
import com.nimbusds.jose.util.Base64URL;
import java.util.Set;
import mfs.mw.crypto.client.SignatureAlgorithm;

/**
 * A Nimbus JOSE {@link JWSSigner} whose private key stays in the HSM: the signing input goes to the
 * provider ({@code CryptoProvider.sign}) under one key version; an ECDSA signature comes back as DER
 * and is turned into the JWS form {@code R ‖ S} (RFC 7518 3.4).
 */
final class HsmJwsSigner implements JWSSigner {

  private final HsmKeys keys;
  private final HsmKeys.Resolved key;
  private final SignatureAlgorithm algorithm;
  private final JCAContext jca = new JCAContext();

  HsmJwsSigner(HsmKeys keys, HsmKeys.Resolved key, SignatureAlgorithm algorithm) {
    this.keys = keys;
    this.key = key;
    this.algorithm = algorithm;
  }

  @Override
  public Base64URL sign(JWSHeader header, byte[] signingInput) throws JOSEException {
    byte[] signature = keys.run("jws-sign", key, p -> p.sign(key.label(), algorithm, signingInput));
    if (algorithm == SignatureAlgorithm.ES256) {
      signature = ECDSA.transcodeSignatureToConcat(signature, 64);
    }
    return Base64URL.encode(signature);
  }

  @Override
  public Set<JWSAlgorithm> supportedJWSAlgorithms() {
    return Set.of(JWSAlgorithm.parse(algorithm.name()));
  }

  @Override
  public JCAContext getJCAContext() {
    return jca;
  }
}
