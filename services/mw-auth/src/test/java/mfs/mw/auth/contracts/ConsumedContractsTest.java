package mfs.mw.auth.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import mfs.mw.auth.dto.WalletLockCommand;
import mfs.mw.auth.dto.WalletStateChanged;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.mapper.WalletEventMapper;
import mfs.mw.auth.mapper.WalletLockMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every {@code consumes.events} entry of {@code contracts.yml} (ticket 67): each valid sample reads
 * through the listener's own {@link WalletEventMapper} (the Boot-built JsonMapper: Jackson 3
 * defaults) into this service's type with every field the projection needs; the invalid samples the
 * mapper checks (no version, an unknown status) are refused. A failure outcome is not checked by
 * the consumer (the producer's contract), so that sample is not asserted.
 *
 * <p>The events mw-auth produces are checked by {@code ProducedContractsTest} (ticket 68).
 */
class ConsumedContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  static final WalletEventMapper MAPPER = new WalletEventMapper(JSON);
  static final WalletLockMapper LOCKS = new WalletLockMapper(JSON);
  static final String WALLET_STATE = "events/mw-core-mock/core.event.v1.schema.json#WalletStateChanged";

  static final Map<String, Consumer<String>> READERS =
      Map.of(
          WALLET_STATE,
          body -> {
            WalletStateChanged e = MAPPER.read(body).orElseThrow();
            assertThat(e.eventId()).isNotBlank();
            assertThat(e.payload().walletId()).isNotBlank();
            assertThat(e.payload().version()).isPositive();
            assertThat(e.payload().hasPin()).isNotNull();
            assertThat(WalletEventMapper.nationalMsisdn(e.payload().msisdn())).matches("01[3-9]\\d{8}");
          },
          "events/mw-auth/auth.wallet-lock.v1.schema.json",
          body -> {
            WalletLockCommand c = LOCKS.read(body);
            assertThat(c.commandId()).isNotBlank();
            assertThat(c.walletId()).isNotBlank();
            assertThat(c.actor().id()).isNotBlank();
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
  void invalidWalletStatesAreRefused() throws Exception {
    Path dir = ContractSchemas.contractsDir().resolve("events/mw-core-mock/samples/core.event.v1/WalletStateChanged");
    for (String name : List.of("invalid-without-version.json", "invalid-unknown-status.json")) {
      String body = Files.readString(dir.resolve(name));
      assertThatThrownBy(() -> MAPPER.read(body)).as(name).isInstanceOf(MwException.class);
    }
  }

  @Test
  void anotherCoreEventIsNotAWalletState() throws Exception {
    Path sample = ContractManifest.validSamples("events/mw-core-mock/core.event.v1.schema.json#WalletUnlocked").getFirst();
    assertThat(MAPPER.read(Files.readString(sample))).isEmpty();
  }

  @Test
  void invalidWalletLockCommandsAreRefused() throws Exception {
    Path dir = ContractSchemas.contractsDir().resolve("events/mw-auth/samples/auth.wallet-lock.v1");
    for (String name : List.of("invalid-unknown-action.json", "invalid-without-actor.json",
        "invalid-pin-reset-without-change-request.json")) {
      String body = Files.readString(dir.resolve(name));
      assertThatThrownBy(() -> LOCKS.read(body)).as(name).isInstanceOf(MwException.class);
    }
  }

  @Test
  void everyReaderIsInTheManifest() {
    assertThat(MANIFEST.consumedEvents()).containsExactlyInAnyOrderElementsOf(READERS.keySet());
  }

  @Test
  void consumesTheCryptoAndCoreWalletApis() {
    assertThat(MANIFEST.consumedApis())
        .containsExactly("api/mw-crypto/crypto-internal.v1.openapi.yaml", "api/mw-core-mock/core-wallet.v1.openapi.yaml");
  }
}
