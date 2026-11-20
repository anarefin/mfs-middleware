package mfs.mw.orchestrator.service.routing;

import java.util.List;
import java.util.Map;

/** The in-memory copy routing decides from. Replaced as a whole on reload. */
public record RoutingSnapshot(
    List<RoutingRule> rules,
    List<MsisdnPrefix> prefixes,
    List<MnpOverride> overrides,
    List<MaintenanceMessage> messages,
    Map<String, String> healthByPartner) {

  public RoutingSnapshot {
    rules = List.copyOf(rules);
    prefixes = List.copyOf(prefixes);
    overrides = List.copyOf(overrides);
    messages = List.copyOf(messages);
    healthByPartner = Map.copyOf(healthByPartner);
  }

  public static RoutingSnapshot empty() {
    return new RoutingSnapshot(List.of(), List.of(), List.of(), List.of(), Map.of());
  }

  public RoutingSnapshot withHealth(Map<String, String> health) {
    return new RoutingSnapshot(rules, prefixes, overrides, messages, health);
  }
}
