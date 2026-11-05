package mfs.mw.orchestrator.service;

import java.time.Clock;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.Status;
import org.springframework.stereotype.Service;

/**
 * The daily MANUAL_REVIEW ageing check (spec 7.2, 21): counts the transactions waiting for an
 * operator and the age of the oldest (since it entered MANUAL_REVIEW) and reports them through
 * {@link SagaAlerts}. Run by {@code scheduler/ManualReviewAgeingJob}.
 */
@Service
public class ManualReviewAgeingService {

  private final TransactionRepository transactions;
  private final SagaAlerts alerts;
  private final Clock clock;

  public ManualReviewAgeingService(TransactionRepository transactions, SagaAlerts alerts, Clock clock) {
    this.transactions = transactions;
    this.alerts = alerts;
    this.clock = clock;
  }

  public void check() {
    alerts.manualReviewAgeing(
        transactions.countByStatus(Status.MANUAL_REVIEW),
        transactions.oldestUpdateInStatus(Status.MANUAL_REVIEW),
        clock.instant());
  }
}
