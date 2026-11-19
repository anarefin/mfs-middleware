package mfs.mw.simulators.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import mfs.mw.simulators.dto.MockBehaviour;
import mfs.mw.simulators.dto.MockScenario;
import mfs.mw.simulators.dto.MockSettings;
import mfs.mw.simulators.dto.MockSwitch;
import mfs.mw.simulators.dto.RecordedRequest;
import mfs.mw.simulators.service.mockcontrol.MockParties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The API this module serves ({@code produces.apis}, ticket 102): what the mock-control endpoints
 * answer, serialised as Spring MVC does, validates against {@code mock-control.v1}; the contract's
 * behaviour list is the engine's; the contract refuses what the controller refuses.
 */
class MockControlContractTest {

  static final String API = "api/mw-simulators/mock-control.v1.openapi.yaml";
  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  private static JsonNode tree(Object o) {
    return JSON.readTree(JSON.writeValueAsString(o));
  }

  @Test
  void servedBodiesAreTheContracts() {
    MockScenario s = MockScenario.of("topup", MockBehaviour.TIMEOUT).withDelayMs(500L).withKey("01712345678")
        .withNextN(2).withIdAndRemaining("S-1", 2);
    assertThat(OpenApiSchemas.response(API, "/mock-control/{party}/scenarios", "post", "201").validate(tree(s))).isEmpty();
    assertThat(OpenApiSchemas.component(API, "Scenario").validate(tree(MockScenario.of(null, MockBehaviour.SUCCESS)
        .withIdAndRemaining("S-2", null)))).isEmpty();
    assertThat(OpenApiSchemas.response(API, "/mock-control/{party}/switch", "put", "200")
        .validate(tree(new MockSwitch(MockSwitch.State.SLOW, 100L, 200L, null)))).isEmpty();
    assertThat(OpenApiSchemas.response(API, "/mock-control/{party}/settings", "get", "200")
        .validate(tree(MockSettings.DEFAULT))).isEmpty();
    assertThat(OpenApiSchemas.response(API, "/mock-control/parties", "get", "200").validate(tree(MockParties.ALL))).isEmpty();
    RecordedRequest r = new RecordedRequest(1, Instant.parse("2026-11-19T10:00:00Z"), "POST", "/ocs/v1/topup", null,
        Map.of("content-type", "application/json"), "{}");
    assertThat(OpenApiSchemas.component(API, "JournalEntry").validate(tree(r))).isEmpty();
  }

  /** Ticket 103: the global reset's answer, and the core's answer it passes on, are what both contracts say. */
  @Test
  void theGlobalResetAnswersAreTheContracts() {
    var result = new mfs.mw.simulators.dto.ResetResult(List.of(
        mfs.mw.simulators.dto.ResetResult.PartyResult.reset("ocs"),
        new mfs.mw.simulators.dto.ResetResult.PartyResult("core", "RESET", "v1", null),
        mfs.mw.simulators.dto.ResetResult.PartyResult.failed("core", "mw-core-mock answered 503")));
    assertThat(OpenApiSchemas.response(API, "/mock-control/reset", "post", "200").validate(tree(result))).isEmpty();
    String core = "api/mw-core-mock/core-mock-control.v1.openapi.yaml";
    JsonNode coreAnswer = JSON.readTree("{\"parties\":[{\"party\":\"core\",\"status\":\"RESET\",\"seedVersion\":\"v1\"}]}");
    assertThat(OpenApiSchemas.response(core, "/mock-control/reset", "post", "200").validate(coreAnswer))
        .as("the core answer GlobalResetIT's stub plays").isEmpty();
    assertThat(JSON.readValue(coreAnswer.toString(), mfs.mw.simulators.dto.ResetResult.class).parties())
        .singleElement().extracting(mfs.mw.simulators.dto.ResetResult.PartyResult::seedVersion).isEqualTo("v1");
  }

  @Test
  void theContractListsEveryBehaviourOfTheEngine() {
    JsonNode e = tree(Arrays.stream(MockBehaviour.values()).map(Enum::name).toList());
    assertThat(OpenApiSchemas.component(API, "Behaviour").validate(tree("NOPE"))).isNotEmpty();
    for (JsonNode b : e) {
      assertThat(OpenApiSchemas.component(API, "Behaviour").validate(b)).as(b.asString()).isEmpty();
    }
  }

  @Test
  void theContractRefusesWhatTheControllerRefuses() {
    var request = OpenApiSchemas.component(API, "ScenarioRequest");
    assertThat(request.validate(JSON.readTree("{\"behaviour\":\"DELAY\",\"delayMs\":10}"))).isEmpty();
    assertThat(request.validate(JSON.readTree("{}"))).isNotEmpty();
    assertThat(request.validate(JSON.readTree("{\"behaviour\":\"HTTP_5XX\",\"httpStatus\":600}"))).isNotEmpty();
    assertThat(request.validate(JSON.readTree("{\"behaviour\":\"TIMEOUT\",\"nextN\":0}"))).isNotEmpty();
    assertThat(request.validate(JSON.readTree("{\"behaviour\":\"DELAY\",\"delayMs\":-1}"))).isNotEmpty();
    var sw = OpenApiSchemas.component(API, "Switch");
    assertThat(sw.validate(JSON.readTree("{\"state\":\"OFF\"}"))).isNotEmpty();
    assertThat(sw.validate(JSON.readTree("{\"state\":\"DOWN\",\"ratio\":1.5}"))).isNotEmpty();
  }
}
