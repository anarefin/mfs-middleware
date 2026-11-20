package mfs.mw.notify.scheduler;

import mfs.mw.notify.service.CampaignService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs due campaigns every {@code mw.notify.campaign.period} (ticket 111, NOT-07). ShedLock so one
 * instance queues each recipient. The tick does not sleep: the per-second throttle is a budget, and
 * OTP dispatch stays on its own job.
 */
@Component
@ConditionalOnProperty(name = "mw.notify.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class CampaignRunner {

  public static final String LOCK_NAME = "notify-campaign";

  private final CampaignService campaigns;

  public CampaignRunner(CampaignService campaigns) {
    this.campaigns = campaigns;
  }

  @Scheduled(fixedDelayString = "${mw.notify.campaign.period:200ms}")
  @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M")
  public void run() {
    campaigns.run();
  }
}
