package mfs.mw.checkout.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.checkout.TestInfrastructure;
import mfs.mw.checkout.dto.NotificationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The checkout OTP does not stay in clear in the outbox (review A8): once a notify request is
 * relayed, its {@code params.otp} is removed from the stored row; rows not yet relayed, other
 * params and other records are left as they are. Relay and schedule off: the test relays by
 * stamping {@code sent_at} and runs the scrub itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class OutboxOtpScrubberIT {

  static final String NOTIFY = "mw.notify.request.v1";

  private final OutboxWriter outbox;
  private final OutboxOtpScrubber scrubber;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  @Autowired
  OutboxOtpScrubberIT(OutboxWriter outbox, OutboxOtpScrubber scrubber, JdbcTemplate jdbc, TransactionTemplate tx) {
    this.outbox = outbox;
    this.scrubber = scrubber;
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @BeforeEach
  void clean() {
    jdbc.update("delete from outbox");
  }

  static NotificationRequest otpSms(String otp) {
    String id = "checkout-otp:" + UUID.randomUUID() + ":1";
    return new NotificationRequest(1, id, "corr-1", new NotificationRequest.Recipient("01711000001"),
        "CHECKOUT_OTP", "en", Map.of("otp", otp, "amount", "25.50", "merchant", "Demo Shop"), List.of("SMS"), "OTP",
        null);
  }

  long append(String topic, Object payload) {
    tx.executeWithoutResult(s -> outbox.append(topic, "01711000001",
        new EventMetadata("corr-1", "msg-" + UUID.randomUUID(), "msg-" + UUID.randomUUID(), 1), payload));
    return jdbc.queryForObject("select max(id) from outbox", Long.class);
  }

  void relayed(long id) {
    jdbc.update("update outbox set sent_at = now() where id = ?", id);
  }

  String payload(long id) {
    return jdbc.queryForObject("select payload::text from outbox where id = ?", String.class, id);
  }

  @Test
  void aRelayedOtpIsRemovedFromItsRowAndTheRestKept() {
    long id = append(NOTIFY, otpSms("042917"));
    relayed(id);

    assertThat(scrubber.scrubRelayed()).isEqualTo(1);

    assertThat(payload(id)).doesNotContain("042917").doesNotContain("\"otp\"")
        .contains("\"amount\": \"25.50\"").contains("\"templateCode\": \"CHECKOUT_OTP\"");
    assertThat(scrubber.scrubRelayed()).as("nothing left").isZero();
  }

  @Test
  void anOtpNotRelayedYetIsKeptForTheRelay() {
    long id = append(NOTIFY, otpSms("042917"));

    assertThat(scrubber.scrubRelayed()).isZero();
    assertThat(payload(id)).contains("042917");
  }

  @Test
  void otherRecordsAreNotTouched() {
    long other = append("conformance.outbox.event.v1", Map.of("params", Map.of("otp", "keep-me")));
    relayed(other);

    assertThat(scrubber.scrubRelayed()).isZero();
    assertThat(payload(other)).contains("keep-me");
  }
}
