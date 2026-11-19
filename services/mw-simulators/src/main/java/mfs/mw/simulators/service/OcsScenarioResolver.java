package mfs.mw.simulators.service;

import mfs.mw.simulators.dto.OcsTopupRequest;
import mfs.mw.simulators.dto.SimMode;
import org.springframework.stereotype.Service;

/**
 * The OCS magic values (MCK-03), the default trigger when no mock-control scenario decides: MSISDN
 * ending 999 is a business failure; then the amount ({@link MagicAmounts}).
 */
@Service
public class OcsScenarioResolver {

  public SimMode magic(OcsTopupRequest req) {
    if (req.msisdn().endsWith("999")) {
      return SimMode.BUSINESS_FAILURE;
    }
    return MagicAmounts.mode(req.amount());
  }
}
