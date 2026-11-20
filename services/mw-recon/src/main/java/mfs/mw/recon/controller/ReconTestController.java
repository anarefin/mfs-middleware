package mfs.mw.recon.controller;

import java.util.List;
import mfs.mw.recon.dto.RunSummary;
import mfs.mw.recon.scheduler.TrustStatementPullJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev/test only: pulls today's trust statements so the API suite can inject a mismatch and ask
 * mw-recon to read it. Key-auth at the test-only APISIX route. Off unless
 * {@code mw.recon.test-api.enabled} is true.
 */
@RestController
@ConditionalOnProperty(name = "mw.recon.test-api.enabled", havingValue = "true")
public class ReconTestController {

  private final TrustStatementPullJob pull;

  public ReconTestController(TrustStatementPullJob pull) {
    this.pull = pull;
  }

  @PostMapping("/test/v1/pull")
  public List<RunSummary> pull() {
    List<RunSummary> runs = new java.util.ArrayList<>();
    runs.addAll(pull.pull("MT940_", true));
    runs.addAll(pull.pull("MT942_", false));
    return List.copyOf(runs);
  }
}
