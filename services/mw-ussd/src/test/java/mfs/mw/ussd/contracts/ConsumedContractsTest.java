package mfs.mw.ussd.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import mfs.mw.ussd.dto.StatusMessage;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every valid contract sample of each event this module consumes ({@code consumes.events} of
 * {@code contracts.yml}) deserialises into the module's own DTO with the JsonMapper the
 * listener uses ({@code StatusReplyListener}: {@code readValue} on the service's mapper).
 *
 * <p>Invalid samples are not asserted: the listener does not validate a status record (no schema
 * check, no Bean Validation); {@code StatusMessage} ignores unknown fields and the orchestrator
 * owns the contract. A check here would test a rule the consumer does not have.
 */
class ConsumedContractsTest {

  static final JsonMapper JSON = ServiceJson.MAPPER;

  /** Per manifest entry: read one sample into the listener's DTO and check what the service uses. */
  static final Map<String, Consumer<String>> READERS =
      Map.of("events/mw-orchestrator/mw.txn.status.v1.schema.json", ConsumedContractsTest::readStatus);

  static void readStatus(String json) {
    StatusMessage status = JSON.readValue(json, StatusMessage.class);
    assertThat(status.txnId()).isNotBlank();
    assertThat(status.status()).isNotBlank();
    assertThat(status.channel()).isNotBlank();
    assertThat(status.awaitedTxnId()).isNotBlank();
  }

  @TestFactory
  Stream<DynamicTest> everyValidSampleDeserialisesIntoTheModulesDto() {
    return ContractManifest.load().consumedEvents().stream()
        .flatMap(entry -> {
          Consumer<String> reader = READERS.get(entry);
          List<Path> samples = ContractManifest.samples(entry, "valid");
          return Stream.concat(
              Stream.of(dynamicTest(entry + " has a reader and samples", () -> {
                assertThat(reader).as("reader for manifest entry %s", entry).isNotNull();
                assertThat(samples).as("valid samples of %s", entry).isNotEmpty();
              })),
              samples.stream().map(sample -> dynamicTest(entry + " " + sample.getFileName(),
                  () -> reader.accept(Files.readString(sample)))));
        });
  }

  @Test
  void everyReaderIsInTheManifest() {
    assertThat(READERS.keySet())
        .containsExactlyInAnyOrderElementsOf(ContractManifest.load().consumedEvents());
  }
}
