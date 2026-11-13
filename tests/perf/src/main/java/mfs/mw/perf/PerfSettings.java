package mfs.mw.perf;

import java.time.Duration;
import mfs.mw.perf.mix.ScenarioMix;

/**
 * Settings of a run, from {@code -Dmw.perf.*} (forwarded by Gradle; {@code scripts/suite.sh perf} passes none,
 * so the defaults are the criterion-7 run):
 *
 * <ul>
 *   <li>{@code mw.perf.tps} (102): injected transactions per second in the steady phase, a little
 *       above the target so the steady window's count is not short by rounding
 *   <li>{@code mw.perf.target-tps} (100), {@code mw.perf.target-p95-ms} (150): criterion 7
 *   <li>{@code mw.perf.ramp} (60s), {@code mw.perf.steady} (5m): ISO-8601 or {@code 90s} / {@code 5m}
 *   <li>{@code mw.perf.mix}: {@code FLOW=weight,...} ({@link ScenarioMix#parse}); default the MVP mix
 *   <li>{@code mw.perf.status-share} (0.2): share of app transactions the app then reads back
 *   <li>{@code mw.perf.setup-parallelism} (16): concurrent device bindings during setup
 * </ul>
 */
public record PerfSettings(
    double tps,
    double targetTps,
    long targetP95Ms,
    Duration ramp,
    Duration steady,
    ScenarioMix mix,
    double statusShare,
    int setupParallelism) {

  public static PerfSettings fromSystem() {
    return new PerfSettings(
        Double.parseDouble(System.getProperty("mw.perf.tps", "102")),
        Double.parseDouble(System.getProperty("mw.perf.target-tps", "100")),
        Long.parseLong(System.getProperty("mw.perf.target-p95-ms", "150")),
        duration(System.getProperty("mw.perf.ramp", "60s")),
        duration(System.getProperty("mw.perf.steady", "5m")),
        ScenarioMix.parse(System.getProperty("mw.perf.mix")),
        Double.parseDouble(System.getProperty("mw.perf.status-share", "0.2")),
        Integer.getInteger("mw.perf.setup-parallelism", 16));
  }

  /** {@code PT90S}, {@code 90s}, {@code 5m} or {@code 1h}. */
  static Duration duration(String s) {
    String v = s.strip().toLowerCase(java.util.Locale.ROOT);
    if (v.startsWith("pt")) {
      return Duration.parse(v.toUpperCase(java.util.Locale.ROOT));
    }
    long n = Long.parseLong(v.substring(0, v.length() - 1));
    return switch (v.charAt(v.length() - 1)) {
      case 's' -> Duration.ofSeconds(n);
      case 'm' -> Duration.ofMinutes(n);
      case 'h' -> Duration.ofHours(n);
      default -> throw new IllegalArgumentException("not a duration: " + s);
    };
  }
}
