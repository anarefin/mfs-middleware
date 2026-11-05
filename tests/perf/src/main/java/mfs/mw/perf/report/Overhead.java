package mfs.mw.perf.report;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import mfs.mw.perf.mix.Flow;

/**
 * Middleware overhead of a run (spec NFR-02, MVP acceptance criterion 7): per app transaction the
 * end-to-end submit time minus the time the core (mw-core-mock) and the partners (mw-simulators)
 * spent on it, as percentiles overall and per flow; plus the achieved rate in the steady window
 * and the unexpected failures. USSD sessions span several screens with user think time removed by
 * the simulator, so their time is reported apart and not part of the overhead percentiles.
 */
public final class Overhead {

  private Overhead() {}

  /** Nearest-rank percentiles of a set of durations, ms. */
  public record Stats(int count, long p50, long p95, long p99, long max) {

    static Stats of(List<Long> values) {
      if (values.isEmpty()) {
        return new Stats(0, 0, 0, 0, 0);
      }
      List<Long> v = new ArrayList<>(values);
      v.sort(null);
      return new Stats(v.size(), rank(v, 50), rank(v, 95), rank(v, 99), v.getLast());
    }

    private static long rank(List<Long> sorted, int percentile) {
      int n = (int) Math.ceil(percentile / 100.0 * sorted.size());
      return sorted.get(Math.max(0, n - 1));
    }
  }

  /** The figures of one run. */
  public record Result(
      Stats overall,
      Map<Flow, Stats> byFlow,
      Stats endToEnd,
      Stats ussdSessions,
      double achievedTps,
      int transactions,
      int unexpectedFailures,
      Map<String, Integer> failuresByCode,
      RunWindow window) {

    /** At least the target rate, p95 overhead strictly under the target, no unexpected failure. */
    public boolean meetsTarget() {
      return achievedTps >= window.targetTps()
          && overall.count() > 0
          && overall.p95() < window.targetP95Ms()
          && unexpectedFailures == 0;
    }
  }

  /**
   * @param txns the run's transaction log
   * @param coreMs time the core spent per txnId (sum over its commands)
   * @param partnerMs time the partners spent per txnId (sum over the adapters' calls)
   */
  public static Result compute(
      List<TxnRecord> txns, Map<String, Long> coreMs, Map<String, Long> partnerMs, RunWindow window) {
    List<Long> all = new ArrayList<>();
    List<Long> e2e = new ArrayList<>();
    List<Long> ussd = new ArrayList<>();
    Map<Flow, List<Long>> perFlow = new EnumMap<>(Flow.class);
    Map<String, Integer> failures = new TreeMap<>();
    int unexpected = 0;
    int inWindow = 0;
    for (TxnRecord t : txns) {
      if (window.contains(t.start())) {
        inWindow++;
      }
      if (!t.expected()) {
        unexpected++;
        failures.merge(t.httpStatus() + " " + (t.errorCode() == null ? "-" : t.errorCode()), 1, Integer::sum);
        continue;
      }
      if (t.flow() == Flow.USSD_P2P) {
        ussd.add(t.e2eMs());
        continue;
      }
      long excluded = t.txnId() == null ? 0 : coreMs.getOrDefault(t.txnId(), 0L) + partnerMs.getOrDefault(t.txnId(), 0L);
      long overhead = Math.max(0, t.e2eMs() - excluded);
      all.add(overhead);
      e2e.add(t.e2eMs());
      perFlow.computeIfAbsent(t.flow(), f -> new ArrayList<>()).add(overhead);
    }
    Map<Flow, Stats> byFlow = new EnumMap<>(Flow.class);
    perFlow.forEach((f, v) -> byFlow.put(f, Stats.of(v)));
    return new Result(
        Stats.of(all),
        byFlow,
        Stats.of(e2e),
        Stats.of(ussd),
        inWindow / window.seconds(),
        txns.size(),
        unexpected,
        failures,
        window);
  }
}
