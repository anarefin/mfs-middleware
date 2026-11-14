package mfs.mw.crypto.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import mfs.mw.crypto.client.PinBlockProvider.PinKey;
import mfs.mw.crypto.config.PayShieldProperties;
import mfs.mw.crypto.exception.MwException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The payShield provider (ticket 64) against the real simulator in a Testcontainer: the
 * mw-simulators image ({@code mfs-mw/mw-simulators:local}, or {@code -Dmw.payshield-sim.image}) with
 * its LMK in the SoftHSM {@code paysim} token (from this module's copy of deploy/softhsm). Module
 * isolation forbids building another module's image here, so the test is skipped unless that image
 * exists ({@code ./gradlew :mw-simulators:bootJar && docker build -t mfs-mw/mw-simulators:local
 * services/mw-simulators}; the dev stack's build makes it too). The provider runs in this JVM (plain
 * TCP, no PKCS#11). PIN blocks come from the simulator's test helper, playing the ATM that shares the
 * TPK.
 */
class PayShieldProviderIT {

  static final Logger log = LoggerFactory.getLogger(PayShieldProviderIT.class);
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String IMAGE = System.getProperty("mw.payshield-sim.image", "mfs-mw/mw-simulators:local");
  static final String PAYSIM_PIN = "it-paysim-user-pin";
  static final String ACCOUNT = "4000001234562000";
  static final List<String> SOFTHSM_FILES =
      List.of("Dockerfile", "softhsm2.conf", "init-tokens.sh", "serve.sh", "remote-token.sh", "healthcheck.sh",
          "pkcs11-modules/mw-crypto.module", "pkcs11-modules/paysim.module");

  static Network network;
  static GenericContainer<?> softhsm;
  static PostgreSQLContainer postgres;
  static GenericContainer<?> simulator;
  static PayShieldProvider provider;

  @BeforeAll
  static void stack() {
    boolean present;
    try {
      DockerClientFactory.instance().client().inspectImageCmd(IMAGE).exec();
      present = true;
    } catch (RuntimeException e) {
      present = false;
    }
    Assumptions.assumeTrue(present, "no image " + IMAGE + ": build mw-simulators' image first");
    network = Network.newNetwork();
    ImageFromDockerfile image = new ImageFromDockerfile("mfs-mw/softhsm-it", false);
    for (String f : SOFTHSM_FILES) {
      image = image.withFileFromClasspath(f, "softhsm/" + f);
    }
    softhsm = new GenericContainer<>(image)
        .withNetwork(network)
        .withNetworkAliases("softhsm")
        .withEnv("SOFTHSM_MWCRYPTO_LABEL", "mw-crypto")
        .withEnv("SOFTHSM_MWCRYPTO_SO_PIN", "it-so-pin-not-a-secret")
        .withEnv("SOFTHSM_MWCRYPTO_USER_PIN", "it-user-pin-not-a-secret")
        .withEnv("SOFTHSM_PAYSIM_LABEL", "paysim")
        .withEnv("SOFTHSM_PAYSIM_SO_PIN", "it-paysim-so-pin")
        .withEnv("SOFTHSM_PAYSIM_USER_PIN", PAYSIM_PIN)
        .waitingFor(Wait.forHealthcheck().withStartupTimeout(Duration.ofMinutes(2)));
    postgres = new PostgreSQLContainer("postgres:18.6-alpine3.24").withNetwork(network).withNetworkAliases("postgres");
    Startables.deepStart(softhsm, postgres).join();
    simulator = new GenericContainer<>(IMAGE)
        .withNetwork(network)
        .withExposedPorts(8080, 1500)
        .withEnv("DB_URL", "jdbc:postgresql://postgres:5432/" + postgres.getDatabaseName())
        .withEnv("DB_USERNAME", postgres.getUsername())
        .withEnv("DB_PASSWORD", postgres.getPassword())
        .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka.invalid:9092")
        .withEnv("MW_OUTBOX_RELAY_ENABLED", "false")
        .withEnv("SIM_SMSC_ENABLED", "false")
        .withEnv("SIM_USSDGATEWAY_SMPP_ENABLED", "false")
        .withEnv("SIM_PAYSHIELD_PKCS11_USERPIN", PAYSIM_PIN)
        .withEnv("JAVA_TOOL_OPTIONS", "-XX:+UseSerialGC -Xmx192m")
        .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("mw-simulators"))
        .waitingFor(Wait.forHttp("/actuator/health/readiness").forPort(8080).forStatusCode(200)
            .withStartupTimeout(Duration.ofMinutes(3)));
    simulator.start();
    var props = new PayShieldProperties(true, simulator.getHost(), simulator.getMappedPort(1500), 4, 2,
        Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(2), List.of(), null);
    provider = new PayShieldProvider(props, CircuitBreakerRegistry.ofDefaults());
  }

  @AfterAll
  static void down() {
    if (provider != null) {
      provider.shutdown();
    }
    for (GenericContainer<?> c : new GenericContainer<?>[] {simulator, softhsm, postgres}) {
      if (c != null) {
        c.stop();
      }
    }
  }

  static HttpResponse<String> control(String method, String path, String json) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://" + simulator.getHost() + ":" + simulator.getMappedPort(8080) + path))
        .header("Content-Type", "application/json");
    b = json == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
        : b.method(method, HttpRequest.BodyPublishers.ofString(json));
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** The ATM side: {@code pin} as a block under the TPK blob it shares. */
  static String atmBlock(String tpk, String format, String pin) throws Exception {
    HttpResponse<String> r = control("POST", "/sim/payshield/pinblock", "{\"keyType\":\"TPK\",\"key\":\"" + tpk
        + "\",\"format\":\"" + format + "\",\"pin\":\"" + pin + "\",\"pan\":\"" + ACCOUNT + "\"}");
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return JSON.readTree(r.body()).path("block").asString();
  }

  @Test
  void pvvGeneratedThroughTheProviderVerifiesAndAWrongPinDoesNot() throws Exception {
    assertThat(provider.probe()).isTrue();
    String tpk = provider.createKey("tpk.v1", KeyAlgorithm.TDES_2KEY, "TPK");
    String zpk = provider.createKey("zpk.v1", KeyAlgorithm.TDES_2KEY, "ZPK");
    String zpkAes = provider.createKey("zpk-aes.v1", KeyAlgorithm.AES_128, "ZPK_AES");
    String pvk = provider.createKey("pvk.v1", KeyAlgorithm.TDES_2KEY, "PVK");
    assertThat(List.of(tpk, zpk, pvk)).allMatch(b -> b.matches("U[0-9A-F]{32}"));
    assertThat(zpkAes).matches("S[0-9A-F]{32}");

    String block = atmBlock(tpk, "ISO0", "1234");
    PinKey terminal = new PinKey(tpk, true);
    String pvv = provider.generatePvv(pvk, terminal, block, PinBlockFormat.ISO0, ACCOUNT, 1);
    assertThat(pvv).matches("[0-9]{4}");
    assertThat(provider.verifyPvv(pvk, terminal, block, PinBlockFormat.ISO0, ACCOUNT, 1, pvv)).isTrue();
    assertThat(provider.verifyPvv(pvk, terminal, atmBlock(tpk, "ISO0", "1243"), PinBlockFormat.ISO0, ACCOUNT, 1, pvv))
        .isFalse();

    // translated to a ZPK the PIN still verifies, through EC; and to format 4 under the AES ZPK
    String zoneBlock = provider.translate(terminal, block, PinBlockFormat.ISO0, new PinKey(zpk, false),
        PinBlockFormat.ISO3, ACCOUNT);
    assertThat(zoneBlock).matches("[0-9A-F]{16}");
    assertThat(provider.verifyPvv(pvk, new PinKey(zpk, false), zoneBlock, PinBlockFormat.ISO3, ACCOUNT, 1, pvv))
        .isTrue();
    assertThat(provider.translate(new PinKey(zpk, false), zoneBlock, PinBlockFormat.ISO3, new PinKey(zpkAes, false),
        PinBlockFormat.ISO4, ACCOUNT)).matches("[0-9A-F]{32}");

    // a ZPK given as the TPK: key parity error 10 -> MW-VAL-015
    assertThatThrownBy(() -> provider.verifyPvv(pvk, new PinKey(zpk, true), block, PinBlockFormat.ISO0, ACCOUNT, 1,
        pvv)).satisfies(e -> assertThat(((MwException) e).code()).isEqualTo("MW-VAL-015"));
  }

  @Test
  void scenarioErrorsAndAnOutageFailClosed() throws Exception {
    try {
      control("POST", "/sim/payshield/scenario", "{\"errorCode\":\"68\",\"commands\":[\"A0\"]}");
      assertThatThrownBy(() -> provider.createKey("x.v1", KeyAlgorithm.TDES_2KEY, "ZPK"))
          .satisfies(e -> assertThat(((MwException) e).code()).isEqualTo("MW-SYS-010"));
      control("POST", "/sim/payshield/scenario", "{\"outage\":true}");
      assertThat(provider.probe()).isFalse();
      control("DELETE", "/sim/payshield/scenario", null);
      assertThat(provider.probe()).isTrue();
    } finally {
      control("DELETE", "/sim/payshield/scenario", null);
    }
  }
}
