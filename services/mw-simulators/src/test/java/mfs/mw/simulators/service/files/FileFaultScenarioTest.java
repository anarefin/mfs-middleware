package mfs.mw.simulators.service.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.Executors;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.MockBehaviour;
import mfs.mw.simulators.dto.MockScenario;
import mfs.mw.simulators.dto.RecordedRequest;
import mfs.mw.simulators.exception.MwException;
import mfs.mw.simulators.service.mockcontrol.Journal;
import mfs.mw.simulators.service.mockcontrol.PartySwitch;
import mfs.mw.simulators.service.mockcontrol.ScenarioEngine;
import mfs.mw.simulators.service.files.TrustStatementGenerator.Mismatch;
import mfs.mw.simulators.util.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

/**
 * The file faults of every file mock (ticket 123, FRS MCT-05): MISSING_FILE, LATE_FILE, DUPLICATE_FILE,
 * CORRUPT_FILE and INJECTED_MISMATCH, each with its documented effect on the file written, scoped to the next N
 * files (or a key such as the business date) only, and every file written or skipped in the party's journal
 * with its checksum.
 */
class FileFaultScenarioTest {

  private static final String PATH = "trustbank/out/MT940_0011_20261120.fin";
  private static final Map<String, String> KEYS = Map.of("date", "2026-11-20", "account", "0011");

