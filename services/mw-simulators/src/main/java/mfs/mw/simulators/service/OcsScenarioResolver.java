package mfs.mw.simulators.service;

import mfs.mw.simulators.dto.OcsTopupRequest;
import mfs.mw.simulators.dto.Scenario;
import mfs.mw.simulators.dto.SimMode;
import org.springframework.stereotype.Service;

/**
 * Picks the outcome. A forced mode wins; then magic values: MSISDN ending 999 is a business
 * failure; then the amount ({@link MagicAmounts}).
 */
@Service
public class OcsScenarioResolver {

  public SimMode resolve(OcsTopupRequest req, Scenario scenario) {
    if (scenario.forceMode() != null) {
      return scenario.forceMode();
    }
    if (req.msisdn().endsWith("999")) {
      return SimMode.BUSINESS_FAILURE;
    }
    return MagicAmounts.mode(req.amount());
  }
}
