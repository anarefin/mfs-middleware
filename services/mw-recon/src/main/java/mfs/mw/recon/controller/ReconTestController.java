package mfs.mw.recon.controller;

import java.time.LocalDate;
import java.util.List;
import mfs.mw.recon.dto.RunSummary;
import mfs.mw.recon.dto.TrustPosition;
import mfs.mw.recon.exception.ErrorCodes;
import mfs.mw.recon.exception.MwException;
import mfs.mw.recon.scheduler.TrustPositionJob;
import mfs.mw.recon.scheduler.TrustStatementPullJob;
import mfs.mw.recon.service.TrustPositionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev/test only: pulls today's trust statements so the API suite can inject a mismatch and ask
 * mw-recon to read it, and runs the trust position job (ticket 127c) for a business date. Key-auth at the test-only APISIX route. Off unless
 * {@code mw.recon.test-api.enabled} is true.
 */
@RestController
@ConditionalOnProperty(name = "mw.recon.test-api.enabled", havingValue = "true")
public class ReconTestController {

  private final TrustStatementPullJob pull;
  private final TrustPositionJob trust;
  private final TrustPositionService positions;

  public ReconTestController(TrustStatementPullJob pull, TrustPositionJob trust, TrustPositionService positions) {
    this.pull = pull;
    this.trust = trust;
    this.positions = positions;
  }

  /**
   * The trust position job for {@code date} (default: the latest business date with an MT940 closing balance), with
   * the core's e-money total now. No closing balance: 404 {@code MW-VAL-003}.
   */
  @PostMapping("/test/v1/trust-position")
  public TrustPosition trustPosition(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    LocalDate day = date != null ? date : positions.latestClosingDate();
    TrustPosition p = day == null ? null : trust.runFor(day);
    if (p == null) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "no MT940 closing balance" + (day == null ? "" : " for " + day));
    }
    return p;
  }

  @PostMapping("/test/v1/pull")
  public List<RunSummary> pull() {
    List<RunSummary> runs = new java.util.ArrayList<>();
    runs.addAll(pull.pull("MT940_", true));
    runs.addAll(pull.pull("MT942_", false));
    return List.copyOf(runs);
  }
}
