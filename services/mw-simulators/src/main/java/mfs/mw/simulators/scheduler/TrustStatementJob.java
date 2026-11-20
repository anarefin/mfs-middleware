package mfs.mw.simulators.scheduler;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import mfs.mw.simulators.service.files.TrustBankService;
import mfs.mw.simulators.service.files.TrustStatementGenerator;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The trust bank's statements (ticket 123): the MT940 of the previous day after the daily cut-off ({@code
 * sim.files.trustbank.cutoff-cron}, Asia/Dhaka) and an MT942 of today so far every {@code
 * sim.files.trustbank.intraday} (cut time to the minute). ShedLock-guarded; only with {@code sim.files.enabled} and
 * the mock-control profile.
 */
@Component
@Profile(MockControl.PROFILE)
@ConditionalOnProperty(name = "sim.files.enabled", havingValue = "true")
public class TrustStatementJob {

  private static final Logger log = LoggerFactory.getLogger(TrustStatementJob.class);

  private final TrustBankService bank;
  private final Clock clock;

  public TrustStatementJob(TrustBankService bank, Clock clock) {
    this.bank = bank;
    this.clock = clock;
  }

  @Scheduled(cron = "${sim.files.trustbank.cutoff-cron}", zone = "Asia/Dhaka")
  @SchedulerLock(name = "sim-trust-mt940")
  public void daily() {
    try {
      bank.mt940(LocalDate.now(clock.withZone(TrustStatementGenerator.DHAKA)).minusDays(1));
    } catch (RuntimeException e) {
      log.atWarn().addKeyValue("event", "sim.files.statement_failed").addKeyValue("partnerId", "trustbank")
          .log("MT940 not written: {}", e.getMessage());
    }
  }

  @Scheduled(fixedDelayString = "${sim.files.trustbank.intraday}", initialDelayString = "${sim.files.trustbank.intraday}")
  @SchedulerLock(name = "sim-trust-mt942")
  public void intraday() {
    try {
      LocalDateTime now = LocalDateTime.now(clock.withZone(TrustStatementGenerator.DHAKA)).truncatedTo(ChronoUnit.MINUTES);
      bank.mt942(now.toLocalDate(), now.toLocalTime());
    } catch (RuntimeException e) {
      log.atWarn().addKeyValue("event", "sim.files.statement_failed").addKeyValue("partnerId", "trustbank")
          .log("MT942 not written: {}", e.getMessage());
    }
  }
}
