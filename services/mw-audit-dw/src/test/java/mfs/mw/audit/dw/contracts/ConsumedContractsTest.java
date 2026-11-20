package mfs.mw.audit.dw.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import mfs.mw.audit.dw.dto.AuditEvent;
import mfs.mw.audit.dw.dto.ReconExceptionEvent;
import mfs.mw.audit.dw.dto.TxnStatusEvent;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every {@code consumes.events} entry of {@code contracts.yml} (ticket 53): each valid sample reads
 * into this service's own type with the mapper {@code messaging/AuditEventListener} uses (the
 * Boot-built JsonMapper: Boot 4 with no {@code spring.jackson.*} settings is {@code
 * findAndAddModules()} on the Jackson 3 defaults) and carries every field {@code
 * AuditService.validate} requires. Invalid samples are not asserted rejected: the consumer checks
 * only that required fields are present, not the formats and enums the invalid samples break
 * ({@code sourceIp} format, {@code actor.type} enum), so a rejection here would invent a check.
 *
 * <p>mw-audit-dw produces no events ({@code produces.events} is empty), so it has no {@code
 * ProducedContractsTest}; {@link #producesNoEvents} makes adding one to the manifest fail here.
 */
class ConsumedContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  static final Map<String, Consumer<String>> READERS =
      Map.of(
          "events/mw-audit-dw/mw.audit.event.v1.schema.json",
          body -> {
            AuditEvent e = JSON.readValue(body, AuditEvent.class);
            // the fields AuditService.validate requires before storing
            assertThat(e.auditId()).isNotNull();
            assertThat(e.correlationId()).isNotBlank();
            assertThat(e.actor()).isNotNull();
            assertThat(e.actor().type()).isNotBlank();
            assertThat(e.actor().id()).isNotBlank();
            assertThat(e.action()).isNotBlank();
            assertThat(e.resource()).isNotBlank();
            assertThat(e.outcome()).isNotBlank();
            assertThat(e.service()).isNotBlank();
            assertThat(e.at()).isNotNull();
          },
          // ticket 130: the search projection audit.txn_index (service/TxnIndexService)
          "events/mw-orchestrator/mw.txn.status.v1.schema.json",
          body -> {
            TxnStatusEvent e = JSON.readValue(body, TxnStatusEvent.class);
            assertThat(e.txnId()).isNotNull();
            assertThat(e.status()).isNotBlank();
            assertThat(e.channel()).isNotBlank();
            assertThat(e.amount()).isNotNull();
            assertThat(e.amount().valueMinor()).isNotNull();
            assertThat(e.updatedAt()).isNotNull();
          },
          // ticket 127b: recon exception state changes into the audit trail (service/ReconExceptionAudit)
          "events/mw-recon/mw.recon.exception.v1.schema.json",
          body -> {
            ReconExceptionEvent e = JSON.readValue(body, ReconExceptionEvent.class);
            assertThat(e.eventId()).isNotNull();
            assertThat(e.exceptionId()).isNotNull();
            assertThat(e.state()).isNotBlank();
            assertThat(e.changedAt()).isNotNull();
            assertThat(e.correlationId()).isNotBlank();
          });

  static final ContractManifest MANIFEST = ContractManifest.load();

  @TestFactory
  Stream<DynamicTest> everyValidSampleReadsIntoTheModuleType() {
    return MANIFEST.consumedEvents().stream()
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
    assertThat(MANIFEST.consumedEvents()).containsExactlyInAnyOrderElementsOf(READERS.keySet());
  }

  @Test
  void producesNoEvents() {
    assertThat(MANIFEST.producedEvents()).as("a produced event needs a ProducedContractsTest").isEmpty();
  }
}
