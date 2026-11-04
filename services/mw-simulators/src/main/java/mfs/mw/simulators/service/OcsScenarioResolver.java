package mfs.mw.simulators.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import mfs.mw.simulators.dto.OcsMode;
import mfs.mw.simulators.dto.OcsTopupRequest;
import mfs.mw.simulators.dto.Scenario;
import org.springframework.stereotype.Service;

/**
 * Picks the outcome. A forced mode wins; then magic values: MSISDN ending 999 is a business
 * failure; the amount's fraction .13 is a timeout, .77 a 5xx after send, .99 an unknown code,
 * .55 a malformed reply.
 */
@Service
public class OcsScenarioResolver {

  public OcsMode resolve(OcsTopupRequest req, Scenario scenario) {
    if (scenario.forceMode() != null) {
      return scenario.forceMode();
    }
    if (req.msisdn().endsWith("999")) {
      return OcsMode.BUSINESS_FAILURE;
    }
    int cents =
        new BigDecimal(req.amount())
            .movePointRight(2)
            .toBigInteger()
            .mod(BigInteger.valueOf(100))
            .intValue();
    return switch (cents) {
      case 13 -> OcsMode.TIMEOUT;
      case 77 -> OcsMode.SERVER_ERROR_AFTER_SEND;
      case 99 -> OcsMode.UNKNOWN_CODE;
      case 55 -> OcsMode.MALFORMED;
      default -> OcsMode.SUCCESS;
    };
  }
}
