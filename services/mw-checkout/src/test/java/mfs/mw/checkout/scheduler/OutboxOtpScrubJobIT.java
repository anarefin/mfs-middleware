package mfs.mw.checkout.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.checkout.TestInfrastructure;
import mfs.mw.checkout.TestTopics;
import mfs.mw.checkout.dto.NotificationRequest;
import mfs.mw.checkout.messaging.EventMetadata;
import mfs.mw.checkout.messaging.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * With the relay and the scrub schedule on (as in production), an OTP notify request is relayed
 * and its OTP gone from the outbox row within seconds (review A8), not after the 7 day purge.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.outbox.relay.enabled=true", "mw.outbox.relay.period=200ms",
      "mw.outbox.otp-scrub.cron=* * * * * *"
    })
@Import(TestInfrastructure.class)
@DirtiesContext
class OutboxOtpScrubJobIT {

  private final OutboxWriter outbox;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  @Autowired
  OutboxOtpScrubJobIT(OutboxWriter outbox, JdbcTemplate jdbc, TransactionTemplate tx) {
    this.outbox = outbox;
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @Test
  void aRelayedOtpIsScrubbedWithinSeconds() throws Exception {
    TestTopics.ensure("mw.notify.request.v1");
    String messageId = "checkout-otp:" + UUID.randomUUID() + ":1";
    NotificationRequest sms = new NotificationRequest(1, messageId, "corr-1",
        new NotificationRequest.Recipient("01711000001"), "CHECKOUT_OTP", "en",
        Map.of("otp", "731905", "amount", "25.50", "merchant", "Demo Shop"), List.of("SMS"), "OTP", null);
    tx.executeWithoutResult(s -> outbox.append("mw.notify.request.v1", "01711000001",
        new EventMetadata("corr-1", messageId, messageId, 1), sms));

    Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
    String payload;
    do {
      Thread.sleep(200);
      List<String> relayed = jdbc.queryForList(
          "select payload::text from outbox where payload->>'messageId' = ? and sent_at is not null",
          String.class, messageId);
      payload = relayed.isEmpty() ? "not relayed yet: 731905" : relayed.getFirst();
    } while (payload.contains("731905") && Instant.now().isBefore(deadline));

    assertThat(payload).doesNotContain("731905");
  }
}
