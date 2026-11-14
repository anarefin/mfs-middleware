package mfs.mw.crypto.client;

import jakarta.annotation.PreDestroy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.KeyGenerator;
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
 * module file, deploy/softhsm/README.md). It logs in with the user PIN from OpenBao ({@code
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
        Capability.XMLDSIG);
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
            .build(new JcaContentSignerBuilder(sigAlg).setProvider(p).build(signer));
    return new JcaX509CertificateConverter().getCertificate(holder);
  }
}
