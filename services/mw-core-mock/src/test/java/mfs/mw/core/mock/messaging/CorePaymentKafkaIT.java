package mfs.mw.core.mock.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import mfs.mw.core.mock.TestInfrastructure;
import mfs.mw.core.mock.contracts.ContractSchemas;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.Fault.FaultMode;
import mfs.mw.core.mock.dto.FaultRequest;
import mfs.mw.core.mock.security.PinKeyProvider;
import mfs.mw.core.mock.service.CoreCommandsTestSupport;
import mfs.mw.core.mock.service.FaultRegistry;
import mfs.mw.core.mock.service.WalletSeeder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The payment side over Kafka (ticket 14, decision recorded in CLAUDE.md): commands on {@code
 * core.payment.command.v1} are answered on {@code core.payment.event.v1}, and a command on the
 * wallet topic still gets its reply on the wallet event topic. A delayed reply really arrives late.
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
class CorePaymentKafkaIT {

  static final String PAYMENT_COMMANDS = "core.payment.command.v1";
  static final String PAYMENT_EVENTS = "core.payment.event.v1";
  static final String WALLET_COMMANDS = "core.wallet.command.v1";
  static final String WALLET_EVENTS = "core.wallet.event.v1";
  static final String A = "01711000007";

  private final EventPublisher publisher;
  private final KafkaContainer kafka;
  private final WalletSeeder seeder;
  private final FaultRegistry faults;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final CoreCommandsTestSupport cmds;

  @Autowired
  CorePaymentKafkaIT(
      EventPublisher publisher,
      KafkaContainer kafka,
      WalletSeeder seeder,
      FaultRegistry faults,
      JdbcTemplate jdbc,
      PinKeyProvider keys,
      JsonMapper json) {
    this.publisher = publisher;
    this.kafka = kafka;
    this.seeder = seeder;
    this.faults = faults;
    this.jdbc = jdbc;
    this.json = json;
    this.cmds = new CoreCommandsTestSupport(keys, json);
  }

  @BeforeEach
  void fresh() {
    faults.reset();
    seeder.reset();
  }

  @AfterEach
  void clear() {
    faults.reset();
  }

  private void send(String topic, CoreCommand c) {
    publisher.publish(topic, c.txnId(), new EventMetadata(c.correlationId(), c.txnId(), c.commandId(), 1), json.valueToTree(c));
  }

  private List<ConsumerRecord<String, String>> events(String topic, String txnId, int count) {
    return KafkaTestConsumer.read(kafka.getBootstrapServers(), topic, count, Duration.ofSeconds(60), r -> txnId.equals(r.key()));
  }

  @Test
  void aPaymentCommandIsAnsweredOnThePaymentEventTopic() {
    String txn = CoreCommandsTestSupport.txnId();
    CoreCommand hold = cmds.holdFunds(txn, A, 5_000, "1234");
    send(PAYMENT_COMMANDS, hold);

    JsonNode e = json.readTree(events(PAYMENT_EVENTS, txn, 1).get(0).value());

    assertThat(e.path("eventType").asString()).isEqualTo("FundsHeld");
    assertThat(ContractSchemas.schema("events/mw-core-mock/core.event.v1.schema.json").validate(e)).isEmpty();
    String holdId = e.path("payload").path("holdId").asString();

    CoreCommand commit = cmds.commitHold(txn, holdId);
    send(PAYMENT_COMMANDS, commit);
    List<ConsumerRecord<String, String>> both = events(PAYMENT_EVENTS, txn, 2);
    assertThat(json.readTree(both.get(1).value()).path("eventType").asString()).isEqualTo("HoldCommitted");
    assertThat(jdbc.queryForObject("select balance_minor from wallet where id = ?", Long.class, A))
        .isEqualTo(1_000_000 - 5_000 - 200);
  }

  @Test
  void anyCommandTypeOnTheWalletTopicIsAnsweredOnTheWalletEventTopic() {
    String txn = CoreCommandsTestSupport.txnId();
    send(WALLET_COMMANDS, cmds.creditWallet(txn, A, 7_000));

    JsonNode e = json.readTree(events(WALLET_EVENTS, txn, 1).get(0).value());

    assertThat(e.path("eventType").asString()).isEqualTo("WalletCredited");
  }

  @Test
  void aDelayedReplyArrivesLateAndADroppedOneNeverDoes() throws InterruptedException {
    faults.add(new FaultRequest(FaultMode.DELAY, "CreditWallet", null, 3_000L, null, null));
    String txn = CoreCommandsTestSupport.txnId();
    long sent = System.currentTimeMillis();
    send(WALLET_COMMANDS, cmds.creditWallet(txn, A, 1_000));

    events(WALLET_EVENTS, txn, 1);

    assertThat(System.currentTimeMillis() - sent).isGreaterThanOrEqualTo(2_800);

    faults.add(new FaultRequest(FaultMode.DROP_REPLY, "CreditWallet", null, null, null, null));
    String lost = CoreCommandsTestSupport.txnId();
    send(WALLET_COMMANDS, cmds.creditWallet(lost, A, 1_000));
    CoreCommand query = cmds.query(lost);
    long deadline = System.currentTimeMillis() + 30_000;
    while (jdbc.queryForObject("select count(*) from command_log where txn_id = ?", Long.class, lost) == 0
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(200);
    }
    send(WALLET_COMMANDS, query);

    JsonNode status = json.readTree(events(WALLET_EVENTS, lost, 1).get(0).value());
    assertThat(status.path("eventType").asString()).isEqualTo("TransactionStatus");
    assertThat(status.path("payload").path("txnStatus").asString()).isEqualTo("CREDITED");
  }
}
