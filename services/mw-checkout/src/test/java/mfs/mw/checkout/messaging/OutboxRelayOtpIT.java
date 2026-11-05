package mfs.mw.checkout.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.checkout.TestInfrastructure;
import mfs.mw.checkout.TestTopics;
import mfs.mw.checkout.dto.NotificationRequest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The checkout OTP does not stay in clear in the outbox (review A8): the relay sends the OTP SMS
 * request to Kafka with its {@code params.otp} and, in the statement that marks the row sent,
 * removes the OTP from the stored row. Other params and other topics' records are kept as they
 * are. The relay route is off (test default); the test drives {@link OutboxRelay} itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class OutboxRelayOtpIT {

  static final String NOTIFY = "mw.notify.request.v1";
  static final String OTHER = "conformance.outbox.otp.v1";

  private final OutboxWriter outbox;
  private final OutboxRelay relay;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  @Autowired
  OutboxRelayOtpIT(OutboxWriter outbox, OutboxRelay relay, JdbcTemplate jdbc, TransactionTemplate tx) {
    this.outbox = outbox;
    this.relay = relay;
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @BeforeEach
  void clean() {
    TestTopics.ensure(NOTIFY, OTHER);
    jdbc.update("delete from outbox");
  }

  static NotificationRequest otpSms(String messageId, String otp) {
    return new NotificationRequest(1, messageId, "corr-1", new NotificationRequest.Recipient("01711000001"),
        "CHECKOUT_OTP", "en", Map.of("otp", otp, "amount", "25.50", "merchant", "Demo Shop"), List.of("SMS"), "OTP",
        null);
  }

  long append(String topic, String messageId, Object payload) {
    tx.executeWithoutResult(s -> outbox.append(topic, "01711000001",
        new EventMetadata("corr-1", messageId, messageId, 1), payload));
    return jdbc.queryForObject("select max(id) from outbox", Long.class);
  }

  String payload(long id) {
    return jdbc.queryForObject("select payload::text from outbox where id = ?", String.class, id);
  }

  @Test
  void theOtpGoesToKafkaAndIsGoneFromTheRowOnceRelayed() {
    String messageId = "checkout-otp:" + UUID.randomUUID() + ":1";
    long id = append(NOTIFY, messageId, otpSms(messageId, "042917"));
    assertThat(payload(id)).as("kept for the relay until sent").contains("042917");

    assertThat(relay.relayPending()).isEqualTo(1);

    List<ConsumerRecord<String, String>> sent = KafkaTestConsumer.read(
        TestTopics.bootstrap(), NOTIFY, 1, Duration.ofSeconds(20), r -> r.value().contains(messageId));
    assertThat(sent).singleElement().satisfies(r -> assertThat(r.value()).contains("\"otp\":\"042917\""));
    assertThat(jdbc.queryForObject("select sent_at is not null from outbox where id = ?", Boolean.class, id)).isTrue();
    assertThat(payload(id)).doesNotContain("042917").doesNotContain("\"otp\"")
        .contains("\"amount\": \"25.50\"").contains("\"templateCode\": \"CHECKOUT_OTP\"");
  }

  @Test
  void anotherTopicsRecordIsStoredAsItWas() {
    long id = append(OTHER, "m-" + UUID.randomUUID(), Map.of("params", Map.of("otp", "keep-me")));

    assertThat(relay.relayPending()).isEqualTo(1);

    assertThat(payload(id)).contains("keep-me");
  }
}
