package mfs.mw.crypto;

import com.github.dockerjava.api.DockerClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import mfs.mw.crypto.security.InternalTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The HSM integration test stack (CLAUDE.md, "HSM integration tests"): PKCS#11 never runs in the host
 * test JVM, so this service runs in its own image (the module {@code Dockerfile} with the boot jar
 * the {@code test} task depends on) on a private Docker network next to the SoftHSM container (built
 * from {@code src/test/resources/softhsm/}, a checked copy of {@code deploy/softhsm}, network alias
 * {@code softhsm}, the name the image's p11-kit module file dials) and a PostgreSQL (alias {@code
 * postgres}). No OpenBao: the token user PIN comes in as an environment variable. The test talks to
 * the service over HTTP with tokens of {@link InternalTokens}, whose JWKS the container reaches on
 * {@code host.testcontainers.internal}. Started once per JVM, removed by Ryuk; tickets 63–65 add
 * their tests on the same stack ({@link #get}, {@link #post}).
 */
public final class HsmStack {

  public static final String USER_PIN = "it-user-pin-not-a-secret";
  private static final Logger log = LoggerFactory.getLogger(HsmStack.class);
  private static final List<String> SOFTHSM_FILES =
      List.of("Dockerfile", "softhsm2.conf", "init-tokens.sh", "serve.sh", "remote-token.sh", "healthcheck.sh",
          "pkcs11-modules/mw-crypto.module", "pkcs11-modules/paysim.module");

  static final Network NETWORK = Network.newNetwork();

  public static final GenericContainer<?> SOFTHSM =
      new GenericContainer<>(softhsmImage())
          .withNetwork(NETWORK)
          .withNetworkAliases("softhsm")
          .withEnv("SOFTHSM_MWCRYPTO_LABEL", "mw-crypto")
          .withEnv("SOFTHSM_MWCRYPTO_SO_PIN", "it-so-pin-not-a-secret")
          .withEnv("SOFTHSM_MWCRYPTO_USER_PIN", USER_PIN)
          .withEnv("SOFTHSM_PAYSIM_LABEL", "paysim")
          .withEnv("SOFTHSM_PAYSIM_SO_PIN", "it-paysim-so-pin")
          .withEnv("SOFTHSM_PAYSIM_USER_PIN", "it-paysim-user-pin")
          .waitingFor(Wait.forHealthcheck().withStartupTimeout(Duration.ofMinutes(2)));

  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.6-alpine3.24").withNetwork(NETWORK).withNetworkAliases("postgres");

  private static GenericContainer<?> crypto;
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private HsmStack() {}

  private static ImageFromDockerfile softhsmImage() {
    ImageFromDockerfile image = new ImageFromDockerfile("mfs-mw/softhsm-it", false);
    for (String f : SOFTHSM_FILES) {
      image = image.withFileFromClasspath(f, "softhsm/" + f);
    }
    return image;
  }

  /** Starts the stack once (idempotent), with the dev key bootstrap on (every 2 s). */
  public static synchronized GenericContainer<?> start() {
    if (crypto != null) {
      return crypto;
    }
    Path jar = Path.of("build/libs/mw-crypto.jar");
    if (!Files.isRegularFile(jar)) {
      throw new IllegalStateException("no " + jar + ": the test task depends on bootJar, run it through Gradle");
    }
    String jwks = InternalTokens.jwkSetUri();
    int jwksPort = URI.create(jwks).getPort();
    Testcontainers.exposeHostPorts(jwksPort);
    crypto =
        new GenericContainer<>(
                new ImageFromDockerfile("mfs-mw/mw-crypto-it", false)
                    .withFileFromPath("Dockerfile", Path.of("Dockerfile"))
                    .withFileFromPath("build/libs/mw-crypto.jar", jar))
            .withNetwork(NETWORK)
            .withExposedPorts(8080)
            .withEnv("DB_URL", "jdbc:postgresql://postgres:5432/" + POSTGRES.getDatabaseName())
            .withEnv("DB_USERNAME", POSTGRES.getUsername())
            .withEnv("DB_PASSWORD", POSTGRES.getPassword())
            .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka.invalid:9092")
            .withEnv("MW_OUTBOX_RELAY_ENABLED", "false")
            .withEnv("MW_CRYPTO_PKCS11_USER_PIN", USER_PIN)
            .withEnv("MW_CRYPTO_BOOTSTRAP_KEYS", "true")
            .withEnv("MW_CRYPTO_BOOTSTRAP_PERIOD", "2s")
            .withEnv("MW_CRYPTO_HSM_PROBE_PERIOD", "1s")
            .withEnv("MW_INTERNAL_JWK_SET_URI", "http://host.testcontainers.internal:" + jwksPort + "/certs")
            .withEnv("MW_INTERNAL_ISSUER", InternalTokens.ISSUER)
            .withEnv("JAVA_TOOL_OPTIONS", "-XX:+UseSerialGC -Xmx256m")
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("mw-crypto"))
            .dependsOn(SOFTHSM, POSTGRES)
            .waitingFor(Wait.forHttp("/actuator/health/readiness").forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(3)));
    Startables.deepStart(crypto).join();
    return crypto;
  }

  public static HttpResponse<String> get(String path, String token) {
    return send(HttpRequest.newBuilder(uri(path)).GET(), token);
  }

  public static HttpResponse<String> post(String path, String json, String token) {
    return send(
        HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)),
        token);
  }

  /** Stops the SoftHSM container (its token volume stays) so the HSM is unreachable. */
  public static void stopHsm() {
    docker().stopContainerCmd(SOFTHSM.getContainerId()).withTimeout(2).exec();
  }

  /** Starts the stopped SoftHSM container again, with the same tokens, and waits until it is healthy. */
  public static void startHsm() throws InterruptedException {
    docker().startContainerCmd(SOFTHSM.getContainerId()).exec();
    for (int i = 0; i < 120; i++) {
      var health = docker().inspectContainerCmd(SOFTHSM.getContainerId()).exec().getState().getHealth();
      if (health != null && "healthy".equals(health.getStatus())) {
        return;
      }
      Thread.sleep(500);
    }
    throw new IllegalStateException("softhsm did not become healthy again");
  }

  private static DockerClient docker() {
    return DockerClientFactory.instance().client();
  }

  private static URI uri(String path) {
    return URI.create("http://" + crypto.getHost() + ":" + crypto.getMappedPort(8080) + path);
  }

  private static HttpResponse<String> send(HttpRequest.Builder b, String token) {
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    try {
      return HTTP.send(b.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
