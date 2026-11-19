package mfs.mw.crypto.client;

import jakarta.annotation.PreDestroy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import mfs.mw.crypto.util.CeremonyComponents;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import mfs.mw.crypto.config.CryptoProperties;
import mfs.mw.crypto.exception.CryptoErrors;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The PKCS#11 provider (spec 18.8, D23): the JDK SunPKCS11 provider against one token, in dev and
 * test the softhsm container's {@code mw-crypto} token through the p11-kit proxy (the image's
 * module file, deploy/softhsm/README.md). It logs in with the user PIN from Vault ({@code
 * mw.crypto.pkcs11.user-pin}) and never logs it; the PIN's char copy is zeroed after the login.
 *
 * <p>Connecting is lazy (first use or probe), so the service starts while the HSM is still coming
 * up. Any HSM failure drops the connection, so the next call logs in again, and fails the request
 * closed ({@code MW-SYS-010}). SunPKCS11 pools the token sessions itself; a semaphore of {@code
 * max-sessions} bounds the operations in flight, the session pool of this service.
 *
 * <p>Keys are token objects, sensitive and never extractable (the {@code attributes} lines of the
 * provider configuration). A key pair is stored as a key store entry with a self-signed placeholder
 * certificate (SunPKCS11 keeps a private key only next to a certificate), signed by the new private
 * key inside the HSM; the certificate only carries the public key.
 *
 * <p>{@code mw.crypto.pkcs11.enabled=false} leaves it out; only tests do that, to put an in-memory
 * provider in its place.
 */
@Component
@ConditionalOnProperty(name = "mw.crypto.pkcs11.enabled", havingValue = "true", matchIfMissing = true)
public class Pkcs11Provider implements CryptoProvider {

  public static final String NAME = "pkcs11";
  private static final Logger log = LoggerFactory.getLogger(Pkcs11Provider.class);
  private static final Duration CERT_LIFE = Duration.ofDays(36500);
  private static final long CKF_OS_LOCKING_OK = 0x2L;
  private static final int WRAP_IV = 16;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final CryptoProperties.Pkcs11 props;
  private final Semaphore sessions;
  private final ExecutorService hsmThreads;
  private Token token;
  /** True once SunPKCS11 was asked to load the library: later connects reset the module first. */
  private boolean moduleLoaded;

  public Pkcs11Provider(CryptoProperties properties) {
    this.props = properties.pkcs11();
    this.sessions = new Semaphore(props.maxSessions(), true);
    // platform threads: a native PKCS#11 call would pin a virtual thread's carrier
    this.hsmThreads = Executors.newCachedThreadPool(Thread.ofPlatform().name("hsm-", 0).daemon().factory());
  }

  /** A logged-in SunPKCS11 provider and its key store. */
  private record Token(Provider provider, KeyStore keyStore) {}

  @FunctionalInterface
  private interface HsmCall<T> {
    T apply(Token token) throws GeneralSecurityException, OperatorCreationException;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Set<Capability> capabilities() {
    return EnumSet.of(
        Capability.PIN_HMAC, Capability.JWS, Capability.HMAC, Capability.TOKENISE, Capability.ENVELOPE,
        Capability.XMLDSIG, Capability.CEREMONY);
  }

  @Override
  public void generateKey(String label, KeyAlgorithm algorithm) {
    call("generate", t -> {
      switch (algorithm) {
        case RSA_3072 -> storePair(t, label, rsa(t.provider()), "SHA256withRSA");
        case EC_P256 -> storePair(t, label, ec(t.provider()), "SHA256withECDSA");
        case HMAC_SHA256 -> storeSecret(t, label, "HmacSHA256");
        case AES_256 -> storeSecret(t, label, "AES");
      }
      return null;
    });
  }

  @Override
  public PublicKey publicKey(String label) {
    return call("public-key", t -> {
      Certificate cert = t.keyStore().getCertificate(label);
      if (cert == null) {
        throw new GeneralSecurityException("no key pair under the label");
      }
      return cert.getPublicKey();
    });
  }

  @Override
  public void destroy(String label) {
    call("destroy", t -> {
      if (t.keyStore().containsAlias(label)) {
        t.keyStore().deleteEntry(label);
      }
      return null;
    });
  }

