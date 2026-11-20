package mfs.mw.audit.dw.util;

/**
 * The timeline hop of an audit event (ticket 130, FRS AUD-04): the producer's {@code hop} when it
 * sent one, else inferred from the action, so events of producers that predate the field still
 * land on the timeline: {@code txn.*} (state changes, operator actions, outcome conflicts) ORCHESTRATOR,
 * {@code partner.call.completed} PARTNER_CALL, {@code partner.callback.*} PARTNER_CALLBACK,
 * {@code core.*} CORE_CALL, {@code notification.*} / {@code notify.*} NOTIFICATION; anything else
 * stays without a hop.
 */
public final class Hops {

  private Hops() {}

  public static String effective(String hop, String action) {
    if (hop != null) {
      return hop;
    }
    if (action == null) {
      return null;
    }
    if (action.equals("partner.call.completed")) {
      return "PARTNER_CALL";
    }
    if (action.startsWith("partner.callback.")) {
      return "PARTNER_CALLBACK";
    }
    if (action.startsWith("txn.")) {
      return "ORCHESTRATOR";
    }
    if (action.startsWith("core.")) {
      return "CORE_CALL";
    }
    if (action.startsWith("notification.") || action.startsWith("notify.")) {
      return "NOTIFICATION";
    }
    return null;
  }
}
