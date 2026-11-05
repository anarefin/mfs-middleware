package mfs.mw.portal.api.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import mfs.mw.portal.api.dto.StatusMessage;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every {@code consumes.events} entry of {@code contracts.yml} (ticket 53): each valid sample of the
 * owner reads into this service's own type with the mapper {@code messaging/TxnStatusListener} uses
 * (the Boot-built JsonMapper). Invalid samples are not checked: the listener validates nothing
 * beyond reading the JSON ({@code BulkExecutionService.apply} only filters by channel, type and
 * settled state), so asserting a rejection here would invent a check the consumer does not make.
 */
class ConsumedContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  /** Per manifest entry: read a sample into the module's DTO and check what the consumer relies on. */
  static final Map<String, Consumer<String>> READERS =
      Map.of(
          "events/mw-orchestrator/mw.txn.status.v1.schema.json",
          body -> {
            StatusMessage s = JSON.readValue(body, StatusMessage.class);
            assertThat(s.txnId()).isNotBlank();
            assertThat(s.status()).isNotBlank();
            assertThat(s.channel()).isNotBlank();
            assertThat(s.txnType()).isNotBlank();
          });

  static final List<String> ENTRIES = ContractManifest.load().consumedEvents();

  @TestFactory
  Stream<DynamicTest> everyValidSampleReadsIntoTheModuleType() {
    return ENTRIES.stream()
        .flatMap(
            entry -> {
              assertThat(READERS).as("reader for manifest entry %s", entry).containsKey(entry);
              List<Path> samples = ContractManifest.validSamples(entry);
              assertThat(samples).as("valid samples of %s", entry).isNotEmpty();
              return samples.stream()
                  .map(p -> DynamicTest.dynamicTest(entry + " " + p.getFileName(), () -> READERS.get(entry).accept(Files.readString(p))));
            });
  }

  @Test
  void everyReaderIsInTheManifest() {
    assertThat(ENTRIES).containsExactlyInAnyOrderElementsOf(READERS.keySet());
  }
}
