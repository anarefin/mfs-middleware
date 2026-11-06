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
                "customer", "customers", "seed-customer01", List.of("customer"), Map.of("msisdn", "01712000001"),
                "mw-app", "KC_CUSTOMERS_TEST_PASSWORD", null));
    ctx.wallets().add(new SeedManifest.Wallet("01712000001", "CUSTOMER", 1_000_000));
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
    assertThat(m.path("personas").get(0).path("passwordEnv").asString()).isEqualTo("KC_CUSTOMERS_TEST_PASSWORD");
    assertThat(m.path("partners").get(0).path("timeoutMs").asLong()).isEqualTo(20_000);
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
    more.wallets().add(new SeedManifest.Wallet("01812000001", "AGENT", 5));
    assertThat(step.run(more)).isEqualTo(StepResult.updated(1));
  }

  @Test
  void theLoggedCopyMasksMsisdns() {
    String logged = WriteManifestStep.masked(seeded().manifest());

    assertThat(logged).contains("0171****001").doesNotContain("01712000001");
  }
}
