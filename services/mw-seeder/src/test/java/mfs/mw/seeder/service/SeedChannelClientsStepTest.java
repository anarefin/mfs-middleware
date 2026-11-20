package mfs.mw.seeder.service;

import static mfs.mw.seeder.service.SeedFixtures.MAKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import mfs.mw.seeder.dto.ChannelClientChangeRequest;
import mfs.mw.seeder.dto.ChannelClientView;
import org.junit.jupiter.api.Test;

/**
 * seed-channel-clients (ticket 100a): today's edge clients as registry rows in mw-admin-api, through
 * maker-checker; a client at its baseline is left alone, so a second run asks nothing.
 */
class SeedChannelClientsStepTest {

  private final FakeAdminConsole admin = new FakeAdminConsole();
  private final SeedChannelClientsStep step = new SeedChannelClientsStep(admin, SeedFixtures.props(Path.of("unused")));

  @Test
  void missingClientsAreRegisteredByMakerAndCheckerAndASecondRunAsksNothing() {
    assertThat(step.run(new SeedContext())).isEqualTo(StepResult.created(2));

    assertThat(admin.writes).containsExactly(
        "request channel_client/ussd-gp UPSERT", "approve channel_client/ussd-gp",
        "request channel_client/customer-app UPSERT", "approve channel_client/customer-app");
    assertThat(admin.requests.values()).allSatisfy(c -> assertThat(c.maker()).isEqualTo(MAKER));
    ChannelClientView gp = admin.channelClients.get("ussd-gp");
    assertThat(gp.authMethod()).isEqualTo("BASIC");
    assertThat(gp.allowedRoutes()).containsExactly("ussd-http-gp");
    assertThat(gp.ratePerSec()).isEqualTo(100L);
    assertThat(gp.dailyQuota()).isNull();

    admin.writes.clear();
    assertThat(step.run(new SeedContext())).isEqualTo(StepResult.unchanged(2));
    assertThat(admin.writes).isEmpty();
  }

  @Test
  void aClientThatDriftedFromItsBaselineIsPutBack() {
    step.run(new SeedContext());
    ChannelClientView gp = admin.channelClients.get("ussd-gp");
    admin.channelClients.put("ussd-gp", new ChannelClientView(
        gp.clientId(), gp.type(), gp.authMethod(), gp.allowedRoutes(), List.of(), 5L, null, false, gp.version() + 1));
    admin.writes.clear();

    assertThat(step.run(new SeedContext())).isEqualTo(StepResult.updated(1).plus(StepResult.unchanged(1)));
    assertThat(admin.writes).containsExactly("request channel_client/ussd-gp UPSERT", "approve channel_client/ussd-gp");
    assertThat(admin.channelClients.get("ussd-gp").enabled()).isTrue();
  }

  @Test
  void aPendingRequestOfAnotherOperatorFailsTheStep() {
    admin.requestChannelClientChange("ops-e2e-maker01", "ussd-gp",
        ChannelClientChangeRequest.upsert(SeedFixtures.props(Path.of("x")).channelClients().getFirst().definition(), "test"));
    admin.writes.clear();

    assertThatThrownBy(() -> step.run(new SeedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ops-e2e-maker01");
    assertThat(admin.writes).isEmpty();
  }
}
