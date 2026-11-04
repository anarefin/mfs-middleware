package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.service.saga.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The operational alerts of spec 6.3 / 7.2 (S4): a stable {@code event} log line and a Micrometer
 * meter per alert, raised once per occurrence.
 */
class SagaAlertsTest {

  private static final Instant T0 = Instant.parse("2026-11-03T08:00:00Z");
  private static final UUID TXN = UUID.fromString("0b9d6c1e-4f8a-4d2b-9c3e-1a2b3c4d5e6f");

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final SagaAlerts alerts = new SagaAlerts(registry);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(SagaAlerts.class);

  @BeforeEach
  void capture() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void release() {
    logger.detachAppender(logs);
  }

  private double count(String name) {
    var c = registry.find(name).counter();
    return c == null ? 0 : c.count();
  }

  private ILoggingEvent only(String event) {
    var matching = logs.list.stream().filter(e -> event.equals(kv(e).get("event"))).toList();
    assertThat(matching).as(event).hasSize(1);
    return matching.get(0);
  }

  private static Map<String, String> kv(ILoggingEvent e) {
    return e.getKeyValuePairs() == null
        ? Map.of()
        : e.getKeyValuePairs().stream().collect(Collectors.toMap(p -> p.key, p -> String.valueOf(p.value), (a, b) -> b));
  }

  // --- COMMITTING / RELEASING not acknowledged after 60 s ------------------------------------

  @Test
  void aCommitStillUnacknowledgedWhenSixtySecondsPassAlertsOnce() {
    // resends at +5 s ... +55 s say nothing; the one that crosses +60 s alerts; later ones do not
    for (int s = 5; s <= 55; s += 5) {
      alerts.stillUnacknowledged(Status.COMMITTING, TXN, T0, T0.plusSeconds(s - 5), T0.plusSeconds(s));
    }
    assertThat(count("mw.txn.commit.unacknowledged")).isZero();

    alerts.stillUnacknowledged(Status.COMMITTING, TXN, T0, T0.plusSeconds(55), T0.plusSeconds(61));
    alerts.stillUnacknowledged(Status.COMMITTING, TXN, T0, T0.plusSeconds(61), T0.plusSeconds(66));

    assertThat(count("mw.txn.commit.unacknowledged")).isEqualTo(1);
    ILoggingEvent e = only("txn.commit.unacknowledged");
    assertThat(e.getLevel()).isEqualTo(Level.WARN);
    assertThat(kv(e)).containsEntry("txnId", TXN.toString()).containsEntry("waitingSeconds", "61");
  }

  @Test
  void aReleaseStillUnacknowledgedAfterSixtySecondsHasItsOwnAlert() {
    alerts.stillUnacknowledged(Status.RELEASING, TXN, T0, T0.plusSeconds(58), T0.plusSeconds(63));

    assertThat(count("mw.txn.release.unacknowledged")).isEqualTo(1);
    assertThat(count("mw.txn.commit.unacknowledged")).isZero();
    assertThat(only("txn.release.unacknowledged").getLevel()).isEqualTo(Level.WARN);
  }

  // --- CommitFailed / ReleaseFailed ----------------------------------------------------------

  @Test
  void aCommitRefusedByTheCoreIsASevOneError() {
    alerts.refused(Status.COMMITTING, TXN, new ErrorInfo("MW-CORE-HOLD_NOT_FOUND", "gone", null, false));

    assertThat(count("mw.txn.commit.failed")).isEqualTo(1);
    ILoggingEvent e = only("txn.commit.failed");
    assertThat(e.getLevel()).isEqualTo(Level.ERROR);
    assertThat(kv(e)).containsEntry("severity", "SEV-1").containsEntry("errorCode", "MW-CORE-HOLD_NOT_FOUND");
  }

  @Test
  void aReleaseRefusedByTheCoreIsAnError() {
    alerts.refused(Status.RELEASING, TXN, new ErrorInfo("MW-CORE-HOLD_COMMITTED", "committed", null, false));

    assertThat(count("mw.txn.release.failed")).isEqualTo(1);
    assertThat(only("txn.release.failed").getLevel()).isEqualTo(Level.ERROR);
  }

  // --- REVERSING retry exhaustion ------------------------------------------------------------

  @Test
  void reversalRetryExhaustionIsAlerted() {
    alerts.reversalRetriesExhausted(TXN, T0, T0.plusSeconds(62));

    assertThat(count("mw.txn.reversal.retry.exhausted")).isEqualTo(1);
    ILoggingEvent e = only("txn.reversal.retry_exhausted");
    assertThat(e.getLevel()).isEqualTo(Level.WARN);
    assertThat(kv(e)).containsEntry("waitingSeconds", "62");
  }

  // --- MANUAL_REVIEW ageing ------------------------------------------------------------------

  @Test
  void theDailyAgeingCheckReportsCountAndOldestAge() {
    alerts.manualReviewAgeing(3, Optional.of(T0), T0.plus(Duration.ofHours(30)));

    ILoggingEvent e = only("txn.manual_review.ageing");
    assertThat(e.getLevel()).isEqualTo(Level.WARN);
    assertThat(kv(e)).containsEntry("count", "3").containsEntry("oldestAgeSeconds", "108000");
    assertThat(registry.find("mw.manual.review.oldest.age").gauge().value()).isEqualTo(108_000);
  }

  @Test
  void anEmptyManualReviewQueueRaisesNoAlert() {
    alerts.manualReviewAgeing(0, Optional.empty(), T0);

    assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.WARN)).isEmpty();
    assertThat(registry.find("mw.manual.review.oldest.age").gauge().value()).isZero();
  }
}
