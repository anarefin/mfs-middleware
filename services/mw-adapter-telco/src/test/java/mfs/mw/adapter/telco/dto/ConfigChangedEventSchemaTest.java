package mfs.mw.adapter.telco.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.adapter.telco.contracts.ContractSchemas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Schema test layer (spec 22.1): this service's own type for {@code mw.config.changed.v1} reads
 * every valid contract sample and writes JSON the contract accepts. Contracts come from the root
 * {@code contracts/} folder through the {@code mwContractsDir} Gradle property only (4.3 rule 2),
 * loaded by {@link ContractSchemas} so the schema's {@code $ref}s into {@code common/} resolve.
 */
class ConfigChangedEventSchemaTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  static final String SCHEMA = "events/mw-admin-api/mw.config.changed.v1.schema.json";

  static final String SAMPLES = "events/mw-admin-api/samples/mw.config.changed.v1";

  /** Method source for the parameterised test. */
  static List<Path> validSamples() {
    return ContractSchemas.samples(SAMPLES, "valid");
  }

  @Test
  void contractsComeFromTheRootContractsFolder() {
    assertThat(ContractSchemas.contractsDir().resolve("OWNERS.yml")).isRegularFile();
    assertThat(ContractSchemas.fileFor(ContractSchemas.ID_BASE + SCHEMA)).isRegularFile();
    assertThat(validSamples()).as("valid samples in %s", SAMPLES).isNotEmpty();
  }

  @ParameterizedTest
  @MethodSource("validSamples")
  void readsEverySampleAndWritesValidJson(Path sample) throws Exception {
    ConfigChangedEvent event = JSON.readValue(Files.readString(sample), ConfigChangedEvent.class);

    assertThat(ContractSchemas.schema(SCHEMA).validate(JSON.valueToTree(event)))
        .as("re-serialised %s", sample)
        .isEmpty();
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

    assertThat(ContractSchemas.schema(SCHEMA).validate(JSON.valueToTree(event))).isEmpty();
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
