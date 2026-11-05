package mfs.mw.portal.api.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import mfs.mw.portal.api.client.OrchestratorQueryClient;
import mfs.mw.portal.api.config.PortalApiProperties;
import mfs.mw.portal.api.dto.OrgTxn;
import mfs.mw.portal.api.entity.ItemStatus;
import mfs.mw.portal.api.entity.SubmittedChild;
import mfs.mw.portal.api.exception.OrchestratorUnavailableException;
import mfs.mw.portal.api.repository.BatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reconciles bulk children whose status record never arrived (M3 gate decision, spec 9.4): a row
 * SUBMITTED longer than {@code bulk.reconcile.after} is looked up on mw-orchestrator's internal
 * query API (authenticated as client mw-portal-api, scoped to the batch's org). A settled state
 * settles the row as the status record would; a row still unsettled (in progress, or unknown to the
 * orchestrator) {@code give-up-after} after it was sent becomes UNRESOLVED, and its batch then ends
 * COMPLETED_WITH_EXCEPTIONS. An unavailable orchestrator ends the run with nothing changed; nothing
 * is re-sent (the orchestrator's idempotency would answer a resend, but a lost child is for
 * operations to look at).
 */
@Service
public class BulkReconcileService {

  private static final Logger log = LoggerFactory.getLogger(BulkReconcileService.class);

  private final BatchRepository batches;
  private final BulkExecutionService execution;
  private final OrchestratorQueryClient orchestrator;
  private final PortalApiProperties.Reconcile props;
  private final Clock clock;

  public BulkReconcileService(
      BatchRepository batches, BulkExecutionService execution, OrchestratorQueryClient orchestrator,
      PortalApiProperties props, Clock clock) {
    this.batches = batches;
    this.execution = execution;
    this.orchestrator = orchestrator;
    this.props = props.bulk().reconcile();
    this.clock = clock;
  }

  /** One run; returns the number of rows it settled or gave up. */
  public int reconcile() {
    Instant now = clock.instant();
    int changed = 0;
    for (SubmittedChild row : batches.claimStaleSubmitted(now.minus(props.after()), now, props.batchSize())) {
      Optional<OrgTxn.Detail> found;
      try {
        found = orchestrator.detail(row.orgId(), row.txnId());
      } catch (OrchestratorUnavailableException e) {
        log.atWarn()
            .addKeyValue("event", "bulk.reconcile.unavailable")
            .addKeyValue("batchId", row.batchId())
            .log("Bulk reconcile stopped: orchestrator query API unavailable");
        break;
      }
      Optional<ItemStatus> settled =
          found.map(OrgTxn.Detail::transaction).flatMap(t -> ItemStatus.settledBy(t.status()));
      if (settled.isPresent()) {
        OrgTxn t = found.get().transaction();
        if (execution.settle(row.txnId(), settled.get(), t.error() == null ? null : t.error().code())) {
          changed++;
        }
      } else if (!row.submittedAt().isAfter(now.minus(props.giveUpAfter()))) {
        if (execution.giveUp(row.txnId())) {
          changed++;
          log.atWarn()
              .addKeyValue("event", "bulk.row.unresolved")
              .addKeyValue("batchId", row.batchId())
              .addKeyValue("lineNo", row.lineNo())
              .addKeyValue("txnId", row.txnId())
              .addKeyValue("found", found.isPresent())
              .log("Bulk row unresolved: no settled state since " + row.submittedAt());
        }
      }
    }
    if (changed > 0) {
      log.atInfo().addKeyValue("event", "bulk.reconcile.done").addKeyValue("rows", changed).log("Bulk rows reconciled");
    }
    return changed;
  }
}
