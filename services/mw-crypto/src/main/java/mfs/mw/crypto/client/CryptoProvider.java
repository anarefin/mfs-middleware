package mfs.mw.crypto.client;

import java.security.PublicKey;
import java.util.Set;

/**
 * The provider SPI of the HSM gateway (spec 18.8, D23): one implementation per HSM kind
 * ({@link Pkcs11Provider} now, the payShield provider in ticket 64). A key lives in the provider
 * under its HSM label; callers above the SPI only ever see key ids. Every method fails closed with
 * {@code exception/HsmUnavailableException} ({@code MW-SYS-010}) when the HSM cannot do it.
 * Operations (sign, HMAC, PIN verify, ...) are added by tickets 63–65.
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

  /** A cheap round trip to the HSM: true when it answers and the session is logged in. */
  boolean probe();
}
