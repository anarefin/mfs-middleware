package mfs.mw.portal.api.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import mfs.mw.portal.api.client.OrchestratorQueryClient;
import mfs.mw.portal.api.config.PortalApiProperties;
import mfs.mw.portal.api.dto.OrgTxn;
import mfs.mw.portal.api.entity.ItemStatus;
import mfs.mw.portal.api.exception.OrchestratorUnavailableException;
import mfs.mw.portal.api.repository.BatchRepository;
import mfs.mw.portal.api.repository.BatchRepository.RetryCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The retry check of an approved retry (ticket 129c, FRS CH-POR-04): a row never pays twice. The
 * orchestrator answers a re-sent key that ended FAILED with that FAILED, so a retried row goes out
 * as a new attempt with its own txnId and key, and only once its failed attempt is proven to have
 * posted nothing ({@link #postedNothing}), read from mw-orchestrator's internal query API: else the
 * row stays FAILED for good in this round (WARN {@code bulk.row.retry_refused}). An unavailable
 * orchestrator ends the run with the rows still waiting.
 */
@Service
public class BulkRetryService {

  private static final Logger log = LoggerFactory.getLogger(BulkRetryService.class);

  static final String POST_TRANSFER = "PostTransfer";
  static final String FAILURE = "FAILURE";
  static final String UNKNOWN = "UNKNOWN";

  private final BatchRepository batches;
  private final OrchestratorQueryClient orchestrator;
  private final TransactionTemplate tx;
  private final PortalApiProperties.Retry props;
  private final Clock clock;

  public BulkRetryService(
      BatchRepository batches, OrchestratorQueryClient orchestrator, TransactionTemplate tx, PortalApiProperties props,
      Clock clock) {
    this.batches = batches;
    this.orchestrator = orchestrator;
    this.tx = tx;
    this.props = props.bulk().retry();
    this.clock = clock;
  }

  /** One run; returns the number of rows it decided (sent again or kept FAILED). */
  public int verify() {
    int decided = 0;
    for (RetryCandidate row : batches.retryCandidates(props.batchSize())) {
      Optional<OrgTxn.Detail> found;
      try {
        found = row.txnId() == null ? Optional.empty() : orchestrator.detail(row.orgId(), row.txnId());
      } catch (OrchestratorUnavailableException e) {
        log.atWarn()
            .addKeyValue("event", "bulk.retry.unavailable")
            .addKeyValue("batchId", row.batchId())
            .log("Bulk retry check stopped: orchestrator query API unavailable");
        break;
      }
      if (decide(row, found.isPresent() && postedNothing(found.get()))) {
        decided++;
      }
    }
    return decided;
  }

  private boolean decide(RetryCandidate row, boolean clear) {
    Instant now = clock.instant();
    Boolean changed =
        tx.execute(
            t -> {
              if (clear) {
                return batches.retryCleared(row.batchId(), row.lineNo(), row.attempt(), now);
              }
              boolean refused = batches.retryRefused(row.batchId(), row.lineNo(), now);
              if (refused) {
                batches.completeIfSettled(row.batchId(), now);
              }
              return refused;
            });
    if (Boolean.TRUE.equals(changed) && !clear) {
      log.atWarn()
          .addKeyValue("event", "bulk.row.retry_refused")
          .addKeyValue("batchId", row.batchId())
          .addKeyValue("lineNo", row.lineNo())
          .addKeyValue("txnId", row.txnId())
          .log("Bulk row not retried: its failed attempt is not proven to have posted nothing");
    }
    return Boolean.TRUE.equals(changed);
  }

  /**
   * Whether a failed attempt certainly moved no money: the orchestrator still has it FAILED, it never
   * was UNKNOWN (an outcome an inquiry decided), it has no core reference, and every PostTransfer it
   * sent was answered with a refusal.
   */
  static boolean postedNothing(OrgTxn.Detail d) {
    OrgTxn t = d.transaction();
    if (t == null || !ItemStatus.FAILED.name().equals(t.status()) || t.coreRef() != null) {
      return false;
    }
    List<OrgTxn.Step> timeline = d.timeline() == null ? List.of() : d.timeline();
    for (OrgTxn.Step s : timeline) {
      if (UNKNOWN.equals(s.to()) || UNKNOWN.equals(s.from())) {
        return false;
      }
      if (OrgTxn.CORE.equals(s.kind()) && POST_TRANSFER.equals(s.command()) && !FAILURE.equals(s.outcome())) {
        return false;
      }
    }
    return true;
  }
}
