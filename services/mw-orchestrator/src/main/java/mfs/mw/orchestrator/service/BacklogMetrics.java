package mfs.mw.orchestrator.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.Status;
import org.springframework.stereotype.Component;

/**
 * The backlog gauges of spec 21: {@code mw_txn_unknown_open} (transactions waiting for a status
 * inquiry, 7.4) and {@code mw_manual_review_open} (transactions an operator must resolve). Read
 * from {@code txn.transaction} on every scrape (partial index on these statuses); every instance
 * reports the same, shared number.
 */
@Component
public class BacklogMetrics {

  public BacklogMetrics(MeterRegistry registry, TransactionRepository transactions) {
    Gauge.builder("mw.txn.unknown.open", transactions, t -> t.countByStatus(Status.UNKNOWN))
        .description("Transactions in UNKNOWN, waiting for a status inquiry")
        .register(registry);
    Gauge.builder("mw.manual.review.open", transactions, t -> t.countByStatus(Status.MANUAL_REVIEW))
        .description("Transactions in MANUAL_REVIEW, waiting for an operator")
        .register(registry);
  }
}
