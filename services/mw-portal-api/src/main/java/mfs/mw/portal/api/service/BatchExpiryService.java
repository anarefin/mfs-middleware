package mfs.mw.portal.api.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import mfs.mw.portal.api.config.PortalApiProperties;
import mfs.mw.portal.api.entity.Batch;
import mfs.mw.portal.api.entity.BatchStatus;
import mfs.mw.portal.api.repository.BatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pending batches expire (M3 gate decision): a batch still PREVIEW (ticket 129b) or PENDING_APPROVAL
 * {@code bulk.expiry.after} (72 h) after its upload becomes EXPIRED, can no longer be submitted,
 * approved or rejected (409 MW-VAL-036 / MW-VAL-032) and is audited as {@code bulk.batch.expired} by the service itself, in the same
 * transaction.
 */
@Service
public class BatchExpiryService {

  private static final Logger log = LoggerFactory.getLogger(BatchExpiryService.class);

  private final BatchRepository batches;
  private final BatchAudit audit;
  private final PortalApiProperties.Expiry props;
  private final Clock clock;

  public BatchExpiryService(BatchRepository batches, BatchAudit audit, PortalApiProperties props, Clock clock) {
    this.batches = batches;
    this.audit = audit;
    this.props = props.bulk().expiry();
    this.clock = clock;
  }

  /** Expires one run's worth of overdue batches; returns how many. */
  @Transactional
  public int expirePending() {
    Instant now = clock.instant();
    int count = 0;
    for (BatchStatus from : List.of(BatchStatus.PREVIEW, BatchStatus.PENDING_APPROVAL)) {
      List<Batch> expired = batches.expirePending(from, now.minus(props.after()), now, props.batchSize());
      for (Batch b : expired) {
        audit.record(b, "expired", null, from, BatchStatus.EXPIRED, null);
        log.atInfo()
            .addKeyValue("event", "bulk.batch.expired")
            .addKeyValue("batchId", b.batchId())
            .addKeyValue("orgId", b.orgId())
            .log("Bulk batch expired: not submitted or decided within " + props.after());
      }
      count += expired.size();
    }
    return count;
  }
}
