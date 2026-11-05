package mfs.mw.checkout.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import mfs.mw.checkout.security.IpnUrlPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An IPN over https goes to the checked address only, yet TLS is the merchant name's (review A7):
 * SNI carries the name and the certificate is verified against the name, never the IP. A local
 * HTTPS merchant with a test certificate (made by the JDK's keytool) for {@value #NAME}.
 */
class MerchantIpnTlsTest {

  static final String NAME = "merchant.example.test";
  static final char[] PASSWORD = "test-only-store".toCharArray();

  @TempDir static Path dir;
  static KeyStore merchantKeys;
  static KeyStore otherKeys;

  private HttpsServer server;
  private final AtomicReference<String> sni = new AtomicReference<>();
  private final AtomicReference<String> host = new AtomicReference<>();

  @BeforeAll
  static void certificates() throws Exception {
    merchantKeys = keyStore("merchant", NAME);
    otherKeys = keyStore("other", "other.example.test");
  }

  static KeyStore keyStore(String alias, String dns) throws Exception {
    Path file = dir.resolve(alias + ".p12");
    String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=" + dns, "-ext", "san=dns:" + dns, "-validity", "2", "-storetype", "PKCS12",
            "-keystore", file.toString(), "-storepass", new String(PASSWORD))
        .redirectErrorStream(true)
        .start();
    p.getInputStream().readAllBytes();
    assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
    assertThat(p.exitValue()).as("keytool").isZero();
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(file)) {
      ks.load(in, PASSWORD);
    }
    return ks;
  }

  void start(KeyStore serverKeys) throws Exception {
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(serverKeys, PASSWORD);
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(kmf.getKeyManagers(), null, null);
    server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(ctx));
    server.createContext("/ipn", ex -> {
      if (((HttpsExchange) ex).getSSLSession() instanceof ExtendedSSLSession s) {
        for (SNIServerName n : s.getRequestedServerNames()) {
          if (n instanceof SNIHostName h) {
            sni.set(h.getAsciiName());
          }
        }
      }
      host.set(ex.getRequestHeaders().getFirst("Host"));
      ex.getRequestBody().readAllBytes();
      ex.sendResponseHeaders(200, -1);
      ex.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  /** A client that trusts both test certificates, so only the hostname check can refuse one. */
  static MerchantIpnClient client() throws Exception {
    KeyStore trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("merchant", merchantKeys.getCertificate("merchant"));
    trust.setCertificateEntry("other", otherKeys.getCertificate("other"));
    TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trust);
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(null, tmf.getTrustManagers(), null);
    TimeLimiterRegistry limiters =
        TimeLimiterRegistry.of(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(5)).build());
    return new MerchantIpnClient(limiters, MerchantIpnClientTest.PROPS, ctx);
  }

  IpnUrlPolicy.Decision target() {
    return new IpnUrlPolicy.Decision(null, URI.create("https://" + NAME + ":" + server.getAddress().getPort() + "/ipn"),
        List.of(InetAddress.getLoopbackAddress()));
  }

  @Test
  void theCheckedAddressIsCalledWithTheMerchantNameInSniHostAndCertificateCheck() throws Exception {
    start(merchantKeys);

    MerchantIpnClient.Result r = client().post(target(), Map.of(), "{}");

    assertThat(r.httpStatus()).as("%s", r).isEqualTo(200);
    assertThat(sni.get()).isEqualTo(NAME);
    assertThat(host.get()).isEqualTo(NAME + ":" + server.getAddress().getPort());
  }

  @Test
  void aTrustedCertificateForAnotherNameIsRefused() throws Exception {
    start(otherKeys);

    MerchantIpnClient.Result r = client().post(target(), Map.of(), "{}");

    assertThat(r.httpStatus()).isNull();
    assertThat(r.error()).isEqualTo("TLS_FAILED");
    assertThat(host.get()).as("nothing was posted").isNull();
  }
}
