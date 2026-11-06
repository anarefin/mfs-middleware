package mfs.mw.notify.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.notify.MutableClock;
import mfs.mw.notify.TestInfrastructure;
import mfs.mw.notify.client.FakeSmsSender;
import mfs.mw.notify.dto.Channel;
import mfs.mw.notify.dto.NotificationRequest;
import mfs.mw.notify.dto.Priority;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Email over SMTP (spec 10.2) at its seams: a request is accepted, the dispatcher sends it to a real
 * SMTP server (in-process GreenMail) as a multipart/alternative message with the rendered subject,
 * text and HTML parts; what leaves the service is observed in the mail server's inbox and in the
 * {@code mw.notify.status.v1} events written to the outbox.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.notify.consumers.enabled=false",
      "mw.notify.smpp.enabled=false",
      "mw.notify.dispatch.enabled=false",
      "mw.notify.email.enabled=true",
      "mw.notify.email.from=Bits <no-reply@bits.test>"
    })
@Import({TestInfrastructure.class, EmailFlowIT.Fixtures.class})
class EmailFlowIT {

  static final Instant T0 = Instant.parse("2026-11-04T08:00:00Z");

  static final int SMTP_PORT = freePort();
  static final GreenMail MAIL = new GreenMail(new ServerSetup(SMTP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));

  static {
    MAIL.start();
  }

  @DynamicPropertySource
  static void smtp(DynamicPropertyRegistry registry) {
    registry.add("spring.mail.host", () -> "127.0.0.1");
    registry.add("spring.mail.port", () -> SMTP_PORT);
  }

  @AfterAll
  static void stopMail() {
    MAIL.stop();
  }

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

  private final NotificationService notifications;
  private final DispatchService dispatcher;
  private final FakeSmsSender smsc;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json = JsonMapper.builder().build();

