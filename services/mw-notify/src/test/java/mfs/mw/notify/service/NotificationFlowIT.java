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
import mfs.mw.notify.dto.DeliveryReport;
import mfs.mw.notify.dto.DlrOutcome;
import mfs.mw.notify.dto.NotificationRequest;
import mfs.mw.notify.dto.Priority;
import mfs.mw.notify.exception.SmsSendException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The notification pipeline at its seams (spec 10): a request is accepted (or dropped as a
 * duplicate, or recorded EXPIRED), the dispatcher sends it to the SMSC seam ({@link
 * FakeSmsSender}) in priority order with transient-error retries at 2/10/30 s, and DLRs drive it
 * to a final status. What leaves the service is observed as the {@code mw.notify.status.v1}
 * events it writes to its outbox.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.notify.consumers.enabled=false",
      "mw.notify.smpp.enabled=false",
      "mw.notify.dispatch.enabled=false"
    })
@Import({TestInfrastructure.class, NotificationFlowIT.Fixtures.class})
class NotificationFlowIT {

  static final String TXN_REF = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b";

  static final Instant T0 = Instant.parse("2026-11-04T08:00:00Z");

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
  private final ReceiptService receipts;
  private final FakeSmsSender smsc;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json = JsonMapper.builder().build();

  @Autowired
  NotificationFlowIT(
      NotificationService notifications,
      DispatchService dispatcher,
      ReceiptService receipts,
      FakeSmsSender smsc,
      MutableClock clock,
      JdbcTemplate jdbc) {
    this.notifications = notifications;
    this.dispatcher = dispatcher;
    this.receipts = receipts;
    this.smsc = smsc;
    this.clock = clock;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void reset() {
    smsc.reset();
    clock.set(T0);
    // Messages left by earlier tests must not be dispatched again.
    jdbc.update("update message set status = 'FAILED' where status in ('QUEUED', 'RETRY')");
  }

  static NotificationRequest request(String id, String msisdn, String locale, Priority priority) {
    return new NotificationRequest(
        1, id, null, "corr-" + id, new NotificationRequest.Recipient(msisdn, null, null, null),
        "TXN_P2P_SENT", locale,
        Map.of("amount", "500.00", "counterparty", "01811000002", "fee", "0.00", "txnId", TXN_REF),
        List.of(Channel.SMS), priority, null, null);
  }

  /** mw.notify.status.v1 events written for {@code messageId}, oldest first. */
  List<JsonNode> statuses(String messageId) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = 'mw.notify.status.v1' and msg_key = ? order by id",
            String.class, messageId)
        .stream()
        .map(json::readTree)
        .toList();
  }

  List<String> statusNames(String messageId) {
    return statuses(messageId).stream()
        .map(n -> n.get("status").asString() + (n.get("final").asBoolean() ? "!" : ""))
        .toList();
  }

  static String id() {
    return "m-" + UUID.randomUUID();
  }

  @Test
  void anEnglishSmsIsRenderedWithMaskedParamsSentAsGsm7AndDeliveredByDlr() {
    String id = id();
    notifications.accept(request(id, "01711000001", "en", Priority.TXN));

    dispatcher.dispatchDue();

    assertThat(smsc.sent()).hasSize(1);
    FakeSmsSender.Sent sent = smsc.sent().getFirst();
    assertThat(sent.part().destination()).isEqualTo("01711000001");
    assertThat(sent.part().source()).isEqualTo("upay");
    assertThat(sent.part().dataCoding()).isEqualTo((byte) 0x00);
    assertThat(new String(sent.part().shortMessage(), StandardCharsets.US_ASCII))
        .isEqualTo("You have sent Tk 500.00 to 0181****002. Fee Tk 0.00. TrxID " + TXN_REF);
    assertThat(statusNames(id)).containsExactly("SENT");

    receipts.onReceipt(new DeliveryReport(sent.providerMessageId(), DlrOutcome.DELIVERED));

    assertThat(statusNames(id)).containsExactly("SENT", "DELIVERED!");
    JsonNode last = statuses(id).getLast();
    assertThat(last.get("providerMessageId").asString()).isEqualTo(sent.providerMessageId());
    assertThat(last.get("templateCode").asString()).isEqualTo("TXN_P2P_SENT");
  }

  @Test
  void aBanglaSmsGoesAsUcs2InConcatenatedPartsAndIsDeliveredWhenEveryPartIs() {
    String id = id();
    notifications.accept(request(id, "01711000001", "bn", Priority.TXN));

    dispatcher.dispatchDue();

    List<FakeSmsSender.Sent> parts = smsc.sent();
    assertThat(parts).hasSizeGreaterThan(1);
    assertThat(parts).allSatisfy(p -> {
      assertThat(p.part().dataCoding()).isEqualTo((byte) 0x08);
      assertThat(p.part().udhi()).isTrue();
    });

    receipts.onReceipt(new DeliveryReport(parts.getFirst().providerMessageId(), DlrOutcome.DELIVERED));
    assertThat(statusNames(id)).containsExactly("SENT");

    for (FakeSmsSender.Sent p : parts.subList(1, parts.size())) {
      receipts.onReceipt(new DeliveryReport(p.providerMessageId(), DlrOutcome.DELIVERED));
    }
    assertThat(statusNames(id)).containsExactly("SENT", "DELIVERED!");
  }

  @Test
  void aDuplicateMessageIdIsDropped() {
    String id = id();
    assertThat(notifications.accept(request(id, "01711000001", "en", Priority.TXN))).isTrue();
    assertThat(notifications.accept(request(id, "01711000009", "en", Priority.TXN))).isFalse();

    dispatcher.dispatchDue();

    assertThat(smsc.sent()).singleElement().satisfies(s -> assertThat(s.part().destination()).isEqualTo("01711000001"));
  }

  @Test
  void aMessageAlreadyExpiredOnArrivalIsRecordedExpiredAndNeverSent() {
    String id = id();
    NotificationRequest r = request(id, "01711000001", "en", Priority.OTP);
    notifications.accept(new NotificationRequest(1, id, null, null, r.recipient(), r.templateCode(), "en",
        r.params(), r.channels(), Priority.OTP, null, T0.minusSeconds(1)));

    dispatcher.dispatchDue();

    assertThat(smsc.sent()).isEmpty();
    assertThat(statusNames(id)).containsExactly("EXPIRED!");
  }

  @Test
  void anOtpNotSentWithinItsDefaultTwoMinutesExpires() {
    String id = id();
    smsc.failNext(1, new SmsSendException(true, 0x58, "fake-otp", "throttled"));
    notifications.accept(request(id, "01711000001", "en", Priority.OTP));
    dispatcher.dispatchDue();

    clock.advance(Duration.ofMinutes(3));
    dispatcher.dispatchDue();

    assertThat(smsc.sent()).isEmpty();
    assertThat(statusNames(id)).containsExactly("FAILED", "EXPIRED!");
  }

  @Test
  void transientErrorsAreRetriedAfter2Then10Then30SecondsThenTheMessageFails() {
    String id = id();
    smsc.failNext(4, new SmsSendException(true, 0x58, "fake-txn", "throttled"));
    notifications.accept(request(id, "01711000001", "en", Priority.TXN));

    dispatcher.dispatchDue(); // attempt 1 fails
    clock.advance(Duration.ofMillis(1999));
    dispatcher.dispatchDue(); // not due yet
    assertThat(statusNames(id)).hasSize(1);

    clock.advance(Duration.ofMillis(1));
    dispatcher.dispatchDue(); // attempt 2 at +2 s
    clock.advance(Duration.ofSeconds(9));
    dispatcher.dispatchDue();
    assertThat(statusNames(id)).hasSize(2);
    clock.advance(Duration.ofSeconds(1));
    dispatcher.dispatchDue(); // attempt 3 at +10 s
    clock.advance(Duration.ofSeconds(30));
    dispatcher.dispatchDue(); // attempt 4 at +30 s, the last

    assertThat(statusNames(id)).containsExactly("FAILED", "FAILED", "FAILED", "FAILED!");
    assertThat(statuses(id).getLast().get("errorCode").asString()).isEqualTo("MW-SYS-102");
    assertThat(statuses(id).getLast().get("attempt").asInt()).isEqualTo(4);
    assertThat(smsc.sent()).isEmpty();
  }

  @Test
  void aTransientErrorThenSuccessSendsOnTheRetry() {
    String id = id();
    smsc.failNext(1, new SmsSendException(true, 0x14, "fake-txn", "queue full"));
    notifications.accept(request(id, "01711000001", "en", Priority.TXN));
    dispatcher.dispatchDue();
    clock.advance(Duration.ofSeconds(2));
    dispatcher.dispatchDue();

    assertThat(smsc.sent()).hasSize(1);
    assertThat(statusNames(id)).containsExactly("FAILED", "SENT");
    assertThat(statuses(id).getLast().get("attempt").asInt()).isEqualTo(2);
  }

  @Test
  void aPermanentSmscRejectionFailsAtOnce() {
    String id = id();
    smsc.failNext(1, new SmsSendException(false, 0x0B, "fake-txn", "invalid destination"));
    notifications.accept(request(id, "01711000001", "en", Priority.TXN));
    dispatcher.dispatchDue();

    assertThat(statusNames(id)).containsExactly("FAILED!");
    assertThat(statuses(id).getLast().get("errorCode").asString()).isEqualTo("MW-SYS-101");
  }

  @Test
  void undeliverableAndExpiredDlrsAreFinal() {
    String a = id();
    String b = id();
    notifications.accept(request(a, "01711000001", "en", Priority.TXN));
    notifications.accept(request(b, "01711000003", "en", Priority.TXN));
    dispatcher.dispatchDue();
    List<FakeSmsSender.Sent> sent = smsc.sent();

    receipts.onReceipt(new DeliveryReport(sent.get(0).providerMessageId(), DlrOutcome.UNDELIVERABLE));
    receipts.onReceipt(new DeliveryReport(sent.get(1).providerMessageId(), DlrOutcome.EXPIRED));

    assertThat(statusNames(a)).containsExactly("SENT", "UNDELIVERABLE!");
    assertThat(statuses(a).getLast().get("errorCode").asString()).isEqualTo("MW-SYS-103");
    assertThat(statusNames(b)).containsExactly("SENT", "EXPIRED!");
  }

  @Test
  void aDlrBeforeItsSegmentIsKnownIsAppliedLater() {
    String id = id();
    receipts.onReceipt(new DeliveryReport("fake-early-" + id, DlrOutcome.DELIVERED));
    // Simulates the race: the SMSC answered and sent the DLR before the submit committed.
    notifications.accept(request(id, "01711000001", "en", Priority.TXN));
    dispatcher.dispatchDue();
    String providerId = smsc.sent().getFirst().providerMessageId();
    jdbc.update("update receipt set provider_message_id = ? where provider_message_id = ?", providerId, "fake-early-" + id);

    receipts.applyBuffered();

    assertThat(statusNames(id)).containsExactly("SENT", "DELIVERED!");
  }

  @Test
  void aDuplicateDlrChangesNothing() {
    String id = id();
    notifications.accept(request(id, "01711000001", "en", Priority.TXN));
    dispatcher.dispatchDue();
    String pid = smsc.sent().getFirst().providerMessageId();

    receipts.onReceipt(new DeliveryReport(pid, DlrOutcome.DELIVERED));
    receipts.onReceipt(new DeliveryReport(pid, DlrOutcome.UNDELIVERABLE));

    assertThat(statusNames(id)).containsExactly("SENT", "DELIVERED!");
  }

  @Test
  void higherPrioritiesAreServedFirst() {
    String promo = id();
    String info = id();
    String txn = id();
    String otp = id();
    notifications.accept(request(promo, "01711000004", "en", Priority.PROMO));
    notifications.accept(request(info, "01711000003", "en", Priority.INFO));
    notifications.accept(request(txn, "01711000002", "en", Priority.TXN));
    notifications.accept(request(otp, "01711000001", "en", Priority.OTP));

    dispatcher.dispatchDue();

    assertThat(smsc.sent()).extracting(s -> s.priority())
        .containsExactly(Priority.OTP, Priority.TXN, Priority.INFO, Priority.PROMO);
  }

  @Test
  void aRequestWithoutAnSmsAddressOrUnknownTemplateFails() {
    String noMsisdn = id();
    notifications.accept(new NotificationRequest(1, noMsisdn, null, null,
        new NotificationRequest.Recipient(null, "a@example.com", null, null), "TXN_P2P_SENT", "en",
        Map.of(), List.of(Channel.SMS), Priority.TXN, null, null));
    String unknown = id();
    NotificationRequest r = request(unknown, "01711000001", "en", Priority.TXN);
    notifications.accept(new NotificationRequest(1, unknown, null, null, r.recipient(), "NO_SUCH_TEMPLATE",
        "en", r.params(), r.channels(), Priority.TXN, null, null));

    assertThat(statusNames(noMsisdn)).containsExactly("FAILED!");
    assertThat(statuses(noMsisdn).getFirst().get("errorCode").asString()).isEqualTo("MW-VAL-101");
    assertThat(statusNames(unknown)).containsExactly("FAILED!");
    assertThat(statuses(unknown).getFirst().get("errorCode").asString()).isEqualTo("MW-VAL-102");
  }

  @Test
  void aScheduledMessageWaitsForSendAt() {
    String id = id();
    NotificationRequest r = request(id, "01711000001", "en", Priority.TXN);
    notifications.accept(new NotificationRequest(1, id, null, null, r.recipient(), r.templateCode(), "en",
        r.params(), r.channels(), Priority.TXN, T0.plusSeconds(60), null));

    dispatcher.dispatchDue();
    assertThat(smsc.sent()).isEmpty();
    clock.advance(Duration.ofSeconds(60));
    dispatcher.dispatchDue();
    assertThat(smsc.sent()).hasSize(1);
  }
}