  /**
   * The raw RSA decrypt ({@code CKM_RSA_X_509}) runs in the HSM, the OAEP decoding (SHA-256,
   * MGF1-SHA-256) here: SunPKCS11 has no OAEP cipher. A block that is no RSA ciphertext of this key
   * (wrong length, not below the modulus) never reaches the HSM. The raw block is zeroed.
   */
  @Override
  public byte[] decryptOaep(String label, byte[] ciphertext) {
    byte[] raw = call("pin-decrypt", t -> {
      PrivateKey key = privateKey(t, label);
      BigInteger modulus = ((RSAPublicKey) t.keyStore().getCertificate(label).getPublicKey()).getModulus();
      int k = (modulus.bitLength() + 7) / 8;
      if (ciphertext.length != k || new BigInteger(1, ciphertext).compareTo(modulus) >= 0) {
        return null;
      }
      Cipher rsa = Cipher.getInstance("RSA/ECB/NoPadding", t.provider());
      rsa.init(Cipher.DECRYPT_MODE, key);
      byte[] out = rsa.doFinal(ciphertext);
      return out.length == 0 ? null : withLength(out, k);
    });
    if (raw == null) {
      throw CryptoErrors.badPinBlock();
    }
    try {
      return OaepDecoder.decode(raw);
    } finally {
      Arrays.fill(raw, (byte) 0);
    }
  }

  @Override
  public List<byte[]> hmac(String label, List<byte[]> messages) {
    return call("hmac", t -> {
      Key key = t.keyStore().getKey(label, null);
      if (!(key instanceof SecretKey secret)) {
        throw new GeneralSecurityException("no secret key under the label");
      }
      Mac mac = Mac.getInstance("HmacSHA256", t.provider());
      mac.init(secret);
      List<byte[]> out = new ArrayList<>(messages.size());
      for (byte[] m : messages) {
        out.add(mac.doFinal(m));
      }
      return out;
    });
  }

  @Override
  public byte[] sign(String label, SignatureAlgorithm algorithm, byte[] data) {
    return call("sign", t -> {
      Signature s =
          switch (algorithm) {
            case RS256 -> Signature.getInstance("SHA256withRSA", t.provider());
            case PS256 -> {
              Signature pss = Signature.getInstance("SHA256withRSASSA-PSS", t.provider());
              pss.setParameter(algorithm.pssParameters());
              yield pss;
            }
            case PS512 -> {
              Signature pss = Signature.getInstance("SHA512withRSASSA-PSS", t.provider());
              pss.setParameter(algorithm.pssParameters());
              yield pss;
            }
            case ES256 -> Signature.getInstance("SHA256withECDSA", t.provider());
          };
      s.initSign(privateKey(t, label));
      s.update(data);
      return s.sign();
    });
  }

  /**
   * AES-256-CTR under the KEK in the HSM, with a random 16-byte initial counter block: the HSM
   * encrypts the counter blocks ({@code CKM_AES_ECB}, the forward cipher only) and the keystream is
   * XORed with the data key here; the result is {@code iv(16) ‖ ciphertext}, the same bytes as JCA
   * {@code AES/CTR/NoPadding}. Not GCM, CBC or key wrap: over the p11-kit RPC to the token {@code
   * CKM_AES_GCM} fails with {@code CKR_DEVICE_ERROR} and a CBC decrypt with {@code
   * CKR_FUNCTION_NOT_SUPPORTED}. The wrapped key needs no tag of its own: a changed one unwraps to
   * another data key, and the envelope's own GCM tag then fails ({@code MW-VAL-016}).
   */
  @Override
  public byte[] wrapKey(String label, byte[] dataKey) {
    byte[] iv = new byte[WRAP_IV];
    RANDOM.nextBytes(iv);
    byte[] out = new byte[WRAP_IV + dataKey.length];
    System.arraycopy(iv, 0, out, 0, WRAP_IV);
    byte[] stream = keystream("wrap", label, iv, dataKey.length);
    try {
      for (int i = 0; i < dataKey.length; i++) {
        out[WRAP_IV + i] = (byte) (dataKey[i] ^ stream[i]);
      }
      return out;
    } finally {
      Arrays.fill(stream, (byte) 0);
    }
  }

