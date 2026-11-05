package mfs.mw.notify.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import mfs.mw.notify.MutableClock;
import mfs.mw.notify.config.SmppProperties;
import mfs.mw.notify.dto.DeliveryReport;
import mfs.mw.notify.dto.DlrOutcome;
import mfs.mw.notify.dto.Priority;
import mfs.mw.notify.dto.SmsPart;
import mfs.mw.notify.dto.SubmitResult;
import mfs.mw.notify.exception.SmsSendException;
import org.jsmpp.SMPPConstant;
import org.jsmpp.util.DeliveryReceiptState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The SMPP client against the in-test SMSC (spec 10.2, 9.2.1, 10.4): submit and DLR, transient
 * and permanent refusals, no SMSC at all, reconnect back-off, round-robin over a pool.
 */
class SmppSmsSenderTest {

  private static final Instant T0 = Instant.parse("2026-11-04T08:00:00Z");
  private static final String MSISDN = "01711000001";

  private final MutableClock clock = new MutableClock(T0);
  private final List<DeliveryReport> reports = new CopyOnWriteArrayList<>();
  private SmscStub smsc;
  private SmppSmsSender sender;

  @AfterEach
  void tearDown() throws IOException {
    if (sender != null) {
      sender.close();
    }
    if (smsc != null) {
      smsc.close();
    }
  }

  private static SmppProperties props(int port, Map<Priority, Integer> binds) {
    return new SmppProperties(true, "127.0.0.1", port, "mwnotify", "fakepw", "", binds,
        Duration.ofSeconds(30), Duration.ofMillis(1000), Duration.ofSeconds(1), Duration.ofSeconds(4));
  }

  private static SmsPart part(String destination) {
    return new SmsPart(destination, "upay", (byte) 0, false, "Your OTP is 123456".getBytes(StandardCharsets.US_ASCII));
  }

