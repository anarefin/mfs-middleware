package mfs.mw.notify.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.notify.MutableClock;
import mfs.mw.notify.TestInfrastructure;
import mfs.mw.notify.client.FakeSmsSender;
import mfs.mw.notify.dto.Channel;
import mfs.mw.notify.dto.ConfigChangedEvent;
import mfs.mw.notify.dto.NotificationRequest;
import mfs.mw.notify.dto.Priority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A template version published by the admin console (ticket 39, spec 10.3, acceptance criterion
 * 2): stored as the next ACTIVE version, it is what the next notification is rendered with from
 * its {@code validFrom} on. A test-only template code keeps the seeded templates other tests read
 * untouched in the shared database.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.notify.consumers.enabled=false",
      "mw.notify.smpp.enabled=false",
      "mw.notify.dispatch.enabled=false"
    })
@Import({TestInfrastructure.class, TemplatePublisherIT.Fixtures.class})
class TemplatePublisherIT {

  static final Instant T0 = Instant.parse("2026-11-05T08:00:00Z");

  @TestConfiguration(proxyBeanMethods = false)
  static class Fixtures {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(T0);
    }

    @Bean
    @Primary
    FakeSmsSender fakeSmsSender() {
      return new FakeSmsSender();
    }
  }

  private final TemplatePublisher publisher;
  private final NotificationService notifications;
  private final DispatchService dispatcher;
  private final FakeSmsSender smsc;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;

  @Autowired
  TemplatePublisherIT(
      TemplatePublisher publisher, NotificationService notifications, DispatchService dispatcher, FakeSmsSender smsc,
      MutableClock clock, JdbcTemplate jdbc) {
    this.publisher = publisher;
    this.notifications = notifications;
    this.dispatcher = dispatcher;
    this.smsc = smsc;
    this.clock = clock;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void reset() {
    smsc.reset();
    clock.set(T0);
    jdbc.update("update message set status = 'FAILED' where status in ('QUEUED', 'RETRY')");
  }

  static ConfigChangedEvent published(String code, long version, String body, Instant validFrom) {
    return new ConfigChangedEvent(
        1, "template/" + code + "/SMS/en", ConfigChangedEvent.ConfigType.TEMPLATE, ConfigChangedEvent.Change.UPSERT,
        version,
        Map.of("code", code, "channel", "SMS", "locale", "en", "version", version, "body", body, "senderId", "upay",
            "validFrom", validFrom.toString()),
        T0, "ops-maker01", "ops-checker01", "corr-it");
  }

  /** Sends one SMS of {@code code} and returns its text. */
  private String nextSms(String code) {
    smsc.reset();
    notifications.accept(
        new NotificationRequest(
            1, "m-" + UUID.randomUUID(), null, "corr-it", new NotificationRequest.Recipient("01711000001", null, null, null),
            code, "en", Map.of("amount", "10.00"), List.of(Channel.SMS), Priority.TXN, null, null));
    dispatcher.dispatchDue();
    assertThat(smsc.sent()).hasSize(1);
    return new String(smsc.sent().getFirst().part().shortMessage(), StandardCharsets.US_ASCII);
  }

  private int rows(String code) {
    Integer n = jdbc.queryForObject("select count(*) from template where code = ?", Integer.class, code);
    return n == null ? 0 : n;
  }

  @Test
  void theNextNotificationUsesTheApprovedTemplateFromItsValidFrom() {
    String code = "IT_TPL_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    publisher.onConfigChanged(published(code, 1, "First Tk {{amount}}", T0.minusSeconds(60)));
    assertThat(nextSms(code)).isEqualTo("First Tk 10.00");

    publisher.onConfigChanged(published(code, 2, "Second Tk {{amount}}", T0.plus(Duration.ofHours(1))));
    assertThat(nextSms(code)).as("not before its validFrom").isEqualTo("First Tk 10.00");

    clock.advance(Duration.ofHours(1));
    assertThat(nextSms(code)).isEqualTo("Second Tk 10.00");
  }

  @Test
  void aRedeliveredOrOlderVersionIsStoredOnceAndAnUnrenderableOneIsDropped() {
    String code = "IT_TPL_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    publisher.onConfigChanged(published(code, 2, "Two {{amount}}", T0));
    publisher.onConfigChanged(published(code, 2, "Two {{amount}}", T0));
    publisher.onConfigChanged(published(code, 1, "One {{amount}}", T0));
    assertThat(rows(code)).isEqualTo(1);

    publisher.onConfigChanged(published(code, 3, "{{#vip}}Three{{/vip}}", T0));
    assertThat(rows(code)).isEqualTo(1);
    assertThat(nextSms(code)).isEqualTo("Two 10.00");
  }
}
