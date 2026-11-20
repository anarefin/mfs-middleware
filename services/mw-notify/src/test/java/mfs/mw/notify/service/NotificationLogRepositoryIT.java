package mfs.mw.notify.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import mfs.mw.notify.TestInfrastructure;
import mfs.mw.notify.dto.NotificationLogQuery;
import mfs.mw.notify.dto.Priority;
import mfs.mw.notify.repository.NotificationLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The notification log query filters the stored rows (ticket 111, NOT-08). Masking is the DTO's. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.notify.consumers.enabled=false",
      "mw.notify.smpp.enabled=false",
      "mw.notify.dispatch.enabled=false",
      "mw.notify.campaign.enabled=false"
    })
@Import({TestInfrastructure.class, NotificationFlowIT.Fixtures.class})
class NotificationLogRepositoryIT {

  private final NotificationService notifications;
  private final NotificationLogRepository logs;

  @Autowired
  NotificationLogRepositoryIT(NotificationService notifications, NotificationLogRepository logs) {
    this.notifications = notifications;
    this.logs = logs;
  }

  @Test
  void searchFiltersByMsisdnChannelAndWindow() {
    notifications.accept(NotificationFlowIT.request("log-otp", "01711000041", "en", Priority.OTP));
    notifications.accept(NotificationFlowIT.request("log-txn", "01711000042", "en", Priority.TXN));
    Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
    Instant to = Instant.now().plus(1, ChronoUnit.HOURS);

    var matched = logs.search(new NotificationLogQuery("01711000041", null, "SMS", null, from, to, 50, 0));
    assertThat(matched).singleElement().satisfies(row -> {
      assertThat(row.messageId()).isEqualTo("log-otp");
      assertThat(row.priority()).isEqualTo("OTP");
      assertThat(row.body()).isNotEqualTo("****");
      assertThat(row.msisdn()).isEqualTo("01711000041");
    });

    assertThat(logs.search(new NotificationLogQuery("01711000041", null, "EMAIL", null, from, to, 50, 0))).isEmpty();
    assertThat(logs.search(new NotificationLogQuery("01711000041", null, null, null, to, null, 50, 0))).isEmpty();
  }
}
