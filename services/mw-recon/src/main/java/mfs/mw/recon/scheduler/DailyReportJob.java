package mfs.mw.recon.scheduler;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import mfs.mw.recon.config.ReconProperties;
import mfs.mw.recon.service.ReportService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The daily recon report per partner for yesterday's Asia/Dhaka business date (ticket 127, FRS REC-05). */
@Component
public class DailyReportJob {

  private static final Logger log = LoggerFactory.getLogger(DailyReportJob.class);

  private final ReportService reports;
  private final ReconProperties props;
  private final Clock clock;

  public DailyReportJob(ReportService reports, ReconProperties props, Clock clock) {
    this.reports = reports;
    this.props = props;
    this.clock = clock;
  }

  @Scheduled(cron = "${mw.recon.report-cron:0 0 2 * * *}", zone = "${mw.recon.trust.zone}")
  @SchedulerLock(name = "recon-daily-report", lockAtLeastFor = "PT5M")
  public void run() {
    LocalDate day = LocalDate.now(clock.withZone(ZoneId.of(props.trust().zone()))).minusDays(1);
    int partners = reports.storeDaily(day, clock.instant());
    log.atInfo().addKeyValue("event", "recon.report.stored").log("daily recon reports stored for {} partners", partners);
  }
}
