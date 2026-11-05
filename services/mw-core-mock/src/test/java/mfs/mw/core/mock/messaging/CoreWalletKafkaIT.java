package mfs.mw.core.mock.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.core.mock.TestInfrastructure;
import mfs.mw.core.mock.contracts.ContractSchemas;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.Party;
import mfs.mw.core.mock.security.PinKeyProvider;
import mfs.mw.core.mock.security.TestPins;
import mfs.mw.core.mock.service.WalletSeeder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Core-mock end to end over Kafka: a command on {@code core.wallet.command.v1} is answered on
 * {@code core.wallet.event.v1} (through the outbox relay); a repeated {@code commandId} gets the
 * same event again. PIN plaintext never reaches the logs (SEC-10).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.coremock.consumer.enabled=true",
      "mw.outbox.relay.enabled=true",
      "mw.kafka.retry.delays=1s,1s,1s"
    })
@Import(TestInfrastructure.class)
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class CoreWalletKafkaIT {

  static final String COMMANDS = "core.wallet.command.v1";
  static final String EVENTS = "core.wallet.event.v1";
  static final String PIN = "739105";
  static final String WRONG_PIN = "482716";

  private final EventPublisher publisher;
  private final KafkaContainer kafka;
  private final JdbcTemplate jdbc;
  private final WalletSeeder seeder;
  private final PasswordEncoder encoder;
  private final PinKeyProvider keys;
  private final JsonMapper json;

  @Autowired
  CoreWalletKafkaIT(
      EventPublisher publisher,
      KafkaContainer kafka,
      JdbcTemplate jdbc,
      WalletSeeder seeder,
      PasswordEncoder encoder,
      PinKeyProvider keys,
      JsonMapper json) {
    this.publisher = publisher;
    this.kafka = kafka;
    this.jdbc = jdbc;
    this.seeder = seeder;
    this.encoder = encoder;
    this.keys = keys;
    this.json = json;
  }

  @BeforeEach
  void freshLedger() {
    seeder.reset();
    jdbc.update("update wallet set pin_hash = ? where id = '01711000005'", encoder.encode(PIN));
  }

  private String send(String commandId, String txnId, String pin) {
    Instant now = Instant.now();
    Map<String, Object> cmd = new LinkedHashMap<>();
    cmd.put("schemaVersion", 1);
    cmd.put("commandType", "PostTransfer");
    cmd.put("commandId", commandId);
    cmd.put("txnId", txnId);
    cmd.put("correlationId", "corr-kafka-it");
    cmd.put("issuedAt", now.toString());
    cmd.put("expiresAt", now.plusSeconds(300).toString());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("txnType", "P2P");
    payload.put("debitParty", new Party("WALLET", "01711000005", null, "CUSTOMER"));
    payload.put("creditParty", new Party("WALLET", "01711000006", null, "CUSTOMER"));
    payload.put("amount", Amount.bdt(25_000));
    payload.put("encryptedPin", TestPins.encrypt(keys.publicKeyPem(), keys.keyId(), pin));
    cmd.put("payload", payload);
    publisher.publish(COMMANDS, txnId, new EventMetadata("corr-kafka-it", txnId, commandId, 1), cmd);
    return commandId;
  }

  private List<ConsumerRecord<String, String>> events(String txnId, int count) {
    return KafkaTestConsumer.read(kafka.getBootstrapServers(), EVENTS, count, Duration.ofSeconds(60), r -> txnId.equals(r.key()));
  }

  private static String txnId() {
    String u = UUID.randomUUID().toString();
    return u.substring(0, 14) + "7" + u.substring(15);
  }

  @Test
  void aCommandIsAnsweredOnTheEventTopicAndARepeatGetsTheSameEvent() {
    String txn = txnId();
    send(txn + ":post", txn, PIN);
    send(txn + ":post", txn, PIN);

    List<ConsumerRecord<String, String>> got = events(txn, 2);

    assertThat(got).hasSize(2);
    JsonNode first = json.readTree(got.get(0).value());
    assertThat(first).isEqualTo(json.readTree(got.get(1).value()));
    assertThat(first.path("eventType").asString()).isEqualTo("TransferPosted");
    assertThat(ContractSchemas.schema("events/mw-core-mock/core.event.v1.schema.json").validate(first)).isEmpty();
    assertThat(KafkaTestConsumer.headers(got.get(0))).containsEntry("mw-txn-id", txn);
    assertThat(jdbc.queryForObject("select balance_minor from wallet where id = '01711000005'", Long.class))
        .isEqualTo(1_000_000 - 25_000 - 500);
  }

  @Test
  void pinPlaintextNeverAppearsInTheLogs(CapturedOutput output) {
    String ok = txnId();
    String bad = txnId();
    send(ok + ":post", ok, PIN);
    send(bad + ":post", bad, WRONG_PIN);

    assertThat(events(ok, 1)).hasSize(1);
    assertThat(events(bad, 1)).hasSize(1);
    assertThat(jdbc.queryForObject("select count(*) from command_log", Long.class)).isEqualTo(2L);

    assertThat(output.getAll()).contains("core.command.handled");
    assertThat(output.getAll()).doesNotContain(PIN).doesNotContain(WRONG_PIN);
  }
}
