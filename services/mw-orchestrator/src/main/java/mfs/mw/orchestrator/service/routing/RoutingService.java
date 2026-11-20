package mfs.mw.orchestrator.service.routing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Picks the connector for a transaction (ticket 98, FRS PLT-040, PLT-042, PLT-043). The most
 * specific enabled rule wins; a caller-supplied partner id is used only when that rule allows it
 * (it is the connector, a fallback, or the operator, biller or bank the rule names). A partner
 * whose health is DOWN or MAINTENANCE, or that a maintenance message covers, is skipped. Failover
 * after a call has left is not decided here.
 */
@Component
public class RoutingService {

  public static final String CIRCUIT_OPEN = "MW-SYS-PARTNER_CIRCUIT_OPEN";
  public static final String PARTNER_DISABLED = "MW-SYS-PARTNER_DISABLED";

  public Decision decide(RoutingQuery query, RoutingSnapshot snapshot, Instant now) {
    List<RoutingRule> matched = snapshot.rules().stream().filter(r -> matches(r, query)).toList();
    if (matched.isEmpty()) {
      return new Decision.UnknownPartner();
    }
    RoutingRule winner =
        matched.stream()
            .max(Comparator.comparingInt(RoutingService::specificity).thenComparingInt(RoutingRule::priority).thenComparing(RoutingRule::id))
            .orElseThrow();
    if (!allows(winner, query.requestedPartnerId())) {
      return new Decision.UnknownPartner();
    }
    List<Attempt> attempts = attempts(winner, query.requestedPartnerId());
    List<MaintenanceMessage> active = snapshot.messages().stream().filter(m -> active(m, now)).toList();
    List<Attempt> usable = new ArrayList<>();
    MaintenanceMessage blocking = null;
    boolean healthBlocked = false;
    for (Attempt attempt : attempts) {
      Optional<MaintenanceMessage> message = messageFor(active, attempt, query.billerCode());
      if (message.isPresent()) {
        if (blocking == null) {
          blocking = message.get();
        }
        continue;
      }
      if (!usableHealth(snapshot.healthByPartner().get(attempt.healthPartnerId()))) {
        healthBlocked = true;
        continue;
      }
      usable.add(attempt);
    }
    if (!usable.isEmpty()) {
      return new Decision.Ready(usable);
    }
    if (blocking != null) {
      return new Decision.Blocked(PARTNER_DISABLED, blocking.messageEn(), blocking.messageBn());
    }
    if (healthBlocked) {
      return new Decision.Blocked(CIRCUIT_OPEN, "No partner is available", null);
    }
    return new Decision.UnknownPartner();
  }

  private static boolean matches(RoutingRule rule, RoutingQuery query) {
    if (!rule.enabled() || rule.txnType() == null || !rule.txnType().equals(query.txnType())) {
      return false;
    }
    if (!same(rule.productCode(), query.productCode())) {
      return false;
    }
    if (!same(rule.billerCode(), query.billerCode())) {
      return false;
    }
    if (!same(rule.bankCode(), query.bankCode())) {
      return false;
    }
    if (!same(rule.operatorCode(), query.operatorCode())) {
      return false;
    }
    if (!same(rule.channel(), query.channel())) {
      return false;
    }
    if (rule.amountMinMinor() != null && query.amountMinor() < rule.amountMinMinor()) {
      return false;
    }
    return rule.amountMaxMinor() == null || query.amountMinor() <= rule.amountMaxMinor();
  }

  /** A null filter matches anything; a set filter matches case-insensitively. */
  private static boolean same(String filter, String value) {
    if (filter == null || filter.isBlank()) {
      return true;
    }
    return value != null && filter.equalsIgnoreCase(value);
  }

  private static int specificity(RoutingRule rule) {
    int score = 0;
    if (set(rule.productCode())) {
      score++;
    }
    if (set(rule.billerCode())) {
      score++;
    }
    if (set(rule.bankCode())) {
      score++;
    }
    if (set(rule.operatorCode())) {
      score++;
    }
    if (set(rule.channel())) {
      score++;
    }
    if (rule.amountMinMinor() != null || rule.amountMaxMinor() != null) {
      score++;
    }
    return score;
  }

  private static boolean set(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * The named partner is allowed when it is the primary connector, a fallback, or the operator,
   * biller or bank the rule is about. No name means routing chooses.
   */
  private static boolean allows(RoutingRule rule, String requested) {
    if (requested == null || requested.isBlank()) {
      return true;
    }
    if (eq(requested, rule.primaryPartnerId()) || rule.fallbacks().stream().anyMatch(f -> eq(requested, f))) {
      return true;
    }
    return eq(requested, rule.operatorCode()) || eq(requested, rule.billerCode()) || eq(requested, rule.bankCode());
  }

  private static List<Attempt> attempts(RoutingRule rule, String requested) {
    List<Attempt> out = new ArrayList<>();
    int start = 0;
    if (requested != null && rule.fallbacks().stream().anyMatch(f -> eq(requested, f)) && !eq(requested, rule.operatorCode())) {
      for (int i = 0; i < rule.fallbacks().size(); i++) {
        if (eq(rule.fallbacks().get(i), requested)) {
          start = i;
          break;
        }
      }
      for (int i = start; i < rule.fallbacks().size(); i++) {
        String id = rule.fallbacks().get(i);
        out.add(new Attempt(id, id));
      }
      return out;
    }
    String wire = rule.primaryPartnerId();
    if (requested != null && eq(requested, rule.operatorCode()) && !eq(requested, rule.primaryPartnerId())) {
      wire = requested;
    } else if (requested != null && eq(requested, rule.primaryPartnerId())) {
      wire = requested;
    }
    out.add(new Attempt(wire, rule.primaryPartnerId()));
    for (String fallback : rule.fallbacks()) {
      out.add(new Attempt(fallback, fallback));
    }
    return out;
  }

  private static boolean active(MaintenanceMessage message, Instant now) {
    if (!message.enabled()) {
      return false;
    }
    if (message.validFrom() != null && now.isBefore(message.validFrom())) {
      return false;
    }
    return message.validUntil() == null || now.isBefore(message.validUntil());
  }

  private static Optional<MaintenanceMessage> messageFor(List<MaintenanceMessage> messages, Attempt attempt, String biller) {
    for (MaintenanceMessage message : messages) {
      if (eq(message.partnerId(), attempt.wirePartnerId())
          || eq(message.partnerId(), attempt.healthPartnerId())
          || (set(message.billerCode()) && eq(message.billerCode(), biller))) {
        return Optional.of(message);
      }
    }
    return Optional.empty();
  }

  /** No report yet, UP and DEGRADED can be called. DOWN and MAINTENANCE cannot. */
  private static boolean usableHealth(String health) {
    if (health == null) {
      return true;
    }
    return switch (health.toUpperCase(Locale.ROOT)) {
      case "DOWN", "MAINTENANCE", "DISABLED", "DELETED" -> false;
      default -> true;
    };
  }

  private static boolean eq(String a, String b) {
    return a != null && b != null && a.equalsIgnoreCase(b);
  }
}
