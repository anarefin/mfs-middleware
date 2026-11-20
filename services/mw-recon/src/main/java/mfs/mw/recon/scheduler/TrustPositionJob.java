package mfs.mw.recon.scheduler;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import mfs.mw.recon.client.CoreEmoneyClient;
import mfs.mw.recon.config.ReconProperties;
import mfs.mw.recon.dto.TrustPosition;
import mfs.mw.recon.service.TrustPositionService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily trust account position (ticket 127c, FRS REC-07), after the MT940 cut-off ({@code
 * mw.recon.trust.position-cron}, default 00:45 Asia/Dhaka, after the 00:30 MT940 pull): yesterday's MT940 closing
 * balance against the core's e-money total ({@link CoreEmoneyClient}). No closing balance for the day yet: the core
 * is not asked and nothing is stored (the dev/test {@code POST /test/v1/trust-position} catches up). A difference
 * raises the {@code AMOUNT_MISMATCH} / {@code TRUST_POSITION} exception ({@link TrustPositionService}).
 */
@Component
public class TrustPositionJob {

  private static final Logger log = LoggerFactory.getLogger(TrustPositionJob.class);

  private final CoreEmoneyClient core;
  private final TrustPositionService positions;
  private final ZoneId zone;
  private final Clock clock;

  @Autowired
  public TrustPositionJob(CoreEmoneyClient core, TrustPositionService positions, ReconProperties props, Clock clock) {
    this(core, positions, props.trust().zone(), clock);
  }

  TrustPositionJob(CoreEmoneyClient core, TrustPositionService positions, String zone, Clock clock) {
    this.core = core;
    this.positions = positions;
    this.zone = ZoneId.of(zone);
    this.clock = clock;
  }

  @Scheduled(cron = "${mw.recon.trust.position-cron:0 45 0 * * *}", zone = "${mw.recon.trust.zone}")
  @SchedulerLock(name = "recon-trust-position")
  public void run() {
    runFor(LocalDate.now(clock.withZone(zone)).minusDays(1));
  }

  /** The position of {@code day}, or null when the trust bank's closing balance of that day is not in yet. */
  public TrustPosition runFor(LocalDate day) {
    if (!positions.closingKnown(day)) {
      log.atInfo().addKeyValue("event", "recon.trust_position.no_statement")
          .log("no MT940 closing balance for {} yet; trust position not computed", day);
      return null;
    }
    TrustPosition p = positions.compute(day, core.emoneyTotalMinor());
    log.atInfo().addKeyValue("event", "recon.trust_position.computed")
        .addKeyValue("errorCode", p.differenceMinor() == 0 ? null : "AMOUNT_MISMATCH")
        .log("trust position for {}: difference {} poisha", day, p.differenceMinor());
    return p;
  }
}
