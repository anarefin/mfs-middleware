package mfs.mw.simulators.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Control API bodies written before a field existed must still be accepted (ticket 22 added
 * {@code callbackOnly}), even by a mapper that refuses missing primitives as the image does.
 */
class ScenarioTest {

  private final JsonMapper strict =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

  @Test
  void aScenarioWithoutCallbackOnlyIsAcceptedAndNotCallbackOnly() {
    // the body the e2e OCS step sends (every primitive of the ticket 15 record, nothing newer)
    Scenario s =
        strict.readValue(
            "{\"latencyMs\":25000,\"errorRate\":1.0,\"outage\":false,\"callbackMode\":\"NONE\",\"callbackDelayMs\":0}",
            Scenario.class);

    assertThat(s.callbackOnly()).isFalse();
    assertThat(s.latencyMs()).isEqualTo(25_000);
  }

  @Test
  void callbackOnlyIsReadWhenGiven() {
    Scenario s =
        strict.readValue(
            "{\"latencyMs\":0,\"errorRate\":0,\"outage\":false,\"callbackDelayMs\":0,\"callbackOnly\":true}",
            Scenario.class);

    assertThat(s.callbackOnly()).isTrue();
    assertThat(Scenario.DEFAULT.withCallbackOnly(true).callbackOnly()).isTrue();
  }
}
