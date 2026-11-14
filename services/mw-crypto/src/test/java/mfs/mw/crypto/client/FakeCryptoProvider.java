package mfs.mw.crypto.client;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mfs.mw.crypto.exception.CryptoErrors;

/**
 * Test stand-in of an HSM provider, named {@code pkcs11} so it replaces the real one: keys are JDK
 * keys in memory. {@link #down(boolean)} makes every call fail closed as an unreachable HSM does.
 * PKCS#11 itself is tested in a container (Pkcs11ProviderIT).
 */
public class FakeCryptoProvider implements CryptoProvider {

  private final Map<String, Object> keys = new ConcurrentHashMap<>();
  private volatile boolean down;

  public void down(boolean value) {
    down = value;
  }

  public Set<String> labels() {
    return Set.copyOf(keys.keySet());
  }

  public Object key(String label) {
    return keys.get(label);
  }

  public void clear() {
    keys.clear();
  }

  @Override
  public String name() {
    return Pkcs11Provider.NAME;
  }

  @Override
  public Set<Capability> capabilities() {
    return EnumSet.of(
        Capability.PIN_HMAC, Capability.JWS, Capability.HMAC, Capability.TOKENISE, Capability.ENVELOPE,
        Capability.XMLDSIG);
  }

  @Override
  public void generateKey(String label, KeyAlgorithm algorithm) {
    check("generate");
    try {
      Object key =
          switch (algorithm) {
            case RSA_3072 -> pair("RSA", 3072);
            case EC_P256 -> ec();
            case HMAC_SHA256 -> "hmac-" + label;
            case AES_256 -> "aes-" + label;
          };
      keys.put(label, key);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public PublicKey publicKey(String label) {
    check("public-key");
    if (!(keys.get(label) instanceof KeyPair pair)) {
      throw CryptoErrors.hsmUnavailable("public-key", null);
    }
    return pair.getPublic();
  }

  @Override
  public void destroy(String label) {
    check("destroy");
    keys.remove(label);
  }

  @Override
  public boolean probe() {
    return !down;
  }

  private void check(String op) {
    if (down) {
      throw CryptoErrors.hsmUnavailable(op, null);
    }
  }

  private static KeyPair pair(String alg, int size) throws GeneralSecurityException {
    KeyPairGenerator g = KeyPairGenerator.getInstance(alg);
    g.initialize(size);
    return g.generateKeyPair();
  }

  private static KeyPair ec() throws GeneralSecurityException {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    return g.generateKeyPair();
  }
}
