package mfs.mw.service.template.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Schema test layer (spec 22.1): this service's own type for {@code mw.config.changed.v1} reads
 * every valid contract sample and writes JSON the contract accepts. Contracts come from the root
 * {@code contracts/} folder through the {@code mwContractsDir} Gradle property only (4.3 rule 2).
 */
class ConfigChangedEventSchemaTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  static Path contractsDir() {
    String dir = System.getProperty("mwContractsDir");
    assertThat(dir).as("mwContractsDir system property (set by build.gradle.kts)").isNotBlank();
    return Path.of(dir);
  }

  static Path schemaFile() {
    return contractsDir().resolve("events/mw-admin-api/mw.config.changed.v1.schema.json");
  }

  static Stream<Path> validSamples() throws Exception {
    Path dir = contractsDir().resolve("events/mw-admin-api/samples/mw.config.changed.v1");
    try (Stream<Path> files = Files.list(dir)) {
      List<Path> valid =
          files.filter(p -> p.getFileName().toString().startsWith("valid")).sorted().toList();
      assertThat(valid).as("valid samples in %s", dir).isNotEmpty();
      return valid.stream();
    }
  }

  static List<Error> validate(JsonNode node) throws Exception {
    Schema schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(Files.readString(schemaFile()));
    return schema.validate(node);
  }

  @Test
  void contractsComeFromTheRootContractsFolder() {
    Path dir = contractsDir();
    assertThat(dir.resolve("OWNERS.yml")).isRegularFile();
    assertThat(schemaFile()).isRegularFile();
  }

  @ParameterizedTest
  @MethodSource("validSamples")
  void readsEverySampleAndWritesValidJson(Path sample) throws Exception {
    ConfigChangedEvent event = JSON.readValue(Files.readString(sample), ConfigChangedEvent.class);

    assertThat(validate(JSON.valueToTree(event))).as("re-serialised %s", sample).isEmpty();
  }

  @Test
  void eventBuiltByTheServiceMatchesTheContract() throws Exception {
    ConfigChangedEvent event =
        new ConfigChangedEvent(
            1,
            "partner/gp",
            ConfigChangedEvent.ConfigType.PARTNER,
            ConfigChangedEvent.Change.UPSERT,
            3,
            Map.of("enabled", true),
            Instant.parse("2026-11-03T10:00:00Z"),
            "ops.maker1",
            null,
            null);

    assertThat(validate(JSON.valueToTree(event))).isEmpty();
  }

  @Test
  void unknownFieldsAreIgnored() {
    ConfigChangedEvent event =
        JSON.readValue(
            """
            {"schemaVersion":1,"configKey":"flag/x","configType":"FEATURE_FLAG","change":"DELETE",
             "version":2,"changedAt":"2026-11-03T10:00:00Z","changedBy":"ops","addedLater":true}
            """,
            ConfigChangedEvent.class);

    assertThat(event.change()).isEqualTo(ConfigChangedEvent.Change.DELETE);
  }
}
