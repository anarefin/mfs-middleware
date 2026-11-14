package mfs.mw.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import mfs.mw.auth.MutableClock;
import mfs.mw.auth.PkiTestAuthority;
import mfs.mw.auth.config.ServiceCertificateManager;
import mfs.mw.auth.config.TlsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The caller side of mTLS phase 1 (D24, ticket 66): the service issues its certificate from the
 * OpenBao intermediate ({@link PkiTestAuthority}) and a {@link RestClient} on {@link
 * ServiceCertificateManager#clientSslContext()} calls a server that needs a client certificate (as
 * mw-crypto does), across a renewal on the same context. Without the certificate the handshake fails.
 */
class MtlsClientIT {

  static final TlsProperties PROPS =
      new TlsProperties(true, "pki_int", "mw-auth", "mw-auth", List.of("mw-auth"), Duration.ofHours(1),
          Duration.ofMinutes(1), "mw-service");

  final AtomicReference<X509Certificate> peer = new AtomicReference<>();
  HttpsServer server;
  ServiceCertificateManager manager;

  @BeforeEach
  void callee() throws Exception {
    PkiTestAuthority.role("mw-auth", true); // localhost too: InternalApiMtlsIT serves on it
    SSLContext ctx = PkiTestAuthority.localhostServerContext("mw-crypto");
    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(ctx) {
      @Override
      public void configure(HttpsParameters params) {
        SSLParameters p = ctx.getDefaultSSLParameters();
        p.setNeedClientAuth(true);
        params.setSSLParameters(p);
      }
    });
    server.createContext("/internal/v1/keys", ex -> {
      peer.set((X509Certificate) ((HttpsExchange) ex).getSSLSession().getPeerCertificates()[0]);
      byte[] body = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
    if (manager != null) {
      manager.destroy();
    }
  }

  RestClient client(SSLContext ssl) {
    return RestClient.builder()
        .baseUrl("https://localhost:" + server.getAddress().getPort())
        .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder().sslContext(ssl).build()))
        .build();
  }

  @Test
  void callsAnMtlsServerWithItsOwnCertificateAcrossARenewal() {
    MutableClock clock = new MutableClock(Instant.now());
    manager = new ServiceCertificateManager(PkiTestAuthority.vault(), PROPS, clock, new SimpleMeterRegistry());
    RestClient rest = client(manager.clientSslContext());

    assertThat(rest.get().uri("/internal/v1/keys").retrieve().body(String.class)).isEqualTo("{\"items\":[]}");
    assertThat(peer.get().getSubjectX500Principal().getName()).isEqualTo("CN=mw-auth");
    assertThat(peer.get().getSerialNumber()).isEqualTo(manager.certificate().getSerialNumber());

    clock.set(manager.renewAt().plusSeconds(1));
    assertThat(manager.renewIfDue()).isTrue();
    // the same SSL context, a new connection (open ones keep the old, still valid certificate)
    RestClient again = client(manager.clientSslContext());
    assertThat(again.get().uri("/internal/v1/keys").retrieve().toBodilessEntity().getStatusCode().value())
        .isEqualTo(200);
    assertThat(peer.get().getSerialNumber()).isEqualTo(manager.certificate().getSerialNumber());
  }

  @Test
  void withoutAClientCertificateTheHandshakeFails() throws Exception {
    MutableClock clock = new MutableClock(Instant.now());
    manager = new ServiceCertificateManager(PkiTestAuthority.vault(), PROPS, clock, new SimpleMeterRegistry());
    SSLContext trustOnly = SSLContext.getInstance("TLS");
    trustOnly.init(null, new javax.net.ssl.TrustManager[] {new TrustTheIntermediate(manager)}, null);
    assertThatThrownBy(() -> client(trustOnly).get().uri("/internal/v1/keys").retrieve().toBodilessEntity())
        .isInstanceOf(ResourceAccessException.class);
  }

  /** Trusts what the manager trusts (the intermediate), presents nothing. */
  static final class TrustTheIntermediate implements javax.net.ssl.X509TrustManager {
    private final javax.net.ssl.X509TrustManager delegate;

    TrustTheIntermediate(ServiceCertificateManager m) throws Exception {
      javax.net.ssl.TrustManagerFactory tmf =
          javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(m.bundle().getStores().getTrustStore());
      this.delegate = (javax.net.ssl.X509TrustManager) tmf.getTrustManagers()[0];
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws java.security.cert.CertificateException {
      delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws java.security.cert.CertificateException {
      delegate.checkServerTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return delegate.getAcceptedIssuers();
    }
  }
}
