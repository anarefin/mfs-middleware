package mfs.mw.audit.dw.scheduler;

import mfs.mw.audit.dw.service.AnchorService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link AnchorService#anchorDue()} on {@code mw.audit-dw.anchor.cron} (UTC; hourly at :15 by
 * default, so a day missed because mw-crypto was down is signed on the next run; {@code -} off).
 * ShedLock-guarded: one instance signs at a time. A failure is the ERROR event {@code
 * audit.anchor.failed} (the day stays due).
 */
@Component
@ConditionalOnProperty(
    name = {"mw.audit-dw.anchor.enabled", "mw.audit-dw.chain-verify.enabled"},
    havingValue = "true",
    matchIfMissing = true)
public class AuditAnchorJob {

  public static final String LOCK_NAME = "audit-anchor";
  private static final Logger log = LoggerFactory.getLogger(AuditAnchorJob.class);

  private final AnchorService anchors;

  public AuditAnchorJob(AnchorService anchors) {
    this.anchors = anchors;
  }

  @Scheduled(cron = "${mw.audit-dw.anchor.cron:0 15 * * * *}", zone = "UTC")
  @SchedulerLock(name = LOCK_NAME)
  public void anchor() {
    try {
      anchors.anchorDue();
    } catch (RuntimeException e) {
      log.atError()
          .addKeyValue("event", "audit.anchor.failed")
          .addKeyValue("errorClass", e.getClass().getSimpleName())
          .log("daily audit anchor not signed; retried on the next run");
    }
  }
}
