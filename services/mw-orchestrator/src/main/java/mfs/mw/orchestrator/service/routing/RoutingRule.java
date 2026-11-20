package mfs.mw.orchestrator.service.routing;

import java.util.List;

/**
 * One routing rule (ticket 98, FRS PLT-040). A null filter matches any value. {@code fallbacks}
 * is the ordered list of connectors after {@code primaryPartnerId}. A higher {@code priority}
 * wins only when two rules are equally specific.
 */
public record RoutingRule(
    String id,
    String txnType,
    String productCode,
    String billerCode,
    String bankCode,
    String operatorCode,
    Long amountMinMinor,
    Long amountMaxMinor,
    String channel,
    String primaryPartnerId,
    List<String> fallbacks,
    int priority,
    boolean enabled,
    int version) {

  public RoutingRule {
    fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
  }
}
