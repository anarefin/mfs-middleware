package mfs.mw.checkout.scheduler;

import mfs.mw.checkout.messaging.OutboxOtpScrubber;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link OutboxOtpScrubber#scrubRelayed()} on {@code mw.outbox.otp-scrub.cron} (every 5 s;
 * "-" switches it off, as in tests), so a relayed OTP stays in the outbox for seconds at most
 * (review A8). ShedLock-guarded: one instance at a time.
 */
@Component
public class OutboxOtpScrubJob {

  public static final String LOCK_NAME = "outbox-otp-scrub";

  private final OutboxOtpScrubber scrubber;

  public OutboxOtpScrubJob(OutboxOtpScrubber scrubber) {
    this.scrubber = scrubber;
  }

  @Scheduled(cron = "${mw.outbox.otp-scrub.cron}")
  @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT1M")
  public void scrub() {
    scrubber.scrubRelayed();
  }
}
