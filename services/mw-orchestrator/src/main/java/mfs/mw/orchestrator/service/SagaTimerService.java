package mfs.mw.orchestrator.service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import mfs.mw.orchestrator.config.OrchestratorProperties;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.repository.SagaTimerRepository;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.InvalidTransitionException;
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
  private final OrchestratorProperties properties;
  private final Clock clock;

  public SagaTimerService(
      SagaTimerRepository timers,
      TransactionRepository transactions,
      SagaEngine engine,
      InquiryService inquiries,
      OrchestratorProperties properties,
      Clock clock) {
    this.timers = timers;
    this.transactions = transactions;
    this.engine = engine;
    this.inquiries = inquiries;
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
        if (reversalExhausted(rec)) {
          engine.fire(rec, new SagaEvent.ReversalFailed(), REVERSAL_UNCONFIRMED);
          continue;
        }
        engine.fire(rec, new SagaEvent.DeadlineExpired(), expiryError(rec.status()));
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
   * REVERSING resends ReverseTransaction every {@code retry-timeout} (spec 7.2 "retry; alert") but
   * not forever: once the core has not confirmed for {@code reversing-timeout} since the
   * transaction entered REVERSING (its {@code updatedAt}; resends do not change it), the reversal
   * goes to MANUAL_REVIEW for operations, the same way a core refusal does.
   */
  private boolean reversalExhausted(TransactionRecord rec) {
    return rec.status() == Status.REVERSING
        && !clock.instant().isBefore(rec.updatedAt().plus(properties.reversingTimeout()));
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
