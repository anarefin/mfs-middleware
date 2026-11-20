package mfs.mw.simulators.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.simulators.dto.MockSwitch;

/**
 * The routing health a mock-control switch implies (ticket 112c, FRS T8). Routing skips a partner
 * whose health is DOWN or MAINTENANCE before the call, so a closed socket after the request is
 * sent is never what fails the attempt over.
 */
public final class OcsRouteHealth {

  private OcsRouteHealth() {}

  /** The telco partner id routing knows, or null when this party is not an OCS profile. */
  public static String partnerId(String party) {
    return switch (party == null ? "" : party) {
      case "ocs-gp" -> "gp-ocs";
      case "ocs-robi" -> "robi-ocs";
      case "ocs-teletalk" -> "teletalk-ocs";
      case "ocs-bl" -> "bl-ocs";
      case "ocs" -> "ocs";
      default -> null;
    };
  }

  /** DOWN and MAINTENANCE are not usable; UP and SLOW are. */
  public static String health(MockSwitch.State state) {
    return switch (state) {
      case DOWN -> "DOWN";
      case MAINTENANCE -> "MAINTENANCE";
      case UP, SLOW -> "UP";
    };
  }

  /**
   * A health report on {@code mw.partner.route.status.v1}: version 0 (the adapter seed, which no
   * console approved) requires {@code health}.
   */
  public static Map<String, Object> event(String partnerId, String health, String reportedBy, Instant reportedAt) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("schemaVersion", 1);
    event.put("partnerId", partnerId);
    event.put("version", 0);
    event.put("family", "telco");
    event.put("status", "LIVE");
    event.put("problems", List.of());
    event.put("health", health);
    event.put("reportedBy", reportedBy);
    event.put("reportedAt", reportedAt.toString());
    return event;
  }
}
