package mfs.mw.crypto.service;

import java.util.Arrays;
import java.util.Optional;
import mfs.mw.crypto.client.KeyAlgorithm;
import mfs.mw.crypto.client.SignatureAlgorithm;

/** The RFC 9421 section 3.3 algorithms mw-crypto supports (ticket 65), by their registered name. */
enum HttpSignatureAlgorithm {
  ECDSA_P256_SHA256("ecdsa-p256-sha256", KeyAlgorithm.EC_P256, SignatureAlgorithm.ES256),
  RSA_PSS_SHA512("rsa-pss-sha512", KeyAlgorithm.RSA_3072, SignatureAlgorithm.PS512),
  RSA_V1_5_SHA256("rsa-v1_5-sha256", KeyAlgorithm.RSA_3072, SignatureAlgorithm.RS256),
  HMAC_SHA256("hmac-sha256", KeyAlgorithm.HMAC_SHA256, null);

  final String id;
  final KeyAlgorithm keyAlgorithm;
  /** The provider signature, null for HMAC. */
  final SignatureAlgorithm signature;

  HttpSignatureAlgorithm(String id, KeyAlgorithm keyAlgorithm, SignatureAlgorithm signature) {
    this.id = id;
    this.keyAlgorithm = keyAlgorithm;
    this.signature = signature;
  }

  static Optional<HttpSignatureAlgorithm> of(String id) {
    return Arrays.stream(values()).filter(a -> a.id.equals(id)).findFirst();
  }

  /** The default for a key of {@code keyAlgorithm}: RSA keys sign rsa-pss-sha512. */
  static HttpSignatureAlgorithm defaultFor(KeyAlgorithm keyAlgorithm) {
    return switch (keyAlgorithm) {
      case EC_P256 -> ECDSA_P256_SHA256;
      case RSA_3072 -> RSA_PSS_SHA512;
      case HMAC_SHA256 -> HMAC_SHA256;
      case AES_256, AES_128, TDES_2KEY ->
          throw new IllegalArgumentException("no HTTP signature with a cipher key");
    };
  }
}
