package mfs.mw.adapter.billpay.partner.route;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import mfs.mw.adapter.billpay.partner.config.PartnerConfig;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * {@code partners} on {@code /actuator/health} (ADR D28, ticket 97): one entry per partner with its
 * live version, route, circuit state and {@link State}. The indicator itself stays UP, and it is not
 * in the readiness group: one partner whose route fails makes that partner DOWN, never the whole
 * service (Camel's own health checks skip the partner and Kamelet routes, {@code
 * camel.health.exclude-pattern}).
 *
 * <p>State, first match: {@code DELETED} (no live version), {@code DISABLED} (record disabled),
 * {@code MAINTENANCE} (the record's {@code maintenance} flag), {@code DOWN} (route not running,
 * circuit breaker {@code mw-cb-<id>} open, or the last health probe failed), {@code DEGRADED}
 * (breaker half-open, or its failure rate at or over {@code
 * mw.partners.health.degraded-failure-rate}, default 25 %, while closed), else {@code UP}. Probe
 * results come from {@link PartnerHealthMonitor} and belong to one record version.
 */
@Component("partners")
public class PartnerRouteHealth implements HealthIndicator {

  /** Partner health (FRS CON-04, CON-05); routing (98) and the console read it. */
  public enum State {
    UP,
    DEGRADED,
    DOWN,
    MAINTENANCE,
    DISABLED,
    DELETED
  }

  private final PartnerConfigStore store;
  private final PartnerRoutes routes;
  private final CircuitBreakerRegistry breakers;
  private final float degradedFailureRate;
  /** {@code <id>@<version>} -> the last probe passed. */
  private final Map<String, Boolean> probes = new ConcurrentHashMap<>();

  public PartnerRouteHealth(
      PartnerConfigStore store,
      PartnerRoutes routes,
      CircuitBreakerRegistry breakers,
      @Value("${mw.partners.health.degraded-failure-rate:25}") float degradedFailureRate) {
    this.store = store;
    this.routes = routes;
    this.breakers = breakers;
    this.degradedFailureRate = degradedFailureRate;
  }

  @Override
  public Health health() {
    Map<String, Object> partners = new TreeMap<>();
    store.entries().forEach((id, entry) -> partners.put(id, state(entry)));
    return Health.up().withDetails(partners).build();
  }

  /** The details of one partner: version, route, circuit and status. */
  public Map<String, Object> state(PartnerConfigStore.Entry entry) {
    Map<String, Object> out = new LinkedHashMap<>();
    PartnerRuntime runtime = entry.runtime();
    out.put("version", entry.version());
    if (runtime != null) {
      out.put("route", runtime.routeId());
      breaker(runtime.id()).ifPresent(b -> out.put("circuit", b.getState().name()));
    }
    out.put("status", health(entry).name());
    return out;
  }

  /** The {@link State} of one partner. */
  public State health(PartnerConfigStore.Entry entry) {
    PartnerRuntime runtime = entry.runtime();
    if (runtime == null) {
      return State.DELETED;
    }
    PartnerConfig config = runtime.config();
    if (!config.enabled()) {
      return State.DISABLED;
    }
    if (config.maintenance()) {
      return State.MAINTENANCE;
    }
    if (!routes.isStarted(runtime) || Boolean.FALSE.equals(probes.get(key(runtime)))) {
      return State.DOWN;
    }
    Optional<CircuitBreaker> breaker = breaker(runtime.id());
    if (breaker.isEmpty()) {
      return State.UP;
    }
    return switch (breaker.get().getState()) {
      case OPEN, FORCED_OPEN -> State.DOWN;
      case HALF_OPEN -> State.DEGRADED;
      default -> breaker.get().getMetrics().getFailureRate() >= degradedFailureRate ? State.DEGRADED : State.UP;
    };
  }

  /** The result of a health probe of {@code runtime} (its version only). */
  void probed(PartnerRuntime runtime, boolean passed) {
    probes.keySet().removeIf(k -> k.startsWith(runtime.id() + "@"));
    probes.put(key(runtime), passed);
  }

  private Optional<CircuitBreaker> breaker(String id) {
    return breakers.find(id);
  }

  private static String key(PartnerRuntime runtime) {
    return runtime.id() + "@" + runtime.version();
  }
}
