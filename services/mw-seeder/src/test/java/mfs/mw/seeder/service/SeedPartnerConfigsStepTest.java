package mfs.mw.seeder.service;

import static mfs.mw.seeder.service.SeedFixtures.MAKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import mfs.mw.seeder.dto.PartnerChangeRequest;
import mfs.mw.seeder.dto.PartnerView;
import mfs.mw.seeder.dto.SeedManifest;
import org.junit.jupiter.api.Test;

class SeedPartnerConfigsStepTest {

  private final FakeAdminConsole admin = new FakeAdminConsole();
  private final SeedPartnerConfigsStep step = new SeedPartnerConfigsStep(admin, SeedFixtures.props(Path.of("unused")));

  private void ocs(boolean enabled, long timeoutMs, long version) {
    admin.partners.put("ocs", new PartnerView("ocs", "telco", version, new PartnerView.Config(enabled, timeoutMs)));
  }

  @Test
  void aPartnerAtItsBaselineIsLeftAloneAndListed() {
    ocs(true, 20_000, 0);
    SeedContext ctx = new SeedContext();

    assertThat(step.run(ctx)).isEqualTo(StepResult.unchanged(1));
    assertThat(admin.writes).isEmpty();
    assertThat(ctx.partners()).containsExactly(new SeedManifest.Partner("ocs", "telco", 0, true, 20_000L));
  }

  @Test
  void aDisabledPartnerWithAnotherTimeLimitIsPutBackByMakerAndCheckerOneChangeAtATime() {
    ocs(false, 3_000, 4);
    SeedContext ctx = new SeedContext();

    assertThat(step.run(ctx)).isEqualTo(StepResult.updated(1));

    assertThat(admin.writes)
        .containsExactly(
            "request partner/ocs ENABLE", "approve partner/ocs", "request partner/ocs SET_TIMEOUTS", "approve partner/ocs");
    assertThat(admin.requests.values()).allSatisfy(c -> assertThat(c.maker()).isEqualTo(MAKER));
    assertThat(ctx.partners()).containsExactly(new SeedManifest.Partner("ocs", "telco", 6, true, 20_000L));
    admin.writes.clear();
    assertThat(step.run(new SeedContext())).isEqualTo(StepResult.unchanged(1));
    assertThat(admin.writes).isEmpty();
  }

  @Test
  void aPendingRequestTheSeedMakerLeftBehindIsApprovedFirst() {
    ocs(false, 20_000, 1);
    admin.requestPartnerChange(MAKER, "ocs", PartnerChangeRequest.of("ENABLE", "left over"));
    admin.writes.clear();

    assertThat(step.run(new SeedContext())).isEqualTo(StepResult.updated(1));
    assertThat(admin.writes).containsExactly("approve partner/ocs");
    assertThat(admin.partners.get("ocs").config().isEnabled()).isTrue();
  }

  @Test
  void aPendingRequestOfAnotherOperatorFailsTheStepWithoutTouchingIt() {
    ocs(false, 20_000, 1);
    admin.requestPartnerChange("ops-e2e-maker01", "ocs", PartnerChangeRequest.of("ENABLE", "test"));
    admin.writes.clear();

    assertThatThrownBy(() -> step.run(new SeedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ops-e2e-maker01");
    assertThat(admin.writes).isEmpty();
  }

  @Test
  void aPartnerUnknownToTheAdminFailsTheStep() {
    assertThatThrownBy(() -> step.run(new SeedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ocs");
  }
}
