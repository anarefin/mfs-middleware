package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import mfs.mw.simulators.config.MwServiceProperties;
import mfs.mw.simulators.contracts.ContractSchemas;
import mfs.mw.simulators.dto.MockSwitch;
import mfs.mw.simulators.messaging.EventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** Switch state becomes a route-status health report routing can skip before PAY (ticket 112c, T8). */
class OcsRouteHealthTest {

  private static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SCHEMA = "events/mw-adapter-billpay/mw.partner.route.status.v1.schema.json";

  @Test
  void anOcsPartyMapsToItsRoutingPartnerAndAClosedSwitchIsDown() {
    assertThat(OcsRouteHealth.partnerId("ocs-gp")).isEqualTo("gp-ocs");
    assertThat(OcsRouteHealth.partnerId("ocs-robi")).isEqualTo("robi-ocs");
    assertThat(OcsRouteHealth.partnerId("ocs-teletalk")).isEqualTo("teletalk-ocs");
    assertThat(OcsRouteHealth.partnerId("ocs-bl")).isEqualTo("bl-ocs");
    assertThat(OcsRouteHealth.partnerId("ocs")).isEqualTo("ocs");
    assertThat(OcsRouteHealth.partnerId("core")).isNull();
    assertThat(OcsRouteHealth.health(MockSwitch.State.DOWN)).isEqualTo("DOWN");
    assertThat(OcsRouteHealth.health(MockSwitch.State.MAINTENANCE)).isEqualTo("MAINTENANCE");
    assertThat(OcsRouteHealth.health(MockSwitch.State.UP)).isEqualTo("UP");
    assertThat(OcsRouteHealth.health(MockSwitch.State.SLOW)).isEqualTo("UP");
  }

  @Test
  void aDownSwitchPublishesAHealthReportTheRouteStatusContractAccepts() {
    EventPublisher publisher = mock(EventPublisher.class);
    OcsRouteHealthPublisher health =
        new OcsRouteHealthPublisher(
            publisher, Clock.fixed(NOW, ZoneOffset.UTC), new MwServiceProperties("mw-simulators", "t", "test"));

    health.publish("ocs-gp", MockSwitch.State.DOWN);

    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(publisher).publish(eq(OcsRouteHealthPublisher.TOPIC), eq("gp-ocs"), any(), body.capture());
    var node = JSON.valueToTree(body.getValue());
    assertThat(ContractSchemas.schema(SCHEMA).validate(node)).isEmpty();
    assertThat(node.path("health").asString()).isEqualTo("DOWN");
    assertThat(node.path("partnerId").asString()).isEqualTo("gp-ocs");
    assertThat(node.path("version").asInt()).isZero();
  }

  @Test
  void aPartyRoutingDoesNotKnowPublishesNothing() {
    EventPublisher publisher = mock(EventPublisher.class);
    OcsRouteHealthPublisher health =
        new OcsRouteHealthPublisher(
            publisher, Clock.fixed(NOW, ZoneOffset.UTC), new MwServiceProperties("mw-simulators", "t", "test"));

    health.publish("core", MockSwitch.State.DOWN);

    verifyNoInteractions(publisher);
  }
}
