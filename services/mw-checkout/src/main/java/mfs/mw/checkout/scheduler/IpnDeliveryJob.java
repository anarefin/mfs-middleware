package mfs.mw.checkout.scheduler;

import mfs.mw.checkout.service.IpnDeliveryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link IpnDeliveryService#deliverDue()} every {@code mw.checkout.ipn.poll-period} (ticket
 * 32). ShedLock-guarded: one instance sends IPNs at a time, so an attempt is never posted twice
 * in parallel. The waits between attempts are rows' {@code due_at}, never a sleep.
 */
@Component
@ConditionalOnProperty(name = "mw.checkout.ipn.enabled", havingValue = "true", matchIfMissing = true)
public class IpnDeliveryJob {

  public static final String LOCK_NAME = "checkout-ipn-delivery";

  private final IpnDeliveryService ipn;

  public IpnDeliveryJob(IpnDeliveryService ipn) {
    this.ipn = ipn;
  }

  @Scheduled(fixedDelayString = "${mw.checkout.ipn.poll-period}")
  @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT15M")
  public void deliver() {
    ipn.deliverDue();
  }
}
