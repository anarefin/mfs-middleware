package mfs.mw.recon.scheduler;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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

  static final int MAX_LOOKBACK_DAYS = 31;

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
  @SchedulerLock(name = "recon-trust-position", lockAtLeastFor = "PT5M")
  public void run() {
    LocalDate yesterday = LocalDate.now(clock.withZone(zone)).minusDays(1);
    for (LocalDate missed : missedDays(yesterday)) {
      log.atWarn().addKeyValue("event", "recon.trust_position.missed").addKeyValue("errorCode", "TRUST_POSITION_MISSING")
          .log("no trust position for {}: its MT940 closing balance was not in at the cut-off; the core's e-money total"
              + " is a current figure, so that day needs a manual position", missed);
    }
    if (runFor(yesterday) == null) {
      log.atWarn().addKeyValue("event", "recon.trust_position.no_statement")
          .addKeyValue("errorCode", "TRUST_POSITION_MISSING")
          .log("no MT940 closing balance for {} at the position cut-off; trust position not computed", yesterday);
    }
  }

  /**
   * REC-07: the days after the last stored position and before {@code yesterday} that have no position (at most
   * {@value #MAX_LOOKBACK_DAYS} days back). They are reported, not computed: the core's e-money total is a current
   * figure, so a past day cannot be compared with it.
   */
  List<LocalDate> missedDays(LocalDate yesterday) {
    LocalDate last = positions.lastPositionDate();
    if (last == null) {
      return List.of();
    }
    LocalDate from = last.plusDays(1);
    LocalDate floor = yesterday.minusDays(MAX_LOOKBACK_DAYS);
    if (from.isBefore(floor)) {
      from = floor;
    }
    List<LocalDate> missed = new ArrayList<>();
    for (LocalDate d = from; d.isBefore(yesterday); d = d.plusDays(1)) {
      if (!positions.positionKnown(d)) {
        missed.add(d);
      }
    }
    return missed;
  }

  /** The position of {@code day}, or null when the trust bank's closing balance of that day is not in yet. */
  public TrustPosition runFor(LocalDate day) {
    if (!positions.closingKnown(day)) {
      return null;
    }
    TrustPosition p = positions.compute(day, core.emoneyTotalMinor());
    log.atInfo().addKeyValue("event", "recon.trust_position.computed")
        .addKeyValue("errorCode", p.differenceMinor() == 0 ? null : "AMOUNT_MISMATCH")
        .log("trust position for {}: difference {} poisha", day, p.differenceMinor());
    return p;
  }
}
