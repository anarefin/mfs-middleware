package mfs.mw.seeder.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import mfs.mw.seeder.dto.SeedManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WriteManifestStepTest {

  @TempDir Path dir;

  private final JsonMapper json = JsonMapper.builder().build();

  private SeedContext seeded() {
    SeedContext ctx = new SeedContext();
    ctx.personas()
        .add(
            new SeedManifest.Persona(
                "customer", "mw-auth", "seed-customer01", "Sumaiya Islam", List.of("customer"),
                Map.of("msisdn", "01712000001"), null, "MW_SEED_APP_PIN", null));
    ctx.importedUsers()
        .add(
            new SeedManifest.Persona(
                "org-maker01", "portals", "org-maker01", "Mizanur Rahman", List.of("org-maker"), Map.of("orgId", "ORG001"),
                "mw-portal", "KC_PORTALS_TEST_PASSWORD", "KC_PORTALS_TEST_TOTP_SECRET"));
    ctx.wallets().add(new SeedManifest.Wallet("01712000001", "CUSTOMER", "Sumaiya Islam", 1_000_000));
    ctx.partners().add(new SeedManifest.Partner("ocs", "telco", 0, true, 20_000L));
    return ctx;
  }

  @Test
  void writesTheManifestAsJsonThatTheSuitesCanReadIncludingMissingFolders() {
    Path file = dir.resolve("seed/manifest.json");
    WriteManifestStep step = new WriteManifestStep(SeedFixtures.props(file), json);

    assertThat(step.run(seeded())).isEqualTo(StepResult.created(1));

    JsonNode m = json.readTree(file.toFile());
    assertThat(m.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(m.path("wallets").get(0).path("walletId").asString()).isEqualTo("01712000001");
    assertThat(m.path("personas").get(0).path("passwordEnv").asString()).isEqualTo("MW_SEED_APP_PIN");
    assertThat(m.path("partners").get(0).path("timeoutMs").asLong()).isEqualTo(20_000);
    assertThat(m.path("personas").get(0).path("displayName").asString()).isEqualTo("Sumaiya Islam");
    assertThat(m.path("wallets").get(0).path("holderName").asString()).isEqualTo("Sumaiya Islam");
    JsonNode imported = m.path("importedUsers").get(0);
    assertThat(imported.path("username").asString()).isEqualTo("org-maker01");
    assertThat(imported.path("displayName").asString()).isEqualTo("Mizanur Rahman");
    assertThat(imported.path("attributes").path("orgId").asString()).isEqualTo("ORG001");
    assertThat(imported.path("totpSecretEnv").asString()).isEqualTo("KC_PORTALS_TEST_TOTP_SECRET");
  }

  @Test
  void theSameSeedWritesTheSameFileAndReportsUnchangedADifferentOneUpdated() throws Exception {
    Path file = dir.resolve("manifest.json");
    WriteManifestStep step = new WriteManifestStep(SeedFixtures.props(file), json);
    step.run(seeded());
    String first = Files.readString(file);

    assertThat(step.run(seeded())).isEqualTo(StepResult.unchanged(1));
    assertThat(Files.readString(file)).isEqualTo(first);

    SeedContext more = seeded();
    more.wallets().add(new SeedManifest.Wallet("01812000001", "AGENT", null, 5));
    assertThat(step.run(more)).isEqualTo(StepResult.updated(1));
  }

  @Test
  void theLoggedCopyMasksMsisdns() {
    String logged = WriteManifestStep.masked(seeded().manifest());

    assertThat(logged).contains("0171****001").doesNotContain("01712000001");
    assertThat(logged).as("names masked to the first initial (18.3)")
        .contains("displayName=S.", "holderName=S.", "displayName=M.")
        .doesNotContain("Sumaiya", "Islam", "Mizanur", "Rahman");
  }
}
