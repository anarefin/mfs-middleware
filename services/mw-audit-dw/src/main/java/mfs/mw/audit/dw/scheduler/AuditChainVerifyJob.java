package mfs.mw.audit.dw.scheduler;

import mfs.mw.audit.dw.service.ChainVerifyService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link ChainVerifyService#verifyAll()} on {@code mw.audit-dw.chain-verify.cron} (hourly by
 * default; {@code -} switches the schedule off). ShedLock-guarded: one instance verifies at a time.
 */
@Component
@ConditionalOnProperty(name = "mw.audit-dw.chain-verify.enabled", havingValue = "true", matchIfMissing = true)
public class AuditChainVerifyJob {

  public static final String LOCK_NAME = "audit-chain-verify";

  private final ChainVerifyService verify;

  public AuditChainVerifyJob(ChainVerifyService verify) {
    this.verify = verify;
  }

  @Scheduled(cron = "${mw.audit-dw.chain-verify.cron:0 30 * * * *}")
  @SchedulerLock(name = LOCK_NAME)
  public void verify() {
    verify.verifyAll();
  }
}
