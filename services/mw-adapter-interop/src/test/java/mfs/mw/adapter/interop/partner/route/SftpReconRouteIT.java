package mfs.mw.adapter.interop.partner.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import mfs.mw.adapter.interop.TestInfrastructure;
import mfs.mw.adapter.interop.contracts.ContractSchemas;
import mfs.mw.adapter.interop.dto.ConfigChangedEvent;
import mfs.mw.adapter.interop.messaging.PartnerReconPublisher;
import mfs.mw.adapter.interop.partner.FakeVaultFunction;
import mfs.mw.adapter.interop.partner.config.PartnerConfigStore;
import mfs.mw.adapter.interop.partner.config.PartnerProperties;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Batch partners over SFTP (ticket 86), in the running service against an SFTP server in
 * Testcontainers (atmoz/sftp, pinned as in deploy/docker-compose.yml): a batch record goes live
 * from {@code mw.config.changed.v1} like any other; a file dropped on the partner host is read
 * once, each data line is a {@code mw.partner.recon.v1} outbox row matching the contract, and the
 * file moves to {@code .done}; the same file put back is not read again; a file with a bad line
 * moves to {@code .error} and publishes nothing; a disabled record polls nothing. Strict host key
 * checking: the host key must be in the partner's known hosts (a field of its Vault secret), an
 * unknown one is refused before any login. Identical in telco, billpay and bank (ticket 84).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
@DirtiesContext
class SftpReconRouteIT {

  static final String IMAGE =
      "atmoz/sftp:alpine@sha256:c5fb9e4beb8b7054a4c0b65c5e11df47cc20da95ad6002e88aecff62bab06372";
  static final String HOME = "/home/bits/recon";
  static final JsonMapper JSON = JsonMapper.builder().build();

  @SuppressWarnings("resource")
  static final GenericContainer<?> SFTP = new GenericContainer<>(DockerImageName.parse(IMAGE))
      .withCommand("bits:fake-sftp-password-for-tests:1001::recon,out,disabled")
      .withExposedPorts(22)
      .waitingFor(Wait.forLogMessage(".*Server listening on 0\\.0\\.0\\.0 port 22.*", 1))
      .withStartupTimeout(Duration.ofSeconds(60));

  static String knownHosts;

  @BeforeAll
  static void sftp() throws Exception {
    SFTP.start();
    StringBuilder lines = new StringBuilder();
    for (String type : List.of("ed25519", "rsa")) {
      String pub = SFTP.execInContainer("cat", "/etc/ssh/ssh_host_" + type + "_key.pub").getStdout().trim();
      String[] parts = pub.split(" ");
      lines.append("[localhost]:").append(SFTP.getMappedPort(22)).append(' ').append(parts[0]).append(' ').append(parts[1]).append('\n');
    }
    knownHosts = lines.toString();
  }

  @AfterAll
  static void stopSftp() {
    SFTP.stop();
    FakeVaultFunction.VALUES.clear();
  }

  private final PartnerConfigStore store;
  private final JdbcTemplate jdbc;
  private final CamelContext camel;
  private final PartnerRouteHealth health;
  private final ProducerTemplate producer;
  private final String family;
  private final String service;

  @Autowired
  SftpReconRouteIT(
      PartnerConfigStore store, JdbcTemplate jdbc, CamelContext camel, PartnerRouteHealth health, ProducerTemplate producer,
      PartnerProperties properties, @org.springframework.beans.factory.annotation.Value("${spring.application.name}") String service) {
    this.store = store;
    this.jdbc = jdbc;
    this.camel = camel;
    this.health = health;
    this.producer = producer;
    this.family = properties.family();
    this.service = service;
  }

  void secrets(String partnerId, String knownHostsValue) {
    FakeVaultFunction.VALUES.put("secret:" + service + "/" + partnerId + "#sftp-password", "fake-sftp-password-for-tests");
    FakeVaultFunction.VALUES.put("secret:" + service + "/" + partnerId + "#sftp-known-hosts", knownHostsValue);
  }