  private final PartySwitch switches = new PartySwitch();
  private final SimulatorProperties props = new SimulatorProperties(Duration.ofMillis(700), 100);
  private final ScenarioEngine engine = new ScenarioEngine(switches, props, new SplittableRandom(7));
  private final Journal journal = new Journal(props);
  private final InMemoryRemoteFiles files = new InMemoryRemoteFiles();
  private final java.util.concurrent.ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
  private final FileMocks mocks = new FileMocks(engine, files, journal, new ConcurrentTaskScheduler(executor), props,
      Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC));

  @AfterEach
  void stop() {
    executor.shutdownNow();
  }

  private List<RecordedRequest> produce() {
    return mocks.produce("trustbank", "mt940", KEYS, PATH,
        m -> ("STATEMENT " + (m.kind() == null ? "clean" : m.kind() + "x" + m.count())).getBytes(StandardCharsets.UTF_8));
  }

  private static String text(byte[] b) {
    return new String(b, StandardCharsets.UTF_8);
  }

  @Test
  void withoutAScenarioTheFileIsWrittenAndJournaledWithItsChecksum() {
    List<RecordedRequest> entries = produce();

    assertThat(text(files.read(PATH))).isEqualTo("STATEMENT clean");
    assertThat(entries).singleElement().satisfies(e -> {
      assertThat(e.method()).isEqualTo("WRITE");
      assertThat(e.path()).isEqualTo(PATH);
      assertThat(e.headers()).containsEntry("sha256", Hashing.sha256Hex("STATEMENT clean".getBytes(StandardCharsets.UTF_8)))
          .containsEntry("bytes", "15").doesNotContainKey("fault");
    });
  }

  @Test
  void aMissingFileIsNotWrittenForTheNextNFilesOnly() {
    engine.add("trustbank", MockScenario.of("mt940", MockBehaviour.MISSING_FILE).withNextN(1));

    List<RecordedRequest> first = produce();
    assertThat(files.exists(PATH)).isFalse();
    assertThat(first).singleElement().satisfies(e -> {
      assertThat(e.method()).isEqualTo("SKIP");
      assertThat(e.headers()).containsEntry("fault", "MISSING_FILE");
    });

    produce();
    assertThat(files.exists(PATH)).as("the scenario was for the next file only").isTrue();
    assertThat(engine.list("trustbank")).isEmpty();
  }

  @Test
  void aLateFileArrivesAfterItsDelay() {
    engine.add("trustbank", MockScenario.of("mt940", MockBehaviour.LATE_FILE).withDelayMs(150L).withNextN(1));

    List<RecordedRequest> entries = produce();

    assertThat(entries).singleElement().satisfies(e -> {
      assertThat(e.method()).isEqualTo("LATE");
      assertThat(e.headers()).containsEntry("fault", "LATE_FILE").containsEntry("delayMs", "150");
    });
    assertThat(files.exists(PATH)).isFalse();
    await().atMost(Duration.ofSeconds(5)).until(() -> files.exists(PATH));
    await().atMost(Duration.ofSeconds(5)).until(() -> journal.query("trustbank", PATH, null, null).size() == 2);
    assertThat(journal.query("trustbank", PATH, null, null).get(1).method()).isEqualTo("WRITE");
  }

  @Test
  void aDuplicateFileIsDeliveredTwiceUnderTwoNames() {
    engine.add("trustbank", MockScenario.of("mt940", MockBehaviour.DUPLICATE_FILE).withNextN(1));

    List<RecordedRequest> entries = produce();

    assertThat(entries).extracting(RecordedRequest::path)
        .containsExactly(PATH, "trustbank/out/MT940_0011_20261120_DUP.fin");
    assertThat(files.read("trustbank/out/MT940_0011_20261120_DUP.fin")).isEqualTo(files.read(PATH));
    assertThat(entries.get(1).headers()).containsEntry("fault", "DUPLICATE_FILE");
  }

  @Test
  void aCorruptFileIsCutShortAndMarked() {
    engine.add("trustbank", MockScenario.of(null, MockBehaviour.CORRUPT_FILE).withKey("2026-11-20"));

    produce();

    String written = text(files.read(PATH));
    assertThat(written).isEqualTo("STATEME" + FileMocks.CORRUPTION);
    assertThat(journal.query("trustbank", null, null, null)).singleElement()
        .satisfies(e -> assertThat(e.headers()).containsEntry("fault", "CORRUPT_FILE"));
  }

  @Test
  void anInjectedMismatchReachesTheFileBuilderWithItsKindAndCount() {
    engine.add("trustbank", MockScenario.of("mt940", MockBehaviour.INJECTED_MISMATCH).withCode("AMOUNT_MISMATCH")
        .withCount(3).withNextN(1));

    List<RecordedRequest> entries = produce();

    assertThat(text(files.read(PATH))).isEqualTo("STATEMENT AMOUNT_MISMATCHx3");
    assertThat(entries.get(0).headers()).containsEntry("fault", "INJECTED_MISMATCH:AMOUNT_MISMATCH:3");
    produce();
    assertThat(text(files.read(PATH))).isEqualTo("STATEMENT clean");
  }

  @Test
  void aKeyScopedFaultLeavesOtherDatesAlone() {
    engine.add("trustbank", MockScenario.of(null, MockBehaviour.MISSING_FILE).withKey("2026-11-21"));

    produce();

    assertThat(files.exists(PATH)).isTrue();
  }

  @Test
  void aMismatchKindThePartyLacksIsRefused() {
    assertThatThrownBy(() -> engine.add("trustbank",
        MockScenario.of("mt940", MockBehaviour.INJECTED_MISMATCH).withCode("UNKNOWN_TXN")))
        .isInstanceOf(MwException.class).hasMessageContaining("MISSING_IN_PARTNER");
    assertThatThrownBy(() -> engine.add("ocs", MockScenario.of("topup", MockBehaviour.MISSING_FILE)))
        .isInstanceOf(MwException.class);
  }

  @Test
  void mismatchDefaultsToOneLine() {
    engine.add("trustbank", MockScenario.of("mt940", MockBehaviour.INJECTED_MISMATCH).withCode("DC_FLIP"));

    produce();

    assertThat(text(files.read(PATH))).isEqualTo("STATEMENT DC_FLIPx1");
    assertThat(new Mismatch("DC_FLIP", 1).count()).isEqualTo(1);
  }
}
