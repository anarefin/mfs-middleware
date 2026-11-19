package mfs.mw.checkout.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.ssl.SslBundleRegistrar;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.SslBundleRegistry;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.stereotype.Component;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.support.CertificateBundle;
import org.springframework.vault.support.VaultCertificateRequest;

/**
 * This service's certificate from Vault PKI (spec 18.2, 18.4, D24; ticket 66; copied, D14).
 *
 * <ul>
 *   <li>Issues at start from {@code <pki-mount>/issue/<role>} (CN = service name, SAN = compose DNS
 *       name) through Spring Cloud Vault's {@link VaultOperations} (AppRole; the service's policy
 *       allows its own role only), and fails the start when it cannot: mTLS is mandatory.
 *   <li>Key and chain stay in an in-memory {@link KeyStore} (never on disk); the trust store holds the
 *       issuing intermediate only, so only certificates of that intermediate pass.
 *   <li>Registers them as the SSL bundle {@code mw.tls.bundle} (an mTLS server names it in {@code
 *       server.ssl.bundle}) and renews at two thirds of the life, then updates the bundle: Tomcat
 *       reloads it without a restart. A failed renewal is retried every {@code retry-period}.
 *   <li>Callers: {@link #clientSslContext()} is one {@link SSLContext} whose key and trust managers
 *       always use the current certificate, so a client built once keeps working across renewals,
 *       e.g. {@code RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(
 *       HttpClient.newBuilder().sslContext(manager.clientSslContext()).build()))}.
 *   <li>Metric {@code tls.certificate.expiry.seconds{service}} (seconds until notAfter). The alert:
 *       ERROR log event {@code tls.certificate.expiring} once less than a third of the life is left
 *       (renewal failed; runbook "Health and alerts").
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "mw.tls.enabled", havingValue = "true")
@EnableConfigurationProperties(TlsProperties.class)
public final class ServiceCertificateManager implements SslBundleRegistrar, DisposableBean {

  static final String ALIAS = "service";
  private static final Logger log = LoggerFactory.getLogger(ServiceCertificateManager.class);
  private static final Duration MIN_DELAY = Duration.ofSeconds(1);

  private final VaultOperations vault;
  private final TlsProperties props;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final SSLContext clientContext;
  private volatile Issued current;
  private volatile SslBundleRegistry registry;
  private volatile boolean expiringReported;

  public ServiceCertificateManager(VaultOperations vault, TlsProperties props, Clock clock, MeterRegistry meters) {
    this.vault = vault;
    this.props = props;
    this.clock = clock;
    this.current = issue();
    Gauge.builder("tls.certificate.expiry.seconds", this, ServiceCertificateManager::secondsLeft)
        .tag("service", props.commonName())
        .description("Seconds until this service's TLS certificate expires")
        .baseUnit("seconds")
        .register(meters);
    this.clientContext = newClientContext();
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("tls-renew").daemon().factory());
    schedule(Duration.between(clock.instant(), current.renewAt()));
  }

  @Override
  public void registerBundles(SslBundleRegistry bundles) {
    this.registry = bundles;
    bundles.registerBundle(props.bundle(), current.bundle());
  }

  /** The current certificate. */
  public X509Certificate certificate() {
    return current.certificate();
  }

  /** The current bundle (key store with key and chain, trust store with the intermediate). */
  public SslBundle bundle() {
    return current.bundle();
  }

  /** When the current certificate is due for renewal: two thirds into its life. */
  public Instant renewAt() {
    return current.renewAt();
  }

  /** mTLS client context that always presents the current certificate and trusts the intermediate. */
  public SSLContext clientSslContext() {
    return clientContext;
  }

  /** Renews when two thirds of the life have passed; true when a new certificate is in place. */
  public synchronized boolean renewIfDue() {
    Instant now = clock.instant();
    if (now.isBefore(current.renewAt())) {
      return false;
    }
    try {
      Issued next = issue();
      current = next;
      expiringReported = false;
      SslBundleRegistry bundles = registry;
      if (bundles != null) {
        bundles.updateBundle(props.bundle(), next.bundle());
      }
      // a resumed TLS session would keep presenting the old certificate
      SSLSessionContext sessions = clientContext.getClientSessionContext();
      for (Enumeration<byte[]> ids = sessions.getIds(); ids.hasMoreElements(); ) {
        SSLSession session = sessions.getSession(ids.nextElement());
        if (session != null) {
          session.invalidate();
        }
      }
      log.atInfo()
          .addKeyValue("event", "tls.certificate.renewed")
          .addKeyValue("notAfter", next.notAfter().toString())
          .log("Service certificate renewed");
      return true;
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "tls.certificate.renew_failed")
          .addKeyValue("errorClass", e.getClass().getSimpleName())
          .log("Service certificate renewal failed, retrying in {}", props.retryPeriod());
      reportIfExpiring(now);
      return false;
    }
  }

  long secondsLeft() {
    return Duration.between(clock.instant(), current.notAfter()).toSeconds();
  }

  private void reportIfExpiring(Instant now) {
    Issued c = current;
    Duration life = Duration.between(c.notBefore(), c.notAfter());
    if (!expiringReported && Duration.between(now, c.notAfter()).compareTo(life.dividedBy(3)) < 0) {
      expiringReported = true;
      log.atError()
          .addKeyValue("event", "tls.certificate.expiring")
          .addKeyValue("notAfter", c.notAfter().toString())
          .log("Service certificate has less than a third of its life left and was not renewed");
    }
  }

  private void tick() {
    try {
      renewIfDue();
    } finally {
      Instant now = clock.instant();
      schedule(now.isBefore(current.renewAt()) ? Duration.between(now, current.renewAt()) : props.retryPeriod());
    }
  }

  private void schedule(Duration delay) {
    long millis = Math.max(MIN_DELAY.toMillis(), delay.toMillis());
    if (!scheduler.isShutdown()) {
      scheduler.schedule(this::tick, millis, TimeUnit.MILLISECONDS);
    }
  }

  private Issued issue() {
    VaultCertificateRequest request =
        VaultCertificateRequest.builder()
            .commonName(props.commonName())
            .altNames(props.altNames())
            .ttl(props.ttl())
            .build();
    CertificateBundle issued = vault.opsForPki(props.pkiMount()).issueCertificate(props.role(), request).getRequiredData();
    try {
      char[] password = randomPassword();
      KeyStore keyStore = issued.createKeyStore(ALIAS, true, password);
      KeyStore trustStore = KeyStore.getInstance("PKCS12");
      trustStore.load(null, null);
      trustStore.setCertificateEntry("issuing-ca", issued.getX509IssuerCertificate());
      KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(keyStore, password);
      TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(trustStore);
      String pw = new String(password);
      SslBundle bundle = SslBundle.of(SslStoreBundle.of(keyStore, pw, trustStore), SslBundleKey.of(pw, ALIAS));
      X509Certificate cert = issued.getX509Certificate();
      return new Issued(cert, bundle, first(kmf.getKeyManagers(), X509ExtendedKeyManager.class),
          first(tmf.getTrustManagers(), X509ExtendedTrustManager.class),
          cert.getNotBefore().toInstant(), cert.getNotAfter().toInstant());
    } catch (GeneralSecurityException | java.io.IOException e) {
      throw new IllegalStateException("cannot build the key store of the issued certificate", e);
    }
  }

  private static <T> T first(Object[] managers, Class<T> type) {
    for (Object m : managers) {
      if (type.isInstance(m)) {
        return type.cast(m);
      }
    }
    throw new IllegalStateException("no " + type.getSimpleName());
  }

  private static char[] randomPassword() {
    SecureRandom random = new SecureRandom();
    char[] pw = new char[32];
    for (int i = 0; i < pw.length; i++) {
      pw[i] = (char) ('a' + random.nextInt(26));
    }
    return pw;
  }

  private SSLContext newClientContext() {
    try {
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(new KeyManager[] {new CurrentKeyManager()}, new TrustManager[] {new CurrentTrustManager()}, null);
      return ctx;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("cannot create the mTLS client context", e);
    }
  }

  @Override
  public void destroy() {
    scheduler.shutdownNow();
  }

  private record Issued(
      X509Certificate certificate,
      SslBundle bundle,
      X509ExtendedKeyManager keyManager,
      X509ExtendedTrustManager trustManager,
      Instant notBefore,
      Instant notAfter) {

    Instant renewAt() {
      return notBefore.plus(Duration.between(notBefore, notAfter).multipliedBy(2).dividedBy(3));
    }
  }

  /** Delegates to the key manager of the certificate current at handshake time. */
  private final class CurrentKeyManager extends X509ExtendedKeyManager {
    private X509ExtendedKeyManager km() {
      return current.keyManager();
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
      return km().getClientAliases(keyType, issuers);
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
      return km().chooseClientAlias(keyType, issuers, socket);
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
      return km().chooseEngineClientAlias(keyType, issuers, engine);
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
      return km().getServerAliases(keyType, issuers);
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
      return km().chooseServerAlias(keyType, issuers, socket);
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
      return km().chooseEngineServerAlias(keyType, issuers, engine);
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
      return km().getCertificateChain(alias);
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
      return km().getPrivateKey(alias);
    }
  }

  /** Delegates to the trust manager (the issuing intermediate) current at handshake time. */
  private final class CurrentTrustManager extends X509ExtendedTrustManager {
    private X509ExtendedTrustManager tm() {
      return current.trustManager();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
        throws CertificateException {
      tm().checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
        throws CertificateException {
      tm().checkServerTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
        throws CertificateException {
      tm().checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
        throws CertificateException {
      tm().checkServerTrusted(chain, authType, engine);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
      tm().checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
      tm().checkServerTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return tm().getAcceptedIssuers();
    }
  }
}
