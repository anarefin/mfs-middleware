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
 * Pending batches expire (M3 gate decision): a batch still PENDING_APPROVAL {@code
 * bulk.expiry.after} (72 h) after its upload becomes EXPIRED, can no longer be approved or rejected
 * (409 MW-VAL-032) and is audited as {@code bulk.batch.expired} by the service itself, in the same
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
    List<Batch> expired = batches.expirePending(now.minus(props.after()), now, props.batchSize());
    for (Batch b : expired) {
      audit.record(b, "expired", null, BatchStatus.PENDING_APPROVAL, BatchStatus.EXPIRED, null);
      log.atInfo()
          .addKeyValue("event", "bulk.batch.expired")
          .addKeyValue("batchId", b.batchId())
          .addKeyValue("orgId", b.orgId())
          .log("Bulk batch expired: no decision within " + props.after());
    }
    return expired.size();
  }
}
