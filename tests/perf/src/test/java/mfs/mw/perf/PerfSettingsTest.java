package mfs.mw.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import mfs.mw.perf.mix.Flow;
import mfs.mw.perf.mix.ScenarioMix;
import mfs.mw.perf.report.RunWindow;
import mfs.mw.perf.report.TxnLog;
import mfs.mw.perf.report.TxnRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerfSettingsTest {

  @TempDir Path dir;

  @Test
  void the_defaults_are_the_criterion_7_run() {
    PerfSettings s = PerfSettings.fromSystem();

    assertThat(s.targetTps()).isEqualTo(100.0);
    assertThat(s.targetP95Ms()).isEqualTo(150);
    assertThat(s.tps()).isGreaterThan(s.targetTps());
    assertThat(s.ramp()).isEqualTo(Duration.ofSeconds(60));
    assertThat(s.steady()).isEqualTo(Duration.ofMinutes(5));
    assertThat(s.mix()).isEqualTo(ScenarioMix.defaults());
  }

  @Test
  void durations_take_short_and_iso_forms() {
    assertThat(PerfSettings.duration("90s")).isEqualTo(Duration.ofSeconds(90));
    assertThat(PerfSettings.duration("10m")).isEqualTo(Duration.ofMinutes(10));
    assertThat(PerfSettings.duration("1h")).isEqualTo(Duration.ofHours(1));
    assertThat(PerfSettings.duration("PT2M")).isEqualTo(Duration.ofMinutes(2));
  }

  @Test
  void the_transaction_log_round_trips_through_the_run_directory() throws Exception {
    TxnRecord a = new TxnRecord(Flow.P2P, "0b6f1e5e-0000-4000-8000-000000000001", Instant.ofEpochMilli(1000), 85, 200, null, true);
    TxnRecord b = new TxnRecord(Flow.TOPUP_REFUSED, null, Instant.ofEpochMilli(2000), 120, 502, "MW-PTR-GP-TOPUP_REJECTED", true);
    try (TxnLog log = new TxnLog(dir)) {
      log.append(a);
      log.append(b);
      log.window(new RunWindow(Instant.ofEpochMilli(0), Instant.ofEpochMilli(5000), 100, 150));
    }

    assertThat(TxnRecord.parse(Files.readAllLines(dir.resolve("txns.csv")))).containsExactly(a, b);
    assertThat(dir.resolve("run.properties")).exists();
  }
}