  void publish(String partnerId, long version, boolean enabled, String directory) {
    publish(partnerId, version, enabled, directory, "delay=100");
  }

  /** Publishes a batch record polling {@code directory} with {@code poll} ({@code delay=...} or {@code cron=...}). */
  void publish(String partnerId, long version, boolean enabled, String directory, String poll) {
    String route = """
        - route:
            from:
              uri: "kamelet:mw-sftp-source?host=localhost&port=%d&username=bits&directory=%s&secretField=sftp-password&knownHostsField=sftp-known-hosts&%s"
              steps:
                - log: "reconciliation file received"
                - to: "kamelet:mw-recon?delimiter=comma"
        """.formatted(SFTP.getMappedPort(22), directory, poll);
    Map<String, Object> value = Map.of("family", family, "enabled", enabled, "allowedHosts", List.of("localhost"), "route", route);
    store.apply(new ConfigChangedEvent(
        1, "partner/" + partnerId, ConfigChangedEvent.ConfigType.PARTNER, ConfigChangedEvent.Change.UPSERT, version, value,
        Instant.now(), "ops.maker1", "ops.checker1", "corr-cfg"));
  }

  void drop(String path, String content) {
    SFTP.copyFileToContainer(Transferable.of(content), path);
  }

  boolean exists(String path) {
    try {
      return SFTP.execInContainer("test", "-f", path).getExitCode() == 0;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static void await(BooleanSupplier condition, String what) {
    Awaitility.await(what).atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
        .until(condition::getAsBoolean);
  }

  List<JsonNode> recon(String partnerId, String fileName) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = ? and msg_key = ? and payload->>'fileName' = ? order by id",
            String.class, PartnerReconPublisher.TOPIC, partnerId, fileName)
        .stream().map(JSON::readTree).toList();
  }

  @Test
  void aDroppedFileIsReadOncePublishedLineByLineAndMovedToDone() throws Exception {
    secrets("recon-a", knownHosts);
    drop(HOME + "/recon-20261118.csv", "reference,amount,status\nBITS01,500.00,SUCCESS\nBITS02,120.50,FAILED\n");
    publish("recon-a", 1, true, "recon");

    await(() -> exists(HOME + "/.done/recon-20261118.csv"), "the file in .done");

    List<JsonNode> lines = recon("recon-a", "recon-20261118.csv");
    assertThat(lines).hasSize(2);
    for (JsonNode line : lines) {
      assertThat(ContractSchemas.schema("events/mw-adapter-billpay/mw.partner.recon.v1.schema.json").validate(line)).isEmpty();
      assertThat(line.path("partnerId").asString()).isEqualTo("recon-a");
      assertThat(line.path("family").asString()).isEqualTo(family);
    }
    assertThat(lines.get(0).path("fields").path("reference").asString()).isEqualTo("BITS01");
    assertThat(lines.get(0).path("last").asBoolean()).isFalse();
    assertThat(lines.get(1).path("fields").path("amount").asString()).isEqualTo("120.50");
    assertThat(lines.get(1).path("last").asBoolean()).isTrue();
    assertThat(exists(HOME + "/recon-20261118.csv")).isFalse();

    // the same file (name, size, modification time) put back is not read again; a new file after it is
    SFTP.execInContainer("cp", "-p", HOME + "/.done/recon-20261118.csv", HOME + "/recon-20261118.csv");
    drop(HOME + "/recon-20261119.csv", "reference,amount,status\nBITS03,7.00,SUCCESS\n");

    await(() -> exists(HOME + "/.done/recon-20261119.csv"), "the next file in .done");
    assertThat(recon("recon-a", "recon-20261119.csv")).hasSize(1);
    assertThat(recon("recon-a", "recon-20261118.csv")).hasSize(2);
    assertThat(exists(HOME + "/recon-20261118.csv")).as("the duplicate is left where it is").isTrue();
  }

