package mfs.mw.perf.mix;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The share of each {@link Flow} in the load: weights, scaled to the target transaction rate. The
 * default is the documented MVP mix (README.md "Scenario mix"); {@code -Dmw.perf.mix=P2P=3,TOPUP=1}
 * replaces it (unlisted flows then do not run).
 */
public final class ScenarioMix {

  private final Map<Flow, Integer> weights;
  private final int total;

  private ScenarioMix(Map<Flow, Integer> weights) {
    this.weights = Collections.unmodifiableMap(new EnumMap<>(weights));
    this.total = weights.values().stream().mapToInt(Integer::intValue).sum();
    if (total <= 0) {
      throw new IllegalArgumentException("the mix needs at least one flow with a positive weight");
    }
  }

  /** The MVP mix, in percent. */
  public static ScenarioMix defaults() {
    Map<Flow, Integer> w = new EnumMap<>(Flow.class);
    w.put(Flow.P2P, 30);
    w.put(Flow.CASH_IN, 10);
    w.put(Flow.CASH_OUT, 10);
    w.put(Flow.MERCHANT_PAY, 13);
    w.put(Flow.TOPUP, 13);
    w.put(Flow.TOPUP_REFUSED, 2);
    w.put(Flow.BILL_PAY, 10);
    w.put(Flow.ADD_MONEY_BANK, 7);
    w.put(Flow.USSD_P2P, 5);
    return new ScenarioMix(w);
  }

  /** {@code FLOW=weight,...}; blank or null: {@link #defaults()}. */
  public static ScenarioMix parse(String spec) {
    if (spec == null || spec.isBlank()) {
      return defaults();
    }
    Map<Flow, Integer> w = new EnumMap<>(Flow.class);
    for (String part : spec.split(",")) {
      String p = part.strip();
      int eq = p.indexOf('=');
      if (eq < 1) {
        throw new IllegalArgumentException("mix entry '" + p + "' is not FLOW=weight");
      }
      String name = p.substring(0, eq).strip();
      Flow flow;
      try {
        flow = Flow.valueOf(name);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("unknown flow '" + name + "' in the mix", e);
      }
      int weight;
      try {
        weight = Integer.parseInt(p.substring(eq + 1).strip());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("weight of " + name + " is not a number", e);
      }
      if (weight < 0) {
        throw new IllegalArgumentException("weight of " + name + " is negative");
      }
      if (weight > 0) {
        w.put(flow, weight);
      }
    }
    return new ScenarioMix(w);
  }

  /** The flows that run (positive weight). */
  public Set<Flow> flows() {
    return weights.keySet();
  }

  /** Arrivals per second of {@code flow} when the whole mix runs at {@code totalTps}. */
  public double ratePerSecond(Flow flow, double totalTps) {
    return totalTps * weights.getOrDefault(flow, 0) / total;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ScenarioMix m && m.weights.equals(weights);
  }

  @Override
  public int hashCode() {
    return weights.hashCode();
  }

  @Override
  public String toString() {
    return weights.toString();
  }
}
