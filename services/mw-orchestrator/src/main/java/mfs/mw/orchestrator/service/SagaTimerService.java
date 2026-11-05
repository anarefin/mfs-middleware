package mfs.mw.orchestrator.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import mfs.mw.orchestrator.config.OrchestratorProperties;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.repository.SagaTimerRepository;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.exception.InvalidTransitionException;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import mfs.mw.orchestrator.service.saga.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deadline sweep (spec 7.1): finds the timers that have passed, with {@code FOR UPDATE SKIP
 * LOCKED} so a second orchestrator instance never handles the same one, and fires {@code
 * DeadlineExpired} on their transactions. The meaning of an expiry comes from the state machine
 * (VALIDATING to FAILED, POSTING to UNKNOWN, ...); an UNKNOWN transaction's inquiry slot goes to
 * {@link InquiryService}. Run by {@code scheduler/SagaTimerJob}.
 */
@Service
public class SagaTimerService {

  private static final Logger log = LoggerFactory.getLogger(SagaTimerService.class);

  private final SagaTimerRepository timers;
  private final TransactionRepository transactions;
  private final SagaEngine engine;
  private final InquiryService inquiries;
  private final SagaAlerts alerts;
  private final OrchestratorProperties properties;
  private final Clock clock;

  public SagaTimerService(
      SagaTimerRepository timers,
      TransactionRepository transactions,
      SagaEngine engine,
      InquiryService inquiries,
      SagaAlerts alerts,
      OrchestratorProperties properties,
      Clock clock) {
    this.timers = timers;
    this.transactions = transactions;
    this.engine = engine;
    this.inquiries = inquiries;
    this.alerts = alerts;
    this.properties = properties;
    this.clock = clock;
  }

  /** One sweep, one database transaction. Returns the number of timers handled. */
  @Transactional
  public int sweep() {
    List<SagaTimerRepository.Due> due = timers.lockDue(clock.instant(), properties.timer().batchSize());
    for (SagaTimerRepository.Due timer : due) {
      timers.delete(timer.txnId(), timer.step());
      Optional<TransactionRecord> found = transactions.findForUpdate(timer.txnId());
      if (found.isEmpty()) {
        continue;
      }
      TransactionRecord rec = found.get();
      try {
        if (rec.status() == Status.UNKNOWN && SagaEngine.INQUIRY_TIMER.equals(timer.step())) {
          inquiries.due(rec, timer.attempt()); // spec 7.4: inquire, or MANUAL_REVIEW at the cut-off
          continue;
        }
        if (rec.status() == Status.REVERSING) {
          reversingDeadline(rec);
          continue;
        }
        engine.fire(rec, new SagaEvent.DeadlineExpired(), expiryError(rec.status()));
        if (rec.status() == Status.COMMITTING || rec.status() == Status.RELEASING) {
          // the previous attempt armed this deadline at dueAt - retry-timeout (spec 7.2 alert)
          alerts.stillUnacknowledged(
              rec.status(), rec.txnId(),
              transactions.enteredAt(rec.txnId(), rec.status()).orElse(rec.updatedAt()),
              timer.dueAt().minus(properties.retryTimeout()), clock.instant());
        }
      } catch (InvalidTransitionException e) {
        log.atInfo()
            .addKeyValue("event", "saga.timer.ignored")
            .addKeyValue("txnId", rec.txnId().toString())
            .addKeyValue("status", rec.status().name())
            .log("deadline passed for a transaction that no longer waits; ignored");
      }
    }
    return due.size();
  }

  /**
   * A REVERSING deadline (spec 7.2 "retry; alert"), measured from when the transaction entered
   * REVERSING. A ReverseTransaction resent late can still be applied by the core until it
   * expires, so the transaction is never handed to an operator while one is live:
   *
   * <ol>
   *   <li>before {@code reversing-timeout}: resend (same commandId);
   *   <li>then: stop resending ({@code txn.reversal.retry_exhausted}) and wait until every
   *       command sent has expired ({@code + command-ttl}; the core drops an expired command);
   *   <li>then, for one more {@code command-ttl}: ask the core every {@code retry-timeout}
   *       whether the original was reversed (QueryTransaction); REVERSED ends it REVERSED, any
   *       other answer MANUAL_REVIEW ({@code CoreEventService});
   *   <li>no answer by then: MANUAL_REVIEW {@code MW-TMO-020}. A TransactionReversed that still
   *       arrives later moves it to REVERSED (the machine accepts it in MANUAL_REVIEW).
   * </ol>
   */
  private void reversingDeadline(TransactionRecord rec) {
    Instant now = clock.instant();
    Instant began = transactions.enteredAt(rec.txnId(), Status.REVERSING).orElse(rec.updatedAt());
    Instant resendsEnd = began.plus(properties.reversingTimeout());
    Instant lastExpiry = resendsEnd.plus(properties.commandTtl());
    Instant queriesEnd = lastExpiry.plus(properties.commandTtl());
    if (now.isBefore(resendsEnd)) {
      engine.fire(rec, new SagaEvent.DeadlineExpired(), null);
    } else if (now.isBefore(lastExpiry)) {
      engine.fire(rec, new SagaEvent.ReversalResendsStopped(), null);
      timers.arm(rec.txnId(), Status.REVERSING.name(), lastExpiry, true);
      alerts.reversalRetriesExhausted(rec.txnId(), began, now);
    } else if (now.isBefore(queriesEnd)) {
      engine.fire(rec, new SagaEvent.ReversalUnconfirmed(), null);
    } else {
      engine.fire(rec, new SagaEvent.ReversalFailed(), REVERSAL_UNCONFIRMED);
    }
  }

  private static final ErrorInfo REVERSAL_UNCONFIRMED =
      new ErrorInfo(
          ErrorCodes.TMO_CORE_REVERSE,
          "The core did not confirm the reversal in time; operations will review it",
          null,
          false);

  /** The error to report when the expiry ends the transaction (only VALIDATING does: FAILED). */
  private static ErrorInfo expiryError(Status status) {
    if (status == Status.VALIDATING) {
      return new ErrorInfo(
          ErrorCodes.TMO_CORE_VALIDATE,
          "The core did not answer in time; nothing was moved",
          null,
          true);
    }
    return null;
  }
}