  @Test
  void aCronScheduledSourcePollsOnItsSchedule() throws Exception {
    secrets("recon-d", knownHosts);
    SFTP.execInContainer("sh", "-c", "mkdir -p /home/bits/out/cron && chown 1001 /home/bits/out/cron");
    drop("/home/bits/out/cron/recon-cron.csv", "reference,amount\nBITS09,1.00\n");
    // every second: the Spring scheduler of the file consumer, not the delay
    publish("recon-d", 1, true, "out/cron", "cron=*+*+*+*+*+*");

    await(() -> exists("/home/bits/out/cron/.done/recon-cron.csv"), "the cron-polled file in .done");

    assertThat(recon("recon-d", "recon-cron.csv")).hasSize(1);
  }

  @Test
  void aBadFileMovesToErrorAndPublishesNothing() throws Exception {
    secrets("recon-b", knownHosts);
    SFTP.execInContainer("sh", "-c", "mkdir -p /home/bits/out/bad && chown 1001 /home/bits/out/bad");
    drop("/home/bits/out/bad/recon-bad.csv", "reference,amount\nBITS01,500.00\n01712345678\n");
    publish("recon-b", 1, true, "out/bad");

    await(() -> exists("/home/bits/out/bad/.error/recon-bad.csv"), "the file in .error");

    assertThat(recon("recon-b", "recon-bad.csv")).isEmpty();
  }

  @Test
  void aDisabledBatchRoutePollsNothingAndEnablingStartsIt() throws Exception {
    secrets("recon-c", knownHosts);
    publish("recon-c", 1, false, "disabled");

    PartnerRuntime disabled = store.instance("recon-c").orElseThrow();
    assertThat(camel.getRoute(disabled.routeId())).isNull();
    assertThat(health.state(store.entries().get("recon-c"))).containsEntry("status", "DISABLED");

    publish("recon-c", 2, true, "disabled");

    PartnerRuntime enabled = store.instance("recon-c").orElseThrow();
    assertThat(camel.getRoute(enabled.routeId())).isNotNull();
    assertThat(health.state(store.entries().get("recon-c"))).containsEntry("status", "UP");
  }

  @Test
  void theSinkPutsAFileAndAnUnknownHostKeyIsRefused() throws Exception {
    camel.getRegistry().bind("it-sftp-password", String.class, "fake-sftp-password-for-tests");
    camel.getRegistry().bind("it-sftp-known", String.class, knownHosts);
    // a host key of another server: the same format, another key
    camel.getRegistry().bind("it-sftp-other", String.class,
        "[localhost]:" + SFTP.getMappedPort(22) + " ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFIC+D34O5RchfqWYexwylSdyJRlNL8S2gxKhMbbkdnE\n");
    String sink = "kamelet:mw-sftp-sink?partnerId=it&host=localhost&port=" + SFTP.getMappedPort(22)
        + "&username=bits&directory=out&fileName=ack-${date:now:yyyyMMdd}.csv&password=#bean:it-sftp-password";
    camel.addRoutes(new RouteBuilder() {
      @Override
      public void configure() {
        from("direct:it-sftp-put").routeId("it-sftp-put").to(sink + "&knownHosts=#bean:it-sftp-known");
        from("direct:it-sftp-put-other").routeId("it-sftp-put-other").to(sink + "&knownHosts=#bean:it-sftp-other");
      }
    });

    producer.sendBody("direct:it-sftp-put", "reference,status\nBITS01,ACK\n");
    String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    assertThat(SFTP.execInContainer("cat", "/home/bits/out/ack-" + today + ".csv").getStdout()).isEqualTo("reference,status\nBITS01,ACK\n");

    assertThatThrownBy(() -> producer.sendBody("direct:it-sftp-put-other", "x"))
        .hasStackTraceContaining("HostKey");
  }
}