  @Override
  public byte[] unwrapKey(String label, byte[] wrapped) {
    if (wrapped.length <= WRAP_IV) {
      throw CryptoErrors.badEnvelope();
    }
    byte[] iv = Arrays.copyOf(wrapped, WRAP_IV);
    int n = wrapped.length - WRAP_IV;
    byte[] stream = keystream("unwrap", label, iv, n);
    try {
      byte[] key = new byte[n];
      for (int i = 0; i < n; i++) {
        key[i] = (byte) (wrapped[WRAP_IV + i] ^ stream[i]);
      }
      return key;
    } finally {
      Arrays.fill(stream, (byte) 0);
    }
  }

  /** {@code length} bytes of AES-CTR keystream from counter block {@code iv}, made in the HSM. */
  private byte[] keystream(String op, String label, byte[] iv, int length) {
    byte[] counters = counterBlocks(iv, (length + WRAP_IV - 1) / WRAP_IV);
    byte[] stream = call(op, t -> {
      Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding", t.provider());
      ecb.init(Cipher.ENCRYPT_MODE, secretKey(t, label));
      return ecb.doFinal(counters);
    });
    try {
      return Arrays.copyOf(stream, length);
    } finally {
      Arrays.fill(stream, (byte) 0);
    }
  }

  /** {@code n} counter blocks from {@code iv}, incremented as one 128-bit big-endian number (as JCA CTR). */
  static byte[] counterBlocks(byte[] iv, int n) {
    byte[] out = new byte[n * WRAP_IV];
    byte[] block = iv.clone();
    for (int b = 0; b < n; b++) {
      System.arraycopy(block, 0, out, b * WRAP_IV, WRAP_IV);
      for (int i = WRAP_IV - 1; i >= 0 && ++block[i] == 0; i--) {
        // carry into the next byte
      }
    }
    return out;
  }

  private static PrivateKey privateKey(Token t, String label) throws GeneralSecurityException {
    if (!(t.keyStore().getKey(label, null) instanceof PrivateKey key)) {
      throw new GeneralSecurityException("no private key under the label");
    }
    return key;
  }

  private static SecretKey secretKey(Token t, String label) throws GeneralSecurityException {
    if (!(t.keyStore().getKey(label, null) instanceof SecretKey key)) {
      throw new GeneralSecurityException("no secret key under the label");
    }
    return key;
  }

  /**
   * The raw RSA output as exactly {@code k} bytes: SunPKCS11 may drop leading zero bytes. The input
   * is zeroed when it is copied.
   */
  static byte[] withLength(byte[] out, int k) {
    if (out.length == k) {
      return out;
    }
    byte[] block = new byte[k];
    if (out.length < k) {
      System.arraycopy(out, 0, block, k - out.length, out.length);
    }
    Arrays.fill(out, (byte) 0);
    return block;
  }

  /**
   * The component becomes an EC P-256 private key token object (sensitive, not extractable, {@code
   * CKA_DERIVE} by the {@code attributes(import, ...)} line of the configuration), stored next to a
   * placeholder certificate as every key pair here. The JDK key built from it is only on the stack
   * of this call.
   */
  @Override
  public void storeComponent(String label, byte[] component) {
    ECPrivateKey priv = CeremonyComponents.privateKey(component);
    KeyPair pair = new KeyPair(CeremonyComponents.publicKey(component), priv);
    call("ceremony-component", t -> {
      X509Certificate cert = placeholderCertificate(null, label, pair, "SHA256withECDSA");
      t.keyStore().setKeyEntry(label, priv, null, new Certificate[] {cert});
      return null;
    });
  }

  /**
   * ECDH ({@code CKM_ECDH1_DERIVE}, {@code CKD_NULL}) of the stored component with the second
   * component's public point, in the HSM, into a generic secret ({@code CKA_VALUE_LEN} 32 from the
   * configuration), copied to a token object under {@code targetLabel}; then its HMAC over 16 zero
   * bytes for the KCV. Why not XOR: SoftHSM has no {@code CKM_XOR_BASE_AND_DATA} and the p11-kit
   * RPC refuses {@code CKM_AES_KEY_WRAP} (CLAUDE.md "Key ceremony").
   */
  @Override
  public byte[] combineComponents(String firstLabel, byte[] second, String targetLabel) {
    ECPublicKey point = CeremonyComponents.publicKey(second);
    return call("ceremony-combine", t -> {
      KeyAgreement ecdh = KeyAgreement.getInstance("ECDH", t.provider());
      ecdh.init(privateKey(t, firstLabel));
      ecdh.doPhase(point, true);
      SecretKey derived = ecdh.generateSecret("Generic");
      t.keyStore().setEntry(targetLabel, new KeyStore.SecretKeyEntry(derived), null);
      Mac mac = Mac.getInstance("HmacSHA256", t.provider());
      mac.init(secretKey(t, targetLabel));
      return mac.doFinal(CeremonyComponents.kcvInput());
    });
  }

