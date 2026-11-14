package mfs.mw.crypto.client;

import java.security.PublicKey;
import java.util.List;
import java.util.Set;

/**
 * The provider SPI of the HSM gateway (spec 18.8, D23): one implementation per HSM kind
 * ({@link Pkcs11Provider} now, the payShield provider in ticket 64). A key lives in the provider
 * under its HSM label; callers above the SPI only ever see key ids. Every method fails closed with
 * {@code exception/HsmUnavailableException} ({@code MW-SYS-010}) when the HSM cannot do it.
 * Operations: PIN block decrypt, HMAC, signatures and key wrapping (ticket 63); XML-DSig and HTTP
 * signatures (ticket 65). A malformed caller input (a PIN block that does not decrypt, a wrapped key
 * whose tag does not match) fails with an {@code MW-VAL-*} error, never as an HSM failure.
 */
public interface CryptoProvider {

  /** The provider's name, stored with each key ({@code crypto.key.provider}). */
  String name();

  /** What this provider can do. */
  Set<Capability> capabilities();

  /** Generates a new key of {@code algorithm} in the HSM under {@code label}, never extractable. */
  void generateKey(String label, KeyAlgorithm algorithm);

  /** The public half of the key pair stored under {@code label}. */
  PublicKey publicKey(String label);

  /** Destroys the key stored under {@code label}; absent is not an error. */
  void destroy(String label);

  /**
   * RSA-OAEP decrypt (SHA-256, MGF1-SHA-256, empty label: the PIN transport block of spec 18.7) under
   * the key pair {@code label}. The caller owns the returned clear bytes and zeroes them. A block
   * that does not decrypt fails with {@code MW-VAL-014}.
   */
  byte[] decryptOaep(String label, byte[] ciphertext);

  /** HMAC-SHA256 of every message under the secret key {@code label}, in one HSM session. */
  List<byte[]> hmac(String label, List<byte[]> messages);

  /**
   * The signature over {@code data} by the private key {@code label}, as the JCA returns it (DER for
   * ECDSA, the plain block for RSA).
   */
  byte[] sign(String label, SignatureAlgorithm algorithm, byte[] data);

  /** Wraps a data key under the KEK {@code label}; the result is opaque and only {@link #unwrapKey} reads it. */
  byte[] wrapKey(String label, byte[] dataKey);

  /**
   * The data key {@link #wrapKey} wrapped under {@code label}; the caller zeroes it. A malformed
   * wrapped key fails with {@code MW-VAL-016}; a changed one may unwrap to another key, which the
   * envelope's own GCM tag then refuses.
   */
  byte[] unwrapKey(String label, byte[] wrapped);

  /** A cheap round trip to the HSM: true when it answers and the session is logged in. */
  boolean probe();
}
