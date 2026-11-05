package mfs.mw.perf.report;

import java.time.Instant;
import java.util.Properties;

/**
 * The steady phase of a run (after the ramp) and its targets: at least {@code targetTps}
 * transactions per second started in {@code [from, to)} and a p95 middleware overhead under
 * {@code targetP95Ms}. Written by the simulation to {@code run.properties}, read by the report.
 */
public record RunWindow(Instant from, Instant to, double targetTps, long targetP95Ms) {

  public Properties toProperties() {
    Properties p = new Properties();
    p.setProperty("steadyFrom", from.toString());
    p.setProperty("steadyTo", to.toString());
    p.setProperty("targetTps", Double.toString(targetTps));
    p.setProperty("targetP95Ms", Long.toString(targetP95Ms));
    return p;
  }

  public static RunWindow fromProperties(Properties p) {
    return new RunWindow(
        Instant.parse(p.getProperty("steadyFrom")),
        Instant.parse(p.getProperty("steadyTo")),
        Double.parseDouble(p.getProperty("targetTps")),
        Long.parseLong(p.getProperty("targetP95Ms")));
  }

  public double seconds() {
    return (to.toEpochMilli() - from.toEpochMilli()) / 1000.0;
  }

  public boolean contains(Instant t) {
    return !t.isBefore(from) && t.isBefore(to);
  }
}