  @Autowired
  EmailFlowIT(NotificationService notifications, DispatchService dispatcher, FakeSmsSender smsc, MutableClock clock,
      JdbcTemplate jdbc) {
    this.notifications = notifications;
    this.dispatcher = dispatcher;
    this.smsc = smsc;
    this.clock = clock;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void reset() throws Exception {
    if (!MAIL.isRunning()) {
      MAIL.start();
    }
    MAIL.purgeEmailFromAllMailboxes();
    smsc.reset();
    clock.set(T0);
    jdbc.update("update message set status = 'FAILED' where status in ('QUEUED', 'RETRY')");
    jdbc.update(
        "insert into template (code, channel, locale, version, subject, body, html_body, valid_from) values"
            + " ('TEST_RECEIPT', 'EMAIL', 'en', 1, 'Receipt {{txnId}}',"
            + " 'Paid Tk {{amount}} to {{biller}}. Account {{counterparty}}.',"
            + " '<p>Paid <b>Tk {{amount}}</b> to {{biller}}. Account {{counterparty}}.</p>', '2026-01-01T00:00:00Z')"
            + " on conflict do nothing");
    jdbc.update(
        "insert into template (code, channel, locale, version, body, valid_from) values"
            + " ('TEST_RECEIPT', 'SMS', 'en', 1, 'Paid Tk {{amount}} to {{biller}}.', '2026-01-01T00:00:00Z')"
            + " on conflict do nothing");
  }

  static NotificationRequest request(String id, String msisdn, String email, List<Channel> channels) {
    return new NotificationRequest(
        1, id, null, "corr-" + id, new NotificationRequest.Recipient(msisdn, email, null, null), "TEST_RECEIPT",
        "en", Map.of("amount", "667.00", "biller", "Gas & <Power>", "counterparty", "01811000002", "txnId", "T-1"),
        channels, Priority.TXN, null, null);
  }

  static String id() {
    return "e-" + UUID.randomUUID();
  }

  List<JsonNode> statuses(String messageId, Channel channel) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = 'mw.notify.status.v1' and msg_key = ? order by id",
            String.class, messageId)
        .stream()
        .map(json::readTree)
        .filter(n -> channel.name().equals(n.get("channel").asString()))
        .toList();
  }

  List<String> statusNames(String messageId, Channel channel) {
    return statuses(messageId, channel).stream()
        .map(n -> n.get("status").asString() + (n.get("final").asBoolean() ? "!" : ""))
        .toList();
  }

  @Test
  void anEmailReachesTheMailServerWithRenderedSubjectTextAndHtml() throws Exception {
    String id = id();
    assertThat(notifications.accept(request(id, null, "customer01@example.test", List.of(Channel.EMAIL)))).isTrue();

    dispatcher.dispatchDue();

    MimeMessage[] received = MAIL.getReceivedMessages();
    assertThat(received).hasSize(1);
    MimeMessage mail = received[0];
    assertThat(mail.getSubject()).isEqualTo("Receipt T-1");
    assertThat(mail.getAllRecipients()).extracting(Object::toString).containsExactly("customer01@example.test");
    assertThat(mail.getFrom()).extracting(Object::toString).containsExactly("Bits <no-reply@bits.test>");
    Map<String, String> parts = parts(mail);
    assertThat(parts.get("text/plain")).isEqualTo("Paid Tk 667.00 to Gas & <Power>. Account 0181****002.");
    assertThat(parts.get("text/html"))
        .isEqualTo("<p>Paid <b>Tk 667.00</b> to Gas &amp; &lt;Power&gt;. Account 0181****002.</p>");

    assertThat(statusNames(id, Channel.EMAIL)).containsExactly("SENT!");
    JsonNode sent = statuses(id, Channel.EMAIL).getFirst();
    assertThat(sent.get("providerMessageId").asString()).isEqualTo(mail.getMessageID());
    assertThat(sent.get("templateCode").asString()).isEqualTo("TEST_RECEIPT");
  }

  @Test
  void oneRequestForSmsAndEmailSendsBothAndItsRepeatNeither() throws Exception {
    String id = id();
    NotificationRequest r = request(id, "01711000001", "customer01@example.test", List.of(Channel.SMS, Channel.EMAIL));
    assertThat(notifications.accept(r)).isTrue();
    assertThat(notifications.accept(r)).isFalse();

    dispatcher.dispatchDue();

    assertThat(MAIL.getReceivedMessages()).hasSize(1);
    assertThat(smsc.sent()).hasSize(1);
    assertThat(smsc.sent().getFirst().part().destination()).isEqualTo("01711000001");
    assertThat(statusNames(id, Channel.EMAIL)).containsExactly("SENT!");
    assertThat(statusNames(id, Channel.SMS)).containsExactly("SENT");
  }

  @Test
  void anEmailRequestWithoutAnAddressOrTemplateFails() {
    String noAddress = id();
    String noTemplate = id();
    notifications.accept(request(noAddress, "01711000001", null, List.of(Channel.EMAIL)));
    notifications.accept(new NotificationRequest(1, noTemplate, null, null,
        new NotificationRequest.Recipient(null, "customer01@example.test", null, null), "TXN_NO_SUCH", "en",
        Map.of(), List.of(Channel.EMAIL), Priority.TXN, null, null));

    dispatcher.dispatchDue();

    assertThat(MAIL.getReceivedMessages()).isEmpty();
    assertThat(statusNames(noAddress, Channel.EMAIL)).containsExactly("FAILED!");
    assertThat(statuses(noAddress, Channel.EMAIL).getFirst().get("errorCode").asString()).isEqualTo("MW-VAL-101");
    assertThat(statuses(noTemplate, Channel.EMAIL).getFirst().get("errorCode").asString()).isEqualTo("MW-VAL-102");
  }

  @Test
  void aMailServerThatIsDownIsRetriedAfterTheBackoff() {
    String id = id();
    notifications.accept(request(id, null, "customer01@example.test", List.of(Channel.EMAIL)));
    MAIL.stop();

    dispatcher.dispatchDue();

    assertThat(statusNames(id, Channel.EMAIL)).containsExactly("FAILED");
    assertThat(statuses(id, Channel.EMAIL).getFirst().get("errorCode").asString()).isEqualTo("MW-SYS-104");

    MAIL.start();
    clock.advance(Duration.ofSeconds(1));
    dispatcher.dispatchDue();
    assertThat(MAIL.getReceivedMessages()).isEmpty();

    clock.advance(Duration.ofSeconds(1));
    dispatcher.dispatchDue();
    assertThat(MAIL.getReceivedMessages()).hasSize(1);
    assertThat(statusNames(id, Channel.EMAIL)).containsExactly("FAILED", "SENT!");
  }

  /** content type (without parameters) to decoded text, for every leaf part. */
  static Map<String, String> parts(Part part) throws Exception {
    Map<String, String> out = new java.util.LinkedHashMap<>();
    collect(part, out);
    return out;
  }

  private static void collect(Part part, Map<String, String> out) throws Exception {
    Object content = part.getContent();
    if (content instanceof Multipart mp) {
      for (int i = 0; i < mp.getCount(); i++) {
        collect(mp.getBodyPart(i), out);
      }
    } else {
      String type = part.getContentType().split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
      out.put(type, content.toString());
    }
  }

  static int freePort() {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
