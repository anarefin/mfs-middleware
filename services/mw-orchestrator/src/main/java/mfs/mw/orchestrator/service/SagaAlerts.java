package mfs.mw.orchestrator.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.service.saga.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Operational alerts of the saga (spec 6.3, 7.2, 21): each one is a stable {@code event} log line
 * (WARN, or ERROR for a Sev-1) and a Micrometer meter for the alert rules.
 *
 * <ul>
 *   <li>{@code txn.commit.unacknowledged} / {@code txn.release.unacknowledged}: COMMITTING or
 *       RELEASING still unanswered {@link #ACK_ALERT_AFTER} after the state was entered (counted
 *       once, on the resend that crosses it);
 *   <li>{@code txn.commit.failed} (Sev-1, ERROR) / {@code txn.release.failed} (ERROR): the core
 *       refused a commit or release;
 *   <li>{@code txn.reversal.retry_exhausted}: REVERSING stopped resending without an answer;
 *   <li>{@code txn.manual_review.ageing}: the daily check of the MANUAL_REVIEW queue (count,
 *       oldest age; gauge {@code mw_manual_review_oldest_age}).
 * </ul>
 */
@Component
public class SagaAlerts {

  private static final Logger log = LoggerFactory.getLogger(SagaAlerts.class);

  /** Spec 7.2: COMMITTING / RELEASING "retry until acknowledged; alert after 60 s". */
  public static final Duration ACK_ALERT_AFTER = Duration.ofSeconds(60);

  private final Counter commitUnacknowledged;
  private final Counter releaseUnacknowledged;
  private final Counter commitFailed;
  private final Counter releaseFailed;
  private final Counter reversalExhausted;
  private final AtomicLong oldestManualReviewSeconds = new AtomicLong();

  public SagaAlerts(MeterRegistry registry) {
    commitUnacknowledged = counter(registry, "mw.txn.commit.unacknowledged", "COMMITTING unanswered after 60 s");
    releaseUnacknowledged = counter(registry, "mw.txn.release.unacknowledged", "RELEASING unanswered after 60 s");
    commitFailed = counter(registry, "mw.txn.commit.failed", "Commits the core refused (Sev-1)");
    releaseFailed = counter(registry, "mw.txn.release.failed", "Releases the core refused");
    reversalExhausted =
        counter(registry, "mw.txn.reversal.retry.exhausted", "Reversals that stopped resending unanswered");
    Gauge.builder("mw.manual.review.oldest.age", oldestManualReviewSeconds, AtomicLong::get)
        .description("Age in seconds of the oldest MANUAL_REVIEW transaction at the last daily check")
        .baseUnit("seconds")
        .register(registry);
  }

  private static Counter counter(MeterRegistry registry, String name, String description) {
    return Counter.builder(name).description(description).register(registry);
  }

  /**
   * A COMMITTING or RELEASING deadline passed again. Alerts when {@link #ACK_ALERT_AFTER} since
   * {@code enteredAt} fell after the previous attempt and by {@code now}, so once per transaction.
   */
  public void stillUnacknowledged(Status status, UUID txnId, Instant enteredAt, Instant previousAttempt, Instant now) {
    Instant threshold = enteredAt.plus(ACK_ALERT_AFTER);
    if (!previousAttempt.isBefore(threshold) || now.isBefore(threshold)) {
      return;
    }
    boolean commit = status == Status.COMMITTING;
    (commit ? commitUnacknowledged : releaseUnacknowledged).increment();
    log.atWarn()
        .addKeyValue("event", commit ? "txn.commit.unacknowledged" : "txn.release.unacknowledged")
        .addKeyValue("txnId", txnId.toString())
        .addKeyValue("status", status.name())
        .addKeyValue("waitingSeconds", Duration.between(enteredAt, now).toSeconds())
        .log(commit
            ? "the core has not acknowledged the commit for 60 s; still resending"
            : "the core has not acknowledged the release for 60 s; still resending");
  }

  /** The core refused the commit (CommitFailed / CreditRejected) or release (ReleaseFailed). */
  public void refused(Status from, UUID txnId, ErrorInfo error) {
    boolean commit = from == Status.COMMITTING;
    (commit ? commitFailed : releaseFailed).increment();
    log.atError()
        .addKeyValue("event", commit ? "txn.commit.failed" : "txn.release.failed")
        .addKeyValue("severity", commit ? "SEV-1" : "SEV-2")
        .addKeyValue("txnId", txnId.toString())
        .addKeyValue("errorCode", error == null ? null : error.code())
        .log(commit
            ? "the core refused the commit after the partner was paid; manual review"
            : "the core refused the release; manual review");
  }

  /** REVERSING stopped resending ReverseTransaction without an answer from the core. */
  public void reversalRetriesExhausted(UUID txnId, Instant began, Instant now) {
    reversalExhausted.increment();
    log.atWarn()
        .addKeyValue("event", "txn.reversal.retry_exhausted")
        .addKeyValue("txnId", txnId.toString())
        .addKeyValue("waitingSeconds", Duration.between(began, now).toSeconds())
        .log("the core has not confirmed the reversal; resends stopped, the core will be asked");
  }

  /** The daily MANUAL_REVIEW check: how many wait and since when the oldest does. */
  public void manualReviewAgeing(long count, Optional<Instant> oldestSince, Instant now) {
    long oldest = oldestSince.map(t -> Math.max(0, Duration.between(t, now).toSeconds())).orElse(0L);
    oldestManualReviewSeconds.set(count == 0 ? 0 : oldest);
    if (count == 0) {
      log.atInfo().addKeyValue("event", "txn.manual_review.ageing").addKeyValue("count", 0).log("no transaction in manual review");
      return;
    }
    log.atWarn()
        .addKeyValue("event", "txn.manual_review.ageing")
        .addKeyValue("count", count)
        .addKeyValue("oldestAgeSeconds", oldest)
        .log("transactions are waiting in manual review");
  }
}
