package mfs.mw.core.mock.controller;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.core.mock.config.ClockConfig;
import mfs.mw.core.mock.config.MockControlConfig;
import mfs.mw.core.mock.config.MockControlProperties;
import mfs.mw.core.mock.service.mockcontrol.Journal;
import mfs.mw.core.mock.service.mockcontrol.LegacyFaults;
import mfs.mw.core.mock.service.mockcontrol.PartySwitch;
import mfs.mw.core.mock.service.mockcontrol.ScenarioEngine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The admin API (the deprecated alias of /mock-control/core) injects, lists and resets faults; it validates what it is given. */
@WebMvcTest(FaultController.class)
@Import({LegacyFaults.class, ScenarioEngine.class, PartySwitch.class, Journal.class, MockControlConfig.class, ClockConfig.class})
@EnableConfigurationProperties(MockControlProperties.class)
class FaultControllerTest {

  private static final String URI = "/core-mock/v1/faults";

  private final MockMvcTester mvc;
  private final LegacyFaults registry;

  @Autowired
  FaultControllerTest(MockMvcTester mvc, LegacyFaults registry) {
    this.mvc = mvc;
    this.registry = registry;
  }

  private MockMvcTester.MockMvcRequestBuilder post(String body) {
    return mvc.post().uri(URI).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  @Test
  void injectsAFaultAndListsIt() {
    registry.reset();

    assertThat(post("{\"mode\":\"DROP_REPLY\",\"commandType\":\"PostTransfer\"}"))
        .hasStatus(201)
        .bodyJson()
        .extractingPath("$.mode")
        .isEqualTo("DROP_REPLY");

    assertThat(mvc.get().uri(URI)).hasStatusOk().bodyJson().extractingPath("$[0].commandType").isEqualTo("PostTransfer");
  }

  @Test
  void resetClearsEveryFault() {
    registry.reset();
    assertThat(post("{\"mode\":\"REJECT\",\"reasonCode\":\"X\"}")).hasStatus(201);
    assertThat(post("{\"mode\":\"DELAY\",\"delayMs\":100}")).hasStatus(201);

    assertThat(mvc.delete().uri(URI)).hasStatus(204);

    assertThat(registry.list()).isEmpty();
  }

  @Test
  void removesOneFaultAndReportsAnUnknownOne() {
    registry.reset();
    assertThat(post("{\"mode\":\"DROP_REPLY\"}")).hasStatus(201);
    String faultId = registry.list().get(0).id();

    assertThat(mvc.delete().uri(URI + "/" + faultId)).hasStatus(204);
    assertThat(mvc.delete().uri(URI + "/" + faultId)).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-003");
  }

  @Test
  void refusesInvalidFaults() {
    assertThat(post("{}")).hasStatus(400);
    assertThat(post("{\"mode\":\"DELAY\"}")).hasStatus(400);
    assertThat(post("{\"mode\":\"DELAY\",\"delayMs\":0}")).hasStatus(400);
    assertThat(post("{\"mode\":\"DROP_REPLY\",\"commandType\":\"Nope\"}")).hasStatus(400);
    assertThat(post("{\"mode\":\"DROP_REPLY\",\"times\":0}")).hasStatus(400);
    assertThat(post("{\"mode\":\"EXPLODE\"}")).hasStatus(400);
  }
}
