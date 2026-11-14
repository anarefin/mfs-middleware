package mfs.mw.crypto.client;

import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;

/** The signature schemes of {@link CryptoProvider#sign} (spec 18.8: RS256, PS256, ES256), by JWS name. */
public enum SignatureAlgorithm {
  /** RSASSA-PKCS1-v1_5 with SHA-256. */
  RS256(KeyAlgorithm.RSA_3072),
  /** RSASSA-PSS with SHA-256, MGF1-SHA-256 and a 32-byte salt (RFC 7518 3.5). */
  PS256(KeyAlgorithm.RSA_3072),
  /** ECDSA P-256 with SHA-256. */
  ES256(KeyAlgorithm.EC_P256);

  private final KeyAlgorithm keyAlgorithm;

  SignatureAlgorithm(KeyAlgorithm keyAlgorithm) {
    this.keyAlgorithm = keyAlgorithm;
  }

  /** The key type that signs with it. */
  public KeyAlgorithm keyAlgorithm() {
    return keyAlgorithm;
  }

  /** The PSS parameters of PS256, null for the others. */
  public PSSParameterSpec pssParameters() {
    return this == PS256 ? new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1) : null;
  }
}