  @Override
  public boolean probe() {
    try {
      // a real round trip (C_GenerateRandom): key store calls answer from SunPKCS11's cache
      call("probe", t -> {
        SecureRandom.getInstance("PKCS11", t.provider()).nextBytes(new byte[8]);
        return null;
      });
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private <T> T call(String op, HsmCall<T> body) {
    boolean acquired;
    try {
      acquired = sessions.tryAcquire(props.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw CryptoErrors.hsmUnavailable(op, e);
    }
    if (!acquired) {
      throw CryptoErrors.hsmUnavailable(op + " (no free HSM session)", null);
    }
    // The native call runs on an HSM thread and keeps its session (permit) until it returns, even
    // after the caller gave up: a hung HSM soon leaves no free session, and requests fail at once.
    Future<T> result;
    try {
      result = hsmThreads.submit(() -> {
        try {
          return body.apply(token());
        } finally {
          sessions.release();
        }
      });
    } catch (RejectedExecutionException e) {
      sessions.release();
      throw CryptoErrors.hsmUnavailable(op, e);
    }
    try {
      return result.get(props.callTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      disconnect(op, e);
      throw CryptoErrors.hsmUnavailable(op + " (timed out)", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw CryptoErrors.hsmUnavailable(op, e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof GeneralSecurityException
          || cause instanceof OperatorCreationException
          || cause instanceof ProviderException) {
        disconnect(op, (Exception) cause);
        throw CryptoErrors.hsmUnavailable(op, cause);
      }
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw CryptoErrors.hsmUnavailable(op, cause);
    }
  }

  @PreDestroy
  void shutdown() {
    hsmThreads.shutdownNow();
  }

  private synchronized Token token() throws GeneralSecurityException {
    if (token == null) {
      token = connect();
    }
    return token;
  }

  private synchronized void disconnect(String op, Exception cause) {
    if (token != null) {
      log.atWarn()
          .addKeyValue("event", "crypto.hsm.call_failed")
          .addKeyValue("op", op)
          .addKeyValue("provider", NAME)
          .addKeyValue("error", cause.getClass().getSimpleName())
          .log("HSM call failed; the next call connects again");
    }
    token = null;
  }

  private Token connect() throws GeneralSecurityException {
    if (!Files.isReadable(Path.of(props.library()))) {
      throw new GeneralSecurityException("PKCS#11 library not found");
    }
    if (props.userPin().isEmpty()) {
      throw new GeneralSecurityException("no PKCS#11 user PIN configured");
    }
    if (moduleLoaded) {
      resetModule(props.library());
    }
    moduleLoaded = true;
    Provider provider;
    try {
      provider = Security.getProvider("SunPKCS11").configure("--" + configuration(props));
    } catch (ProviderException | IllegalArgumentException e) {
      throw new GeneralSecurityException("PKCS#11 provider not configured", e);
    }
    KeyStore keyStore = KeyStore.getInstance("PKCS11", provider);
    char[] pin = props.userPin().toCharArray();
    try {
      keyStore.load(null, pin);
    } catch (java.io.IOException e) {
      throw new GeneralSecurityException("PKCS#11 login failed", e);
    } finally {
      Arrays.fill(pin, '\0');
    }
    log.atInfo()
        .addKeyValue("event", "crypto.hsm.connected")
        .addKeyValue("provider", NAME)
        .log("Logged in to the PKCS#11 token");
    return new Token(provider, keyStore);
  }

  /**
   * Finalises and initialises the PKCS#11 module again before a reconnect. SunPKCS11 initialises a
   * library once per JVM and never again, but after the HSM connection broke (or the first
   * initialisation found no token: the p11-kit proxy then reports 0 slots) only a fresh {@code
   * C_Initialize} reaches the token again. JDK SunPKCS11 has no public API for it, so this calls the
   * JDK-internal {@code sun.security.pkcs11.wrapper.PKCS11} (opened by {@code Add-Opens} in the boot
   * jar manifest, build.gradle.kts). If that is not possible the reconnect still runs and the call
   * fails closed as before.
   */
  private static void resetModule(String library) {
    try {
      Class<?> wrapper = Class.forName("sun.security.pkcs11.wrapper.PKCS11");
      Class<?> argsType = Class.forName("sun.security.pkcs11.wrapper.CK_C_INITIALIZE_ARGS");
      Method getInstance = wrapper.getMethod("getInstance", String.class, String.class, argsType, boolean.class);
      Object module = getInstance.invoke(null, library, "C_GetFunctionList", null, true);
      Method finalize = wrapper.getDeclaredMethod("C_Finalize", Object.class);
      Method initialize = wrapper.getDeclaredMethod("C_Initialize", Object.class);
      finalize.setAccessible(true);
      initialize.setAccessible(true);
      Object args = argsType.getConstructor().newInstance();
      argsType.getField("flags").setLong(args, CKF_OS_LOCKING_OK);
      try {
        finalize.invoke(module, (Object) null);
      } catch (InvocationTargetException notInitialised) {
        // CKR_CRYPTOKI_NOT_INITIALIZED: nothing to finalise
      }
      initialize.invoke(module, args);
    } catch (ReflectiveOperationException | RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "crypto.hsm.reset_failed")
          .addKeyValue("provider", NAME)
          .addKeyValue("error", e.getClass().getSimpleName())
          .log("PKCS#11 module could not be initialised again");
    }
  }

  /**
   * The SunPKCS11 configuration: library and slot, and every key generated or stored is a
   * sensitive, never extractable token object.
   */
  static String configuration(CryptoProperties.Pkcs11 p) {
    return String.join(
        "\n",
        "name = mwcrypto",
        "library = " + p.library(),
        "slotListIndex = " + p.slotListIndex(),
        "attributes(*, CKO_PRIVATE_KEY, *) = {",
        "  CKA_SENSITIVE = true",
        "  CKA_EXTRACTABLE = false",
        "}",
        "attributes(*, CKO_SECRET_KEY, *) = {",
        "  CKA_SENSITIVE = true",
        "  CKA_EXTRACTABLE = false",
        "}",
        // key ceremony (ticket 75): an imported component may derive (ECDH); a derived generic
        // secret is 32 bytes (SoftHSM needs the length; HMAC keys are generated with 32 anyway)
        "attributes(import, CKO_PRIVATE_KEY, CKK_EC) = {",
        "  CKA_DERIVE = true",
        "}",
        "attributes(generate, CKO_SECRET_KEY, CKK_GENERIC_SECRET) = {",
        "  CKA_VALUE_LEN = 32",
        "}",
        "");
  }

  private static KeyPair rsa(Provider p) throws GeneralSecurityException {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA", p);
    g.initialize(3072);
    return g.generateKeyPair();
  }

  private static KeyPair ec(Provider p) throws GeneralSecurityException {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC", p);
    g.initialize(new ECGenParameterSpec("secp256r1"));
    return g.generateKeyPair();
  }

  private static void storePair(Token t, String label, KeyPair pair, String sigAlg)
      throws GeneralSecurityException, OperatorCreationException {
    X509Certificate cert = placeholderCertificate(t.provider(), label, pair, sigAlg);
    t.keyStore().setKeyEntry(label, pair.getPrivate(), null, new Certificate[] {cert});
  }

  private static void storeSecret(Token t, String label, String algorithm) throws GeneralSecurityException {
    KeyGenerator g = KeyGenerator.getInstance(algorithm, t.provider());
    g.init(256);
    t.keyStore().setEntry(label, new KeyStore.SecretKeyEntry(g.generateKey()), null);
  }

  private static X509Certificate placeholderCertificate(Provider p, String label, KeyPair pair, String sigAlg)
      throws GeneralSecurityException, OperatorCreationException {
    X500Name name = new X500Name("CN=" + label + ", O=mw-crypto placeholder");
    Instant now = Instant.now();
    PrivateKey signer = pair.getPrivate();
    var holder =
        new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now.toEpochMilli()), Date.from(now), Date.from(now.plus(CERT_LIFE)),
                name, pair.getPublic())
            .build(p == null
                ? new JcaContentSignerBuilder(sigAlg).build(signer)
                : new JcaContentSignerBuilder(sigAlg).setProvider(p).build(signer));
    return new JcaX509CertificateConverter().getCertificate(holder);
  }
}
