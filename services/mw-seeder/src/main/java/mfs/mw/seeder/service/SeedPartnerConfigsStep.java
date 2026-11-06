package mfs.mw.seeder.service;

import java.util.ArrayList;
import java.util.List;
import mfs.mw.seeder.client.AdminConsole;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.config.SeederProperties.PartnerSpec;
import mfs.mw.seeder.dto.PartnerChangeRequest;
import mfs.mw.seeder.dto.PartnerView;
import mfs.mw.seeder.dto.SeedManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * seed-partner-configs: every partner of {@code mw.seeder.partners} at its baseline in mw-admin-api
 * (enabled, its call time limit), through maker-checker: for each difference the seed maker asks
 * (ENABLE / DISABLE / SET_TIMEOUTS) and the seed checker approves, which publishes the config to
 * the adapters. A partner already at its baseline is left alone (no request: the admin refuses a
 * change that changes nothing). The partners themselves come from mw-admin-api's dev seeds (the
 * full configs live with the adapters); one it does not know fails the step.
 */
@Service
@Order(4)
public class SeedPartnerConfigsStep implements SeedStep {

  static final String REASON = "seed: partner baseline (mw-seeder)";

  private static final Logger log = LoggerFactory.getLogger(SeedPartnerConfigsStep.class);

  private final AdminConsole admin;
  private final MakerChecker ops;
  private final SeederProperties props;

  public SeedPartnerConfigsStep(AdminConsole admin, SeederProperties props) {
    this.admin = admin;
    this.ops = new MakerChecker(admin, props.operators());
    this.props = props;
  }

  @Override
  public String id() {
    return "seed-partner-configs";
  }

  @Override
  public StepResult run(SeedContext ctx) {
    StepResult result = StepResult.unchanged(0);
    List<SeedManifest.Partner> listed = new ArrayList<>();
    for (PartnerSpec spec : props.partners()) {
      int changes =
          ops.approveLeftovers(admin.pendingRequests(ops.checker(), "partner", spec.partnerId()), "partner " + spec.partnerId());
      PartnerView p = current(spec.partnerId());
      if (p.config().isEnabled() != spec.enabled()) {
        change(spec.partnerId(), PartnerChangeRequest.of(spec.enabled() ? "ENABLE" : "DISABLE", REASON));
        changes++;
      }
      if (spec.timeoutMs() != null && !spec.timeoutMs().equals(p.config().timeoutMs())) {
        change(spec.partnerId(), PartnerChangeRequest.timeout(spec.timeoutMs(), REASON));
        changes++;
      }
      if (changes > 0) {
        p = current(spec.partnerId());
        log.atInfo()
            .addKeyValue("event", "seed.partner.updated")
            .addKeyValue("partnerId", spec.partnerId())
            .log("partner {} back at its baseline, version {}", spec.partnerId(), p.version());
      }
      result = result.plus(changes > 0 ? StepResult.updated(1) : StepResult.unchanged(1));
      listed.add(
          new SeedManifest.Partner(p.partnerId(), p.family(), p.version(), p.config().isEnabled(), p.config().timeoutMs()));
    }
    ctx.partners().addAll(listed);
    return result;
  }

  private PartnerView current(String partnerId) {
    return admin
        .partner(ops.checker(), partnerId)
        .orElseThrow(() -> new IllegalStateException("partner " + partnerId + " is unknown to mw-admin-api"));
  }

  private void change(String partnerId, PartnerChangeRequest request) {
    ops.approve(admin.requestPartnerChange(ops.maker(), partnerId, request));
  }
}
