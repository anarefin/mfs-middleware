package mfs.mw.crypto.client;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import mfs.mw.crypto.exception.CryptoErrors;

/**
 * Test stand-in of an HSM provider, named {@code pkcs11} so it replaces the real one: keys are JDK
 * keys in memory. {@link #down(boolean)} makes every call fail closed as an unreachable HSM does.
 * {@link #lastDecrypted()} is the very buffer the last {@link #decryptOaep} handed out, so a test can
 * check the caller zeroed it. PKCS#11 itself is tested in a container (Pkcs11ProviderIT, OperationsIT).
 */
public class FakeCryptoProvider implements CryptoProvider {

  private static final SecureRandom RANDOM = new SecureRandom();
  private final Map<String, Object> keys = new ConcurrentHashMap<>();
  private volatile boolean down;
  private volatile byte[] lastDecrypted;

  public void down(boolean value) {
    down = value;
  }

  public Set<String> labels() {
    return Set.copyOf(keys.keySet());
  }

  public Object key(String label) {
    return keys.get(label);
  }

  /** Puts a known HMAC key under {@code label} (known-answer tests). */
  public void putHmacKey(String label, byte[] key) {
    keys.put(label, new SecretKeySpec(key, "HmacSHA256"));
  }

  public byte[] lastDecrypted() {
    return lastDecrypted;
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
            case HMAC_SHA256 -> new SecretKeySpec(random(32), "HmacSHA256");
            case AES_256 -> new SecretKeySpec(random(32), "AES");
            case TDES_2KEY, AES_128 -> new SecretKeySpec(random(16), "AES");
          };
      keys.put(label, key);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public PublicKey publicKey(String label) {
    check("public-key");
    return pair(label, "public-key").getPublic();
  }

  @Override
  public void destroy(String label) {
    check("destroy");
    keys.remove(label);
  }

  @Override
  public byte[] decryptOaep(String label, byte[] ciphertext) {
    check("pin-decrypt");
    try {
      Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
      c.init(Cipher.DECRYPT_MODE, pair(label, "pin-decrypt").getPrivate(),
          new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
      byte[] out = c.doFinal(ciphertext);
      lastDecrypted = out;
      return out;
    } catch (BadPaddingException | javax.crypto.IllegalBlockSizeException e) {
      throw CryptoErrors.badPinBlock();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public List<byte[]> hmac(String label, List<byte[]> messages) {
    check("hmac");
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(secret(label, "hmac"));
      List<byte[]> out = new ArrayList<>();
      for (byte[] m : messages) {
        out.add(mac.doFinal(m));
      }
      return out;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public byte[] sign(String label, SignatureAlgorithm algorithm, byte[] data) {
    check("sign");
    try {
      Signature s =
          switch (algorithm) {
            case RS256 -> Signature.getInstance("SHA256withRSA");
            case PS256, PS512 -> {
              Signature pss = Signature.getInstance("RSASSA-PSS");
              pss.setParameter(algorithm.pssParameters());
              yield pss;
            }
            case ES256 -> Signature.getInstance("SHA256withECDSA");
          };
      s.initSign(pair(label, "sign").getPrivate());
      s.update(data);
      return s.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public byte[] wrapKey(String label, byte[] dataKey) {
    check("wrap");
    try {
      // as Pkcs11Provider: AES-CTR, iv(16) ‖ ciphertext
      byte[] iv = random(16);
      Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, secret(label, "wrap"), new IvParameterSpec(iv));
      byte[] sealed = c.doFinal(dataKey);
      byte[] out = Arrays.copyOf(iv, 16 + sealed.length);
      System.arraycopy(sealed, 0, out, 16, sealed.length);
      return out;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public byte[] unwrapKey(String label, byte[] wrapped) {
    check("unwrap");
    if (wrapped.length <= 16) {
      throw CryptoErrors.badEnvelope();
    }
    try {
      Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
      c.init(Cipher.DECRYPT_MODE, secret(label, "unwrap"), new IvParameterSpec(wrapped, 0, 16));
      return c.doFinal(wrapped, 16, wrapped.length - 16);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
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

  private KeyPair pair(String label, String op) {
    if (!(keys.get(label) instanceof KeyPair pair)) {
      throw CryptoErrors.hsmUnavailable(op, null);
    }
    return pair;
  }

  private SecretKey secret(String label, String op) {
    if (!(keys.get(label) instanceof SecretKey key)) {
      throw CryptoErrors.hsmUnavailable(op, null);
    }
    return key;
  }

  private static byte[] random(int n) {
    byte[] b = new byte[n];
    RANDOM.nextBytes(b);
    return b;
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
