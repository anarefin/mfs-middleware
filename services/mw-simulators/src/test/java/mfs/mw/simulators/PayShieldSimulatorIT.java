package mfs.mw.simulators;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The payShield simulator as the stack runs it (ticket 64): this service's image (module Dockerfile
 * and boot jar) with its LMK in the SoftHSM {@code paysim} token (the SoftHSM image built from
 * {@code src/test/resources/softhsm}, a checked copy of deploy/softhsm), over the host command port.
 * PKCS#11 runs only in the container (no PKCS#11 tooling on the host). Keys from A0, a PIN block
 * from the test helper, CA, JC + DG, DC (00, and 01 for a wrong PIN); then a second simulator on the
 * same token has the same LMK (check value) and still verifies with the first one's keys.
 */
class PayShieldSimulatorIT {

  static final Logger log = LoggerFactory.getLogger(PayShieldSimulatorIT.class);
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String PAYSIM_PIN = "it-paysim-user-pin";
  static final String PAN = "4000001234562000";
  static final String ACCOUNT = "000123456200";
  static final List<String> SOFTHSM_FILES =
      List.of("Dockerfile", "softhsm2.conf", "init-tokens.sh", "serve.sh", "remote-token.sh", "healthcheck.sh",
          "pkcs11-modules/mw-crypto.module", "pkcs11-modules/paysim.module");

  static final Network NETWORK = Network.newNetwork();
  static GenericContainer<?> softhsm;
  static PostgreSQLContainer postgres;

  @BeforeAll
  static void stack() {
    ImageFromDockerfile image = new ImageFromDockerfile("mfs-mw/softhsm-it", false);
    for (String f : SOFTHSM_FILES) {
      image = image.withFileFromClasspath(f, "softhsm/" + f);
    }
    softhsm = new GenericContainer<>(image)
        .withNetwork(NETWORK)
        .withNetworkAliases("softhsm")
        .withEnv("SOFTHSM_MWCRYPTO_LABEL", "mw-crypto")
        .withEnv("SOFTHSM_MWCRYPTO_SO_PIN", "it-so-pin-not-a-secret")
        .withEnv("SOFTHSM_MWCRYPTO_USER_PIN", "it-user-pin-not-a-secret")
        .withEnv("SOFTHSM_PAYSIM_LABEL", "paysim")
        .withEnv("SOFTHSM_PAYSIM_SO_PIN", "it-paysim-so-pin")
        .withEnv("SOFTHSM_PAYSIM_USER_PIN", PAYSIM_PIN)
        .waitingFor(Wait.forHealthcheck().withStartupTimeout(Duration.ofMinutes(2)));
    postgres = new PostgreSQLContainer("postgres:18.6-alpine3.24").withNetwork(NETWORK).withNetworkAliases("postgres");
    Startables.deepStart(softhsm, postgres).join();
  }

  @AfterAll
  static void down() {
    softhsm.stop();
    postgres.stop();
  }

  static GenericContainer<?> simulator(String name) {
    Path jar = Path.of("build/libs/mw-simulators.jar");
    if (!Files.isRegularFile(jar)) {
      throw new IllegalStateException("no " + jar + ": the test task depends on bootJar, run it through Gradle");
    }
    GenericContainer<?> c = new GenericContainer<>(
            new ImageFromDockerfile("mfs-mw/mw-simulators-it", false)
                .withFileFromPath("Dockerfile", Path.of("Dockerfile"))
                .withFileFromPath("build/libs/mw-simulators.jar", jar))
        .withNetwork(NETWORK)
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
        .withLogConsumer(new Slf4jLogConsumer(log).withPrefix(name))
        .waitingFor(Wait.forHttp("/actuator/health/readiness").forPort(8080).forStatusCode(200)
            .withStartupTimeout(Duration.ofMinutes(3)));
    c.start();
    return c;
  }

  /** One host command over a fresh connection: length, header "0001", body; the answer after the header. */
  static String command(GenericContainer<?> sim, String body) throws IOException {
    try (var s = new java.net.Socket(sim.getHost(), sim.getMappedPort(1500))) {
      s.setSoTimeout(15_000);
      var out = new DataOutputStream(s.getOutputStream());
      var in = new DataInputStream(s.getInputStream());
      byte[] b = ("0001" + body).getBytes(StandardCharsets.US_ASCII);
      out.writeShort(b.length);
      out.write(b);
      out.flush();
      byte[] r = new byte[in.readUnsignedShort()];
      in.readFully(r);
      String answer = new String(r, StandardCharsets.US_ASCII);
      assertThat(answer).startsWith("0001");
      return answer.substring(4);
    }
  }

  static String pinBlock(GenericContainer<?> sim, String tpk, String pin) throws Exception {
    HttpResponse<String> r = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://" + sim.getHost() + ":" + sim.getMappedPort(8080) + "/sim/payshield/pinblock"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"keyType\":\"TPK\",\"key\":\"" + tpk + "\",\"format\":\"ISO0\","
                + "\"pin\":\"" + pin + "\",\"pan\":\"" + PAN + "\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return JSON.readTree(r.body()).path("block").asString();
  }

  @Test
  void commandsRunWithTheLmkInThePaysimToken() throws Exception {
    GenericContainer<?> first = simulator("sim-1");
    String nc;
    String tpk;
    String pvk;
    String pvv;
    String logs;
    try {
      nc = command(first, "NC");
      assertThat(nc).startsWith("ND00").hasSize(29);
      tpk = command(first, "A00002U").substring(4, 37);
      String zpk = command(first, "A00001U").substring(4, 37);
      pvk = command(first, "A00002U").substring(4, 37);

      String block = pinBlock(first, tpk, "1234");
      assertThat(command(first, "CA" + tpk + zpk + "12" + block + "0101" + ACCOUNT)).matches("CB0004[0-9A-F]{16}01");
      String pinLmk = command(first, "JC" + tpk + block + "01" + ACCOUNT).substring(4);
      String dh = command(first, "DG" + pvk + pinLmk + ACCOUNT + "1");
      assertThat(dh).matches("DH00[0-9]{4}");
      pvv = dh.substring(4);
      assertThat(command(first, "DC" + tpk + pvk + block + "01" + ACCOUNT + "1" + pvv)).isEqualTo("DD00");
      assertThat(command(first, "DC" + tpk + pvk + pinBlock(first, tpk, "4321") + "01" + ACCOUNT + "1" + pvv))
          .isEqualTo("DD01");
    } finally {
      logs = first.getLogs();
      first.stop();
    }

    // the LMK stays in the token: a new simulator has the same one and reads the old keys
    GenericContainer<?> second = simulator("sim-2");
    try {
      assertThat(command(second, "NC")).isEqualTo(nc);
      assertThat(command(second, "DC" + tpk + pvk + pinBlock(second, tpk, "1234") + "01" + ACCOUNT + "1" + pvv))
          .isEqualTo("DD00");
    } finally {
      second.stop();
    }
    assertThat(logs).contains("payshield.lmk_connected").doesNotContain(PAYSIM_PIN);
  }
}
