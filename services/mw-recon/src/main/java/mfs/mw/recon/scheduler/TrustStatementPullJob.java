package mfs.mw.recon.scheduler;

import java.util.ArrayList;
import java.util.List;
import mfs.mw.recon.client.SftpClient;
import mfs.mw.recon.dto.RunSummary;
import mfs.mw.recon.service.ReconService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pulls the trust bank's statements (ticket 126). The SFTP read runs on the scheduler thread,
 * never on a Kafka listener. With no password configured the job does nothing.
 */
@Component
public class TrustStatementPullJob {

  private static final Logger log = LoggerFactory.getLogger(TrustStatementPullJob.class);

  private final SftpClient sftp;
  private final ReconService recon;

  public TrustStatementPullJob(SftpClient sftp, ReconService recon) {
    this.sftp = sftp;
    this.recon = recon;
  }

  @Scheduled(cron = "${mw.recon.trust.mt940-cron}", zone = "${mw.recon.trust.zone}")
  @SchedulerLock(name = "recon-mt940")
  public void pullDaily() {
    pull("MT940_", true);
  }

  @Scheduled(cron = "${mw.recon.trust.mt942-cron}", zone = "${mw.recon.trust.zone}")
  @SchedulerLock(name = "recon-mt942")
  public void pullIntraday() {
    pull("MT942_", false);
  }

  /** Every new file of {@code prefix}. A file already registered is skipped. */
  public List<RunSummary> pull(String prefix, boolean daily) {
    if (!sftp.configured()) {
      log.atInfo().addKeyValue("event", "recon.sftp.unconfigured").log("trust SFTP password is not set");
      return List.of();
    }
    List<RunSummary> runs = new ArrayList<>();
    for (String name : sftp.list(prefix)) {
      runs.add(recon.ingestStatement(name, sftp.read(name), daily));
    }
    return List.copyOf(runs);
  }
}