  private static void await(BooleanSupplier done) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!done.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
  }

  private void withStub(Map<Priority, Integer> binds, DeliveryReportHandler handler) throws IOException {
    smsc = new SmscStub();
    sender = new SmppSmsSender(props(smsc.port(), binds), handler, clock);
  }

  @Test
  void aSubmitGoesOutOnTheBindWithTheSmscMessageIdAndItsDeliveryReceiptReachesTheHandler() throws Exception {
    withStub(Map.of(), reports::add);

    SubmitResult result = sender.submit(Priority.OTP, part(MSISDN));

    assertThat(result.bind()).isEqualTo("otp-1");
    assertThat(smsc.receivedFor(MSISDN)).singleElement()
        .satisfies(r -> {
          assertThat(r.messageId()).isEqualTo(result.providerMessageId());
          assertThat(r.source()).isEqualTo("upay");
          assertThat(SmscStub.text(r)).isEqualTo("Your OTP is 123456");
        });
    await(() -> !reports.isEmpty());
    assertThat(reports).containsExactly(new DeliveryReport(result.providerMessageId(), DlrOutcome.DELIVERED));
  }

  @Test
  void throttlingIsTransientWithItsCommandStatusAndTheBind() throws Exception {
    withStub(Map.of(), reports::add);
    smsc.throttle(MSISDN, 1);

    SmsSendException e = catchThrowableOfType(SmsSendException.class, () -> sender.submit(Priority.TXN, part(MSISDN)));

    assertThat(e.isTransient()).isTrue();
    assertThat(e.smppStatus()).isEqualTo(SMPPConstant.STAT_ESME_RTHROTTLED);
    assertThat(e.bind()).isEqualTo("txn-1");
    assertThat(sender.submit(Priority.TXN, part(MSISDN)).bind()).isEqualTo("txn-1");
  }

  @Test
  void anInvalidDestinationIsPermanent() throws Exception {
    withStub(Map.of(), reports::add);
    smsc.refuse(MSISDN, 1, SMPPConstant.STAT_ESME_RINVDSTADR);

    SmsSendException e = catchThrowableOfType(SmsSendException.class, () -> sender.submit(Priority.OTP, part(MSISDN)));

    assertThat(e.isTransient()).isFalse();
    assertThat(e.smppStatus()).isEqualTo(SMPPConstant.STAT_ESME_RINVDSTADR);
    assertThat(e.getMessage()).contains("0xb");
  }

  @Test
  void aFullQueueAndASystemErrorAreTransient() throws Exception {
    withStub(Map.of(), reports::add);
    smsc.refuse(MSISDN, 1, SMPPConstant.STAT_ESME_RMSGQFUL);
    smsc.refuse("01811000002", 1, SMPPConstant.STAT_ESME_RSYSERR);

    assertThat(catchThrowableOfType(SmsSendException.class, () -> sender.submit(Priority.OTP, part(MSISDN)))
        .isTransient()).isTrue();
    assertThat(catchThrowableOfType(SmsSendException.class, () -> sender.submit(Priority.OTP, part("01811000002")))
        .isTransient()).isTrue();
  }

  @Test
  void eachSubmitTakesTheNextBindOfThePriorityPool() throws Exception {
    withStub(Map.of(Priority.OTP, 2), reports::add);

    String first = sender.submit(Priority.OTP, part(MSISDN)).bind();
    String second = sender.submit(Priority.OTP, part(MSISDN)).bind();
    String third = sender.submit(Priority.OTP, part(MSISDN)).bind();

    assertThat(List.of(first, second, third)).containsExactly("otp-1", "otp-2", "otp-1");
  }

  @Test
  void maintainBindsEveryPoolSoAllPoolsReportBound() throws Exception {
    withStub(Map.of(), reports::add);
    assertThat(sender.allPoolsBound()).isFalse();

    sender.maintain();

    assertThat(sender.allPoolsBound()).isTrue();
    await(() -> smsc.bindCount() == Priority.values().length);
    assertThat(smsc.bindCount()).isEqualTo(Priority.values().length);

    sender.close();
    assertThat(sender.allPoolsBound()).isFalse();
  }

  @Test
  void aReceiptTheHandlerCannotStoreIsRefusedAndTheSenderCarriesOn() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    withStub(Map.of(), report -> {
      calls.incrementAndGet();
      throw new IllegalStateException("database down");
    });

    sender.submit(Priority.OTP, part(MSISDN));
    await(() -> calls.get() > 0);

    assertThat(calls.get()).isEqualTo(1);
    assertThat(sender.submit(Priority.OTP, part(MSISDN)).bind()).isEqualTo("otp-1");
  }

  @Test
  void withoutAnSmscASubmitIsTransientWithNoBindAndReconnectsBackOffExponentially() throws Exception {
    AtomicInteger connects = new AtomicInteger();
    try (ServerSocket refusing = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      Thread.ofVirtual().start(() -> {
        while (!refusing.isClosed()) {
          try {
            Socket s = refusing.accept();
            connects.incrementAndGet();
            s.close();
          } catch (IOException e) {
            return;
          }
        }
      });
      sender = new SmppSmsSender(props(refusing.getLocalPort(), Map.of()), reports::add, clock);

      SmsSendException e = catchThrowableOfType(SmsSendException.class, () -> sender.submit(Priority.OTP, part(MSISDN)));
      assertThat(e.isTransient()).isTrue();
      assertThat(e.bind()).isNull();
      assertThat(e.getMessage()).contains("no SMPP bind available for OTP");
      await(() -> connects.get() == 1);

      // within the first back-off (1 s) nobody connects
      catchThrowableOfType(SmsSendException.class, () -> sender.submit(Priority.OTP, part(MSISDN)));
      assertThat(connects.get()).isEqualTo(1);

      clock.advance(Duration.ofSeconds(1));
      catchThrowableOfType(SmsSendException.class, () -> sender.submit(Priority.OTP, part(MSISDN)));
      await(() -> connects.get() == 2);
      assertThat(connects.get()).isEqualTo(2);

      // the back-off doubled to 2 s
      clock.advance(Duration.ofSeconds(1));
      catchThrowableOfType(SmsSendException.class, () -> sender.submit(Priority.OTP, part(MSISDN)));
      assertThat(connects.get()).isEqualTo(2);
      clock.advance(Duration.ofSeconds(1));
      catchThrowableOfType(SmsSendException.class, () -> sender.submit(Priority.OTP, part(MSISDN)));
      await(() -> connects.get() == 3);
      assertThat(connects.get()).isEqualTo(3);
      assertThat(sender.allPoolsBound()).isFalse();
    }
  }

  @Test
  void onlyFinalReceiptStatesBecomeAnOutcome() {
    assertThat(SmppSmsSender.outcome(DeliveryReceiptState.DELIVRD)).isEqualTo(DlrOutcome.DELIVERED);
    assertThat(SmppSmsSender.outcome(DeliveryReceiptState.EXPIRED)).isEqualTo(DlrOutcome.EXPIRED);
    assertThat(SmppSmsSender.outcome(DeliveryReceiptState.UNDELIV)).isEqualTo(DlrOutcome.UNDELIVERABLE);
    assertThat(SmppSmsSender.outcome(DeliveryReceiptState.REJECTD)).isEqualTo(DlrOutcome.UNDELIVERABLE);
    assertThat(SmppSmsSender.outcome(DeliveryReceiptState.DELETED)).isEqualTo(DlrOutcome.UNDELIVERABLE);
    assertThat(SmppSmsSender.outcome(DeliveryReceiptState.ENROUTE)).isNull();
    assertThat(SmppSmsSender.outcome(DeliveryReceiptState.ACCEPTD)).isNull();
    assertThat(SmppSmsSender.outcome(DeliveryReceiptState.UNKNOWN)).isNull();
    assertThat(SmppSmsSender.outcome(null)).isNull();
  }

  @Test
  void theDisabledSenderFailsEverySendAsTransient() {
    SmsSendException e = catchThrowableOfType(SmsSendException.class,
        () -> new DisabledSmsSender().submit(Priority.OTP, part(MSISDN)));

    assertThat(e.isTransient()).isTrue();
    assertThat(e.bind()).isNull();
  }
}
