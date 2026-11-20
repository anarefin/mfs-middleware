package mfs.mw.admin.api.service;

import java.util.Optional;
import mfs.mw.admin.api.config.ApisixProperties;
import mfs.mw.admin.api.exception.ApisixUnavailableException;
import mfs.mw.admin.api.repository.ChannelClientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keeps APISIX in line with the committed channel client registry (ticket 100a): one client right
 * after its approval commits ({@link #project}, from {@link ChannelClientChangeHandler}), all of
 * them on the reconcile schedule ({@link #reconcile}, {@code scheduler/ApisixReconcileJob}). Off
 * unless {@code mw.apisix.enabled}. An Admin API failure is logged and left to the next reconcile:
 * the registry row is the source of truth and already stored.
 */
@Service
public class ChannelClientProjection {

  private static final Logger log = LoggerFactory.getLogger(ChannelClientProjection.class);

  private final ChannelClientRepository clients;
  private final ApisixConsumerSync sync;
  private final ApisixProperties apisix;

  public ChannelClientProjection(ChannelClientRepository clients, ApisixConsumerSync sync, ApisixProperties apisix) {
    this.clients = clients;
    this.sync = sync;
    this.apisix = apisix;
  }

  /** Projects one committed row; nothing when the projection is off or the row is gone. */
  public void project(String clientId) {
    if (!apisix.enabled()) {
      return;
    }
    clients.find(clientId).ifPresent(c -> {
      try {
        ApisixConsumerSync.Outcome o = sync.apply(c);
        log.atInfo()
            .addKeyValue("event", "apisix.consumer.synced")
            .addKeyValue("clientId", c.clientId())
            .addKeyValue("outcome", o.name())
            .log("channel client {} projected to APISIX: {}", c.clientId(), o);
      } catch (ApisixUnavailableException e) {
        log.atWarn()
            .addKeyValue("event", "apisix.consumer.sync_failed")
            .addKeyValue("clientId", c.clientId())
            .log("channel client {} not projected to APISIX, the reconcile job retries: {}", c.clientId(), e.getMessage());
      }
    });
  }

  /** Brings APISIX in line with the whole registry; empty when off or APISIX failed. */
  public Optional<ApisixConsumerSync.Report> reconcile() {
    if (!apisix.enabled()) {
      return Optional.empty();
    }
    try {
      ApisixConsumerSync.Report r = sync.reconcile(clients.list());
      if (r.written() > 0 || r.removed() > 0) {
        log.atInfo()
            .addKeyValue("event", "apisix.consumers.reconciled")
            .addKeyValue("written", r.written())
            .addKeyValue("removed", r.removed())
            .log("APISIX consumers reconciled: {} written, {} removed", r.written(), r.removed());
      }
      return Optional.of(r);
    } catch (ApisixUnavailableException e) {
      log.atWarn()
          .addKeyValue("event", "apisix.consumers.reconcile_failed")
          .log("APISIX reconcile failed, next run retries: {}", e.getMessage());
      return Optional.empty();
    }
  }
}
