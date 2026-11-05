package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.OcsTopupRequest;
import mfs.mw.simulators.dto.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Magic values (spec 22.2): amount ending .13 is a timeout, MSISDN suffix 999 a business failure. */
class OcsScenarioResolverTest {

  private final OcsScenarioResolver resolver = new OcsScenarioResolver();

  private static OcsTopupRequest req(String msisdn, String amount) {
    return new OcsTopupRequest("T-1", msisdn, amount);
  }

  @ParameterizedTest
  @CsvSource({
    "01712345678, 100.00, SUCCESS",
    "01712345678, 100.13, TIMEOUT",
    "01712345678, 100.77, SERVER_ERROR_AFTER_SEND",
    "01712345678, 100.99, UNKNOWN_CODE",
    "01712345678, 100.55, MALFORMED",
    "01712345999, 100.00, BUSINESS_FAILURE",
    "01712345999, 100.13, BUSINESS_FAILURE",
    "01712345678, 7, SUCCESS"
  })
  void magicValuesPickTheMode(String msisdn, String amount, SimMode expected) {
    assertThat(resolver.resolve(req(msisdn, amount), Scenario.DEFAULT)).isEqualTo(expected);
  }

  @Test
  void aForcedModeWinsOverMagicValues() {
    Scenario forced = Scenario.DEFAULT.withForceMode(SimMode.MALFORMED);
    assertThat(resolver.resolve(req("01712345999", "100.13"), forced)).isEqualTo(SimMode.MALFORMED);
  }
}
