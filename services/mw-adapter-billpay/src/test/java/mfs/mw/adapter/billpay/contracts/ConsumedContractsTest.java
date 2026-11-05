package mfs.mw.adapter.billpay.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import mfs.mw.adapter.billpay.dto.ConfigChangedEvent;
import mfs.mw.adapter.billpay.dto.PartnerRequest;
import mfs.mw.adapter.billpay.mapper.PartnerCallMapper;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigListener;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigStore;
import mfs.mw.adapter.billpay.partner.config.PartnerEndpointPolicy;
import mfs.mw.adapter.billpay.partner.config.PartnerProperties;
import mfs.mw.adapter.billpay.partner.template.PartnerInstanceFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Every event this module consumes ({@code consumes.events} in {@code contracts.yml}, ticket 53):
 * each valid contract sample deserialises into the module's own DTO with the service's JSON mapper,
 * as the listener reads it, and is accepted by the consumer's own checks. Invalid samples are
 * asserted rejected only where the consumer itself checks; no Spring context, no Docker.
 */
class ConsumedContractsTest {

  static final String REQUEST = "events/mw-orchestrator/mw.partner.request.v1.schema.json#billpay";
  static final String CONFIG = "events/mw-admin-api/mw.config.changed.v1.schema.json";

  /** Reads a valid sample the way the consumer does and checks it is usable. */
  static final Map<String, Consumer<String>> READERS =
      Map.of(
          REQUEST,
          body -> {
            // BillpayRequestHandler.handle: parse, ids present, then TopupService's mapper check
            PartnerRequest request = ServiceJson.MAPPER.readValue(body, PartnerRequest.class);
            assertThat(request.requestId()).isNotBlank();
            assertThat(request.txnId()).isNotBlank();
            assertThat(request.correlationId()).isNotBlank();
            // the adapter sends PAY and STATUS_QUERY only; any other operation of its family is a
            // valid contract message it answers FAILED MW-VAL-INVALID_PARTNER_REQUEST, here
            // INQUIRY (valid-bill-inquiry.json; the bill fetch is the HTTP API here)
            if (List.of("PAY", "STATUS_QUERY").contains(request.payload().operation())) {
              assertThat(new PartnerCallMapper().problem(request)).isEmpty();
            } else {
              assertThat(new PartnerCallMapper().problem(request)).contains("unsupported operation");
            }
          },
          CONFIG,
          body -> {
            // PartnerConfigListener.onRecord parses into ConfigChangedEvent
            ConfigChangedEvent event = ServiceJson.MAPPER.readValue(body, ConfigChangedEvent.class);
            assertThat(event.configKey()).isNotBlank();
            assertThat(event.change()).isNotNull();
          });

  @TestFactory
  Stream<DynamicTest> everyValidSampleOfAConsumedEventIsRead() {
    return ContractManifest.load().consumedEvents().stream()
        .flatMap(
            entry -> {
              List<Path> samples = samples(entry, "valid");
              assertThat(samples).as("valid samples for %s", entry).isNotEmpty();
              assertThat(READERS).as("reader for manifest entry %s", entry).containsKey(entry);
              return samples.stream()
                  .map(s -> DynamicTest.dynamicTest(entry + " " + s.getFileName(), () -> READERS.get(entry).accept(read(s))));
            });
  }

  @Test
  void everyManifestEntryHasAReaderAndEveryReaderIsInTheManifest() {
    assertThat(READERS.keySet()).containsExactlyInAnyOrderElementsOf(ContractManifest.load().consumedEvents());
  }

  /** The billpay request check refuses every invalid sample (it is answered FAILED MW-VAL-*). */
  @Test
  void invalidBillpayRequestSamplesAreRefusedByTheRequestCheck() {
    List<Path> invalid = samples(REQUEST, "invalid");
    assertThat(invalid).isNotEmpty();
    for (Path s : invalid) {
      PartnerRequest request = ServiceJson.MAPPER.readValue(read(s), PartnerRequest.class);
      assertThat(new PartnerCallMapper().problem(request)).as("%s", s.getFileName()).isPresent();
    }
  }

  /**
   * The config store refuses an UPSERT without a value (it cannot build a partner config), so the
   * previous config stays. The consumer does not check {@code schemaVersion}
   * (invalid-wrong-schema-version.json): no check is asserted for it.
   */
  @Test
  void aConfigUpsertWithoutValueIsRefusedByTheStore() {
    PartnerProperties.Security security = new PartnerProperties.Security(Map.of(), List.of());
    PartnerConfigStore store =
        new PartnerConfigStore(
            new PartnerProperties(null, Map.of(), security),
            new PartnerInstanceFactory(ref -> "fake", Clock.systemUTC(), ServiceJson.MAPPER, new PartnerEndpointPolicy(security)),
            ServiceJson.MAPPER);
    Path sample = ContractSchemas.contractsDir()
        .resolve(ContractManifest.Entry.of(CONFIG).samplesDir())
        .resolve("invalid-upsert-without-value.json");
    ConfigChangedEvent event = ServiceJson.MAPPER.readValue(read(sample), ConfigChangedEvent.class);

    assertThat(store.apply(event)).isFalse();
    new PartnerConfigListener(ServiceJson.MAPPER, store).onRecord(read(sample));
    assertThat(store.instance("gp")).isEmpty();
  }

  /** {@code samples/<topic>/<Msg>/} for a {@code #<Msg>} entry, else every sample of the topic. */
  static List<Path> samples(String entry, String prefix) {
    ContractManifest.Entry e = ContractManifest.Entry.of(entry);
    Path dir = ContractSchemas.contractsDir().resolve(e.samplesDir());
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().startsWith(prefix))
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .sorted()
          .toList();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  static String read(Path p) {
    try {
      return Files.readString(p);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
