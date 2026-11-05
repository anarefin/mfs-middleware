package mfs.mw.perf.report;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import mfs.mw.perf.mix.Flow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code ./gradlew perfOverhead} (run by {@code make perf} after {@code gatlingRun}): reads the last
 * run's {@code txns.csv} and {@code run.properties} (written by the simulation to {@code
 * build/reports/perf/}), subtracts core and partner time per transaction ({@link RunFacts}) and
 * writes {@code overhead.md} and {@code overhead.json} there. Exits 1 when the target is missed
 * (criterion 7: at least 100 TPS, p95 overhead under 150 ms, no unexpected failure) unless
 * {@code -Dmw.perf.report-only=true}.
 */
public final class OverheadReport {

  private static final Logger log = LoggerFactory.getLogger(OverheadReport.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private OverheadReport() {}

  public static void main(String[] args) {
    Path dir = Path.of(System.getProperty("mw.perf.run-dir", "build/reports/perf"));
    if (!Files.isRegularFile(dir.resolve("txns.csv"))) {
      throw new IllegalStateException("no run in " + dir + ": run ./gradlew gatlingRun first");
    }
    Overhead.Result r =
        write(dir, RunFacts::coreMs, since -> RunFacts.partnerMs(since, Duration.ofMinutes(2)), RunFacts::diagnostics);
    log.info(
        "event=perf.report result={} tps={} p95OverheadMs={} unexpectedFailures={} report={}",
        r.meetsTarget() ? "MET" : "GAP",
        String.format(Locale.ROOT, "%.1f", r.achievedTps()),
        r.overall().p95(),
        r.unexpectedFailures(),
        dir.resolve("overhead.md"));
    if (!r.meetsTarget() && !Boolean.getBoolean("mw.perf.report-only")) {
      System.exit(1);
    }
  }

  /**
   * Computes the run in {@code dir} with the given per-txn core and partner time sources and writes
   * the report, with the diagnostics (label -> value) that help explain a gap.
   */
  static Overhead.Result write(
      Path dir,
      Function<Instant, Map<String, Long>> core,
      Function<Instant, Map<String, Long>> partner,
      Function<Instant, Map<String, String>> diagnostics) {
    try {
      List<TxnRecord> txns = TxnRecord.parse(Files.readAllLines(dir.resolve("txns.csv")));
      Properties p = new Properties();
      try (Reader in = Files.newBufferedReader(dir.resolve("run.properties"))) {
        p.load(in);
      }
      RunWindow window = RunWindow.fromProperties(p);
      Instant since = txns.stream().map(TxnRecord::start).min(Instant::compareTo).orElse(window.from()).minusSeconds(60);
      Overhead.Result r = Overhead.compute(txns, core.apply(since), partner.apply(since), window);
      StringBuilder md = new StringBuilder(markdown(r));
      Map<String, String> diag = diagnostics.apply(since);
      if (!diag.isEmpty()) {
        md.append("\n## Diagnostics\n\n");
        diag.forEach((k, v) -> md.append("- ").append(k).append(": ").append(v).append("\n"));
      }
      Files.writeString(dir.resolve("overhead.md"), md.toString());
      Files.writeString(dir.resolve("overhead.json"), json(r));
      return r;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String markdown(Overhead.Result r) {
    RunWindow w = r.window();
    StringBuilder md = new StringBuilder();
    md.append("# Perf run: middleware overhead (MVP acceptance criterion 7)\n\n");
    md.append("Result: ").append(r.meetsTarget() ? "MET" : "GAP").append("\n\n");
    md.append("Steady window ").append(w.from()).append(" to ").append(w.to()).append("; overhead = submit")
        .append(" response time minus core-mock and partner-simulator time per transaction (tests/perf/README.md).\n\n");
    md.append("| Figure | Value | Target |\n| --- | --- | --- |\n");
    md.append(String.format(Locale.ROOT, "| Achieved rate (steady window) | %.1f TPS | >= %.0f TPS |%n", r.achievedTps(), w.targetTps()));
    md.append(String.format(Locale.ROOT, "| p95 middleware overhead | %d ms | < %d ms |%n", r.overall().p95(), w.targetP95Ms()));
    md.append(String.format(Locale.ROOT, "| p99 middleware overhead | %d ms | (NFR-02 < 300 ms) |%n", r.overall().p99()));
    md.append(String.format(Locale.ROOT, "| p95 end to end (app submits) | %d ms | |%n", r.endToEnd().p95()));
    md.append(String.format(Locale.ROOT, "| p95 USSD session (all screens) | %d ms | |%n", r.ussdSessions().p95()));
    md.append(String.format(Locale.ROOT, "| Transactions | %d | |%n%n", r.transactions()));
    md.append("## Overhead per flow (ms)\n\n| Flow | Count | p50 | p95 | p99 | max |\n| --- | --- | --- | --- | --- | --- |\n");
    for (Map.Entry<Flow, Overhead.Stats> e : r.byFlow().entrySet()) {
      Overhead.Stats s = e.getValue();
      md.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %d |%n", e.getKey(), s.count(), s.p50(), s.p95(), s.p99(), s.max()));
    }
    md.append("\nUnexpected failures: ").append(r.unexpectedFailures()).append("\n");
    if (!r.failuresByCode().isEmpty()) {
      md.append("\n| HTTP status and code | Count |\n| --- | --- |\n");
      r.failuresByCode().forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v).append(" |\n"));
    }
    return md.toString();
  }

  private static String json(Overhead.Result r) {
    ObjectNode n = JSON.createObjectNode();
    n.put("meetsTarget", r.meetsTarget());
    n.put("achievedTps", r.achievedTps());
    n.put("targetTps", r.window().targetTps());
    n.put("targetP95Ms", r.window().targetP95Ms());
    n.put("steadyFrom", r.window().from().toString());
    n.put("steadyTo", r.window().to().toString());
    n.put("transactions", r.transactions());
    n.put("unexpectedFailures", r.unexpectedFailures());
    n.set("overall", JSON.valueToTree(r.overall()));
    n.set("endToEnd", JSON.valueToTree(r.endToEnd()));
    n.set("ussdSessions", JSON.valueToTree(r.ussdSessions()));
    ObjectNode flows = n.putObject("byFlow");
    r.byFlow().forEach((f, s) -> flows.set(f.name(), JSON.valueToTree(s)));
    ObjectNode failures = n.putObject("failuresByCode");
    r.failuresByCode().forEach(failures::put);
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n);
  }
}
