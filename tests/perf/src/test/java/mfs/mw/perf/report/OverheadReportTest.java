package mfs.mw.perf.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mfs.mw.perf.mix.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OverheadReportTest {

  private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");
  private static final RunWindow WINDOW = new RunWindow(T0, T0.plusSeconds(10), 100, 150);

  @TempDir Path dir;

  private static List<TxnRecord> run(long e2eMs) {
    List<TxnRecord> txns = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      txns.add(new TxnRecord(i % 2 == 0 ? Flow.P2P : Flow.TOPUP, "t" + i, T0.plusMillis(i * 10L), e2eMs, 200, null, true));
    }
    return txns;
  }

  @Test
  void a_run_that_meets_the_target_is_reported_as_met_with_its_figures() throws Exception {
    Overhead.Result r = Overhead.compute(run(130), Map.of(), Map.of(), WINDOW);

    String md = OverheadReport.markdown(r);

    assertThat(md)
        .contains("Result: MET")
        .contains("| Achieved rate (steady window) | 100.0 TPS | >= 100 TPS |")
        .contains("| p95 middleware overhead | 130 ms | < 150 ms |")
        .contains("| P2P | 500 |")
        .contains("| TOPUP | 500 |")
        .contains("Unexpected failures: 0");
  }

  @Test
  void a_missed_target_is_reported_as_a_gap_naming_the_failures() {
    List<TxnRecord> txns = run(400);
    txns.add(new TxnRecord(Flow.P2P, null, T0, 25_000, 202, null, false));

    String md = OverheadReport.markdown(Overhead.compute(txns, Map.of(), Map.of(), WINDOW));

    assertThat(md).contains("Result: GAP").contains("| 202 - | 1 |").contains("| p95 middleware overhead | 400 ms | < 150 ms |");
  }

  @Test
  void the_report_reads_the_run_directory_and_writes_markdown_and_json_next_to_it() throws Exception {
    List<String> lines = new ArrayList<>(List.of(TxnRecord.HEADER));
    run(90).forEach(t -> lines.add(t.toCsv()));
    Files.write(dir.resolve("txns.csv"), lines);
    try (var out = Files.newBufferedWriter(dir.resolve("run.properties"))) {
      WINDOW.toProperties().store(out, null);
    }

    Overhead.Result r =
        OverheadReport.write(
            dir,
            since -> Map.of("t0", 40L),
            since -> Map.of(),
            since -> Map.of("p95 outbox relay wait, mw-orchestrator (txn.outbox)", "12 ms"));

    assertThat(r.meetsTarget()).isTrue();
    assertThat(Files.readString(dir.resolve("overhead.md")))
        .contains("Result: MET")
        .contains("## Diagnostics")
        .contains("- p95 outbox relay wait, mw-orchestrator (txn.outbox): 12 ms");
    JsonNode json = JsonMapper.builder().build().readTree(Files.readString(dir.resolve("overhead.json")));
    assertThat(json.path("meetsTarget").asBoolean()).isTrue();
    assertThat(json.path("overall").path("p95").asLong()).isEqualTo(90);
    assertThat(json.path("achievedTps").asDouble()).isEqualTo(100.0);
  }
}
