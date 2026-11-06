package mfs.mw.seeder.service;

import static mfs.mw.seeder.service.SeedFixtures.MAKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import mfs.mw.seeder.dto.MenuProposal;
import mfs.mw.seeder.dto.SeedManifest;
import mfs.mw.seeder.dto.TemplateProposal;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

class SeedAdminContentStepTest {

  private final FakeAdminConsole admin = new FakeAdminConsole();
  private final SeedAdminContentStep step =
      new SeedAdminContentStep(
          admin, SeedFixtures.props(Path.of("unused")), new DefaultResourceLoader(), JsonMapper.builder().build());

  @Test
  void aFreshAdminGetsTheMenuAndTheTemplatePublishedByMakerAndChecker() {
    SeedContext ctx = new SeedContext();

    assertThat(step.run(ctx)).isEqualTo(StepResult.created(2));

    assertThat(admin.writes)
        .containsExactly(
            "request ussd_menu/main PUBLISH", "approve ussd_menu/main",
            "request notify_template/TXN_P2P_RECEIVED/SMS/en PUBLISH", "approve notify_template/TXN_P2P_RECEIVED/SMS/en");
    assertThat(ctx.menus()).containsExactly(new SeedManifest.UssdMenu("main", 1, List.of("gp", "robi")));
    assertThat(ctx.templates()).containsExactly(new SeedManifest.Template("TXN_P2P_RECEIVED", "SMS", "en", 1));
  }

  @Test
  void theMenuIsMwUssdsTreeWithoutItsIdAndVersionLiveOnEveryListedMno() {
    step.run(new SeedContext());

    MenuProposal menu =
        admin.requests.values().stream().map(FakeAdminConsole.Cr::body).filter(MenuProposal.class::isInstance)
            .map(MenuProposal.class::cast).findFirst().orElseThrow();
    assertThat(menu.menu().has("menuId")).isFalse();
    assertThat(menu.menu().has("version")).isFalse();
    assertThat(menu.menu().path("start").asString()).isEqualTo("main");
    assertThat(menu.menu().path("nodes").has("main")).isTrue();
    assertThat(menu.activations()).containsExactly(new MenuProposal.Activation("gp"), new MenuProposal.Activation("robi"));
    TemplateProposal t =
        admin.requests.values().stream().map(FakeAdminConsole.Cr::body).filter(TemplateProposal.class::isInstance)
            .map(TemplateProposal.class::cast).findFirst().orElseThrow();
    assertThat(t).isEqualTo(new TemplateProposal("You have received Tk {{amount}}", null, "Bits", SeedAdminContentStep.REASON));
  }

  @Test
  void contentAlreadyPublishedThroughTheAdminIsLeftAloneAndItsLatestVersionListed() {
    admin.putVersion("ussd-menu/main", 1, "PUBLISHED", MAKER);
    admin.putVersion("ussd-menu/main", 2, "PUBLISHED", "ops-e2e-maker01");
    admin.putVersion("template/TXN_P2P_RECEIVED/SMS/en", 1, "PUBLISHED", MAKER);
    admin.writes.clear();
    SeedContext ctx = new SeedContext();

    assertThat(step.run(ctx)).isEqualTo(StepResult.unchanged(2));
    assertThat(admin.writes).isEmpty();
    assertThat(ctx.menus()).containsExactly(new SeedManifest.UssdMenu("main", 2, List.of("gp", "robi")));
  }

  @Test
  void aVersionTheSeedMakerLeftPendingIsApprovedInsteadOfProposedAgain() {
    admin.putVersion("ussd-menu/main", 1, "PENDING", MAKER);
    admin.writes.clear();

    assertThat(step.run(new SeedContext())).isEqualTo(StepResult.created(2));
    assertThat(admin.writes).startsWith("approve ussd_menu/main");
    assertThat(admin.writes).doesNotContain("request ussd_menu/main PUBLISH");
  }

  @Test
  void aVersionAnotherOperatorLeftPendingFailsTheStep() {
    admin.putVersion("ussd-menu/main", 1, "PENDING", "ops-e2e-maker01");

    assertThatThrownBy(() -> step.run(new SeedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ops-e2e-maker01");
  }

  @Test
  void aRejectedOrExpiredVersionDoesNotCountAsSeeded() {
    admin.putVersion("ussd-menu/main", 1, "REJECTED", MAKER);
    admin.writes.clear();

    step.run(new SeedContext());
    assertThat(admin.writes).contains("request ussd_menu/main PUBLISH");
  }
}
