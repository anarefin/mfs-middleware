package mfs.mw.adapter.billpay.partner.route;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigStore;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * {@code partners} on {@code /actuator/health} (ADR D26): one entry per partner with its live
 * version, route and state, UP when its route runs, DOWN when it does not, DISABLED or DELETED.
 * The indicator itself stays UP, and it is not in the readiness group: one partner whose route
 * fails makes that partner DOWN, never the whole service (Camel's own health checks skip the
 * partner and Kamelet routes, {@code camel.health.exclude-pattern}).
 */
@Component("partners")
public class PartnerRouteHealth implements HealthIndicator {

  private final PartnerConfigStore store;
  private final PartnerRoutes routes;

  public PartnerRouteHealth(PartnerConfigStore store, PartnerRoutes routes) {
    this.store = store;
    this.routes = routes;
  }

  @Override
  public Health health() {
    Map<String, Object> partners = new TreeMap<>();
    store.entries().forEach((id, entry) -> partners.put(id, state(entry)));
    return Health.up().withDetails(partners).build();
  }

  /** The state of one partner: UP, DOWN, DISABLED or DELETED. */
  public Map<String, Object> state(PartnerConfigStore.Entry entry) {
    Map<String, Object> out = new LinkedHashMap<>();
    PartnerRuntime runtime = entry.runtime();
    out.put("version", entry.version());
    if (runtime == null) {
      out.put("status", "DELETED");
      return out;
    }
    out.put("route", runtime.routeId());
    out.put("status", !runtime.config().enabled() ? "DISABLED" : routes.isStarted(runtime) ? "UP" : "DOWN");
    return out;
  }
}
