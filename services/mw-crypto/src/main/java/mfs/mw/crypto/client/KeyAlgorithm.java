package mfs.mw.crypto.client;

/** The key types a {@link CryptoProvider} generates (spec 18.8 key hierarchy). */
public enum KeyAlgorithm {
  /** RSA 3072 key pair: PIN transport (OAEP SHA-256), JWT signing (RS256), XML-DSig. */
  RSA_3072(true),
  /** EC P-256 key pair: ES256 signatures (assertions, audit anchor). */
  EC_P256(true),
  /** 256-bit HMAC-SHA256 secret: PIN HMAC, tokenisation. */
  HMAC_SHA256(false),
  /** AES-256 secret: key encryption keys of envelope encryption. */
  AES_256(false),
  /** Double length triple DES key (payShield, under the LMK): ZPK, TPK, PVK (ticket 64). */
  TDES_2KEY(false),
  /** AES-128 key (payShield, under the LMK): ZPK and TPK of ISO 9564 format 4 PIN blocks (ticket 64). */
  AES_128(false);

  private final boolean asymmetric;

  KeyAlgorithm(boolean asymmetric) {
    this.asymmetric = asymmetric;
  }

  /** Whether the key has a public half. */
  public boolean asymmetric() {
    return asymmetric;
  }
}
