package mfs.mw.crypto.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import mfs.mw.crypto.PkiTestAuthority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.vault.VaultException;
import org.springframework.vault.core.VaultTemplate;

/**
 * The service certificate against a real OpenBao PKI (D24, ticket 66; {@link PkiTestAuthority}):
 * issue at start into memory, the expiry gauge, renewal at two thirds of the life (a moved clock, no
 * wait), the SSL bundle update a Tomcat connector reloads from, the client context presenting the
 * new certificate, and a failed renewal keeping the old one.
 */
class ServiceCertificateManagerIT {

  static final TlsProperties PROPS =
      new TlsProperties(true, "pki_int", "mw-crypto", "mw-crypto", List.of("mw-crypto"), Duration.ofHours(1),
          Duration.ofMinutes(1), "mw-service");

  ServiceCertificateManager manager;

  @BeforeAll
  static void pki() {
    PkiTestAuthority.start();
  }

  @AfterEach
  void stop() {
    if (manager != null) {
      manager.destroy();
    }
  }

  @Test
  void issuesAtStartIntoMemoryWithTheServiceNameAndTheIntermediateAsTrust() throws Exception {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    MutableClock clock = new MutableClock(Instant.now());
    manager = new ServiceCertificateManager(PkiTestAuthority.vault(), PROPS, clock, meters);

    X509Certificate cert = manager.certificate();
    assertThat(cert.getSubjectX500Principal().getName()).isEqualTo("CN=mw-crypto");
    assertThat(cert.getSubjectAlternativeNames().stream().map(l -> String.valueOf(l.get(1))).toList())
        .containsExactly("mw-crypto");
    assertThat(cert.getIssuerX500Principal().getName()).contains("mfs-mw IT services intermediate CA");
    assertThat(Duration.between(cert.getNotBefore().toInstant(), cert.getNotAfter().toInstant()))
        .isBetween(Duration.ofMinutes(59), Duration.ofMinutes(61));

    KeyStore trust = manager.bundle().getStores().getTrustStore();
    assertThat(trust.size()).isEqualTo(1);
    assertThat(((X509Certificate) trust.getCertificate("issuing-ca")).getSubjectX500Principal().getName())
        .contains("intermediate");
    assertThat(manager.bundle().getStores().getKeyStore().isKeyEntry(ServiceCertificateManager.ALIAS)).isTrue();

    double left = meters.get("tls.certificate.expiry.seconds").tag("service", "mw-crypto").gauge().value();
    assertThat(left).isBetween(3000.0, 3700.0);
  }

  @Test
  void renewsAtTwoThirdsOfTheLifeAndUpdatesTheBundle() {
    MutableClock clock = new MutableClock(Instant.now());
    manager = new ServiceCertificateManager(PkiTestAuthority.vault(), PROPS, clock, new SimpleMeterRegistry());
    DefaultSslBundleRegistry registry = new DefaultSslBundleRegistry();
    manager.registerBundles(registry);
    AtomicReference<SslBundle> reloaded = new AtomicReference<>();
    registry.addBundleUpdateHandler("mw-service", reloaded::set);
    X509Certificate first = manager.certificate();

    Duration life = Duration.between(first.getNotBefore().toInstant(), first.getNotAfter().toInstant());
    assertThat(manager.renewAt()).isEqualTo(first.getNotBefore().toInstant().plus(life.multipliedBy(2).dividedBy(3)));

    clock.set(manager.renewAt().minusSeconds(1));
    assertThat(manager.renewIfDue()).isFalse();
    assertThat(reloaded.get()).isNull();

    clock.set(manager.renewAt().plusSeconds(1));
    assertThat(manager.renewIfDue()).isTrue();
    assertThat(manager.certificate().getSerialNumber()).isNotEqualTo(first.getSerialNumber());
    assertThat(reloaded.get()).isSameAs(manager.bundle());
    assertThat(registry.getBundle("mw-service")).isSameAs(manager.bundle());
  }

  @Test
  void theClientContextPresentsTheCurrentCertificateAcrossRenewals() throws Exception {
    AtomicReference<X509Certificate> seen = new AtomicReference<>();
    HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    SSLContext serverCtx = PkiTestAuthority.localhostServerContext();
    server.setHttpsConfigurator(new HttpsConfigurator(serverCtx) {
      @Override
      public void configure(HttpsParameters params) {
        SSLParameters p = serverCtx.getDefaultSSLParameters();
        p.setNeedClientAuth(true);
        params.setSSLParameters(p);
      }
    });
    server.createContext("/", ex -> {
      seen.set((X509Certificate) ((HttpsExchange) ex).getSSLSession().getPeerCertificates()[0]);
      byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });
    server.start();
    try {
      MutableClock clock = new MutableClock(Instant.now());
      manager = new ServiceCertificateManager(PkiTestAuthority.vault(), PROPS, clock, new SimpleMeterRegistry());
      URI uri = URI.create("https://localhost:" + server.getAddress().getPort() + "/");

      assertThat(call(uri)).isEqualTo(200);
      assertThat(seen.get().getSerialNumber()).isEqualTo(manager.certificate().getSerialNumber());

      clock.set(manager.renewAt().plusSeconds(1));
      assertThat(manager.renewIfDue()).isTrue();
      assertThat(call(uri)).isEqualTo(200);
      assertThat(seen.get().getSerialNumber()).isEqualTo(manager.certificate().getSerialNumber());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void aFailedRenewalKeepsTheCurrentCertificate() {
    MutableClock clock = new MutableClock(Instant.now());
    VaultTemplate vault = spy(PkiTestAuthority.vault());
    manager = new ServiceCertificateManager(vault, PROPS, clock, new SimpleMeterRegistry());
    X509Certificate first = manager.certificate();
    doThrow(new VaultException("down")).when(vault).opsForPki("pki_int");

    clock.set(manager.renewAt().plusSeconds(1));
    assertThat(manager.renewIfDue()).isFalse();
    assertThat(manager.certificate()).isSameAs(first);
  }

  int call(URI uri) throws Exception {
    // a new client per call: a new TLS handshake with whatever certificate is current
    try (HttpClient client = HttpClient.newBuilder().sslContext(manager.clientSslContext()).build()) {
      return client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }
  }

  /** A clock the test moves by hand. */
  static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;

    MutableClock(Instant start) {
      this.now = new AtomicReference<>(start);
    }

    void set(Instant instant) {
      now.set(instant);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }
}
