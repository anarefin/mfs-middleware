package mfs.mw.crypto;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.CertificateBundle;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only Vault PKI (spec 18.2, D24): a Vault dev server in a container (network alias {@code
 * vault} on {@link HsmStack#NETWORK}) set up like {@code deploy/provision} does on the stack: a
 * root CA at {@code pki/}, an intermediate at {@code pki_int/} signed by it, and one role per service
 * (CN and SAN = the service name, 72 h max, EC P-256). Roles are created on first use, so any client
 * id gets one. Started once per JVM, removed by Ryuk. Fake root token only.
 */
public final class PkiTestAuthority {

  public static final String TOKEN = "it-root-token-not-a-secret";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  static final GenericContainer<?> VAULT =
      new GenericContainer<>("hashicorp/vault:2.2.2")
          .withNetwork(HsmStack.NETWORK)
          .withNetworkAliases("vault")
          .withCommand("server", "-dev", "-dev-root-token-id=" + TOKEN, "-dev-listen-address=0.0.0.0:8200")
          .withExposedPorts(8200)
          .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));

  private static final Set<String> ROLES = new HashSet<>();
  private static boolean ready;

  private PkiTestAuthority() {}

  /** Starts Vault and sets up the root, the intermediate and the mw-crypto role (idempotent). */
  public static synchronized void start() {
    if (ready) {
      return;
    }
    VAULT.start();
    post("/v1/sys/mounts/pki", Map.of("type", "pki", "config", Map.of("max_lease_ttl", "87600h")));
    post("/v1/sys/mounts/pki_int", Map.of("type", "pki", "config", Map.of("max_lease_ttl", "720h")));
    post("/v1/pki/root/generate/internal",
        Map.of("common_name", "mfs-mw IT root CA", "ttl", "8760h", "key_type", "ec", "key_bits", 256));
    String csr = post("/v1/pki_int/intermediate/generate/internal",
        Map.of("common_name", "mfs-mw IT services intermediate CA", "key_type", "ec", "key_bits", 256))
        .path("data").path("csr").asString();
    String signed = post("/v1/pki/root/sign-intermediate",
        Map.of("csr", csr, "format", "pem_bundle", "ttl", "4380h")).path("data").path("certificate").asString();
    post("/v1/pki_int/intermediate/set-signed", Map.of("certificate", signed));
    ready = true;
    // the server's own role may also name localhost: the test reaches it on a mapped port
    role("mw-crypto", true);
  }

  /** The Vault address seen from the host. */
  public static String address() {
    return "http://" + VAULT.getHost() + ":" + VAULT.getMappedPort(8200);
  }

  /** A root-token VaultTemplate against the container (stands in for Spring Cloud Vault's). */
  public static VaultTemplate vault() {
    start();
    return new VaultTemplate(VaultEndpoint.from(URI.create(address())), new TokenAuthentication(TOKEN));
  }

  /** The role {@code name} as provisioned on the stack (localhost only for the server under test). */
  public static synchronized void role(String name, boolean allowLocalhost) {
    start();
    if (ROLES.add(name)) {
      post("/v1/pki_int/roles/" + name, Map.of("allowed_domains", name, "allow_bare_domains", true,
          "allow_subdomains", false, "allow_localhost", allowLocalhost, "allow_ip_sans", false,
          "key_type", "ec", "key_bits", 256, "max_ttl", "72h", "ttl", "72h"));
    }
  }

  /** A client certificate of role {@code name} (CN = name), as an SSL context trusting the intermediate. */
  public static SSLContext clientContext(String name) {
    role(name, false);
    JsonNode data = post("/v1/pki_int/issue/" + name, Map.of("common_name", name, "ttl", "1h")).path("data");
    CertificateBundle bundle = CertificateBundle.of(data.path("serial_number").asString(),
        data.path("certificate").asString(), data.path("issuing_ca").asString(),
        data.path("private_key").asString(), data.path("private_key_type").asString());
    try {
      char[] pw = "it-store".toCharArray();
      KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(bundle.createKeyStore("client", true, pw), pw);
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(kmf.getKeyManagers(), trustManagers(bundle), null);
      return ctx;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** A server certificate of role mw-crypto with SAN localhost, as an SSL context trusting the intermediate. */
  public static SSLContext localhostServerContext() {
    role("mw-crypto", true);
    JsonNode data = post("/v1/pki_int/issue/mw-crypto",
        Map.of("common_name", "mw-crypto", "alt_names", "localhost", "ttl", "1h")).path("data");
    CertificateBundle bundle = CertificateBundle.of(data.path("serial_number").asString(),
        data.path("certificate").asString(), data.path("issuing_ca").asString(),
        data.path("private_key").asString(), data.path("private_key_type").asString());
    try {
      char[] pw = "it-store".toCharArray();
      KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(bundle.createKeyStore("server", true, pw), pw);
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(kmf.getKeyManagers(), trustManagers(bundle), null);
      return ctx;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** An SSL context that trusts the intermediate and presents no client certificate. */
  public static SSLContext noClientCertContext() {
    role("mw-crypto", true);
    JsonNode data = post("/v1/pki_int/issue/mw-crypto", Map.of("common_name", "mw-crypto", "ttl", "1h")).path("data");
    CertificateBundle bundle = CertificateBundle.of(data.path("serial_number").asString(),
        data.path("certificate").asString(), data.path("issuing_ca").asString(),
        data.path("private_key").asString(), data.path("private_key_type").asString());
    try {
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, trustManagers(bundle), null);
      return ctx;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static javax.net.ssl.TrustManager[] trustManagers(CertificateBundle bundle) throws Exception {
    KeyStore trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("issuing-ca", bundle.getX509IssuerCertificate());
    TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trust);
    return tmf.getTrustManagers();
  }

  private static JsonNode post(String path, Object body) {
    try {
      HttpResponse<String> r = HTTP.send(
          HttpRequest.newBuilder(URI.create(address() + path)).header("X-Vault-Token", TOKEN)
              .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
          HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() / 100 != 2) {
        throw new IllegalStateException("Vault " + path + ": " + r.statusCode() + " " + r.body());
      }
      return r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
