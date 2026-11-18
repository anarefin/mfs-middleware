package mfs.mw.adapter.telco.partner.route;

import java.util.List;

/**
 * What became of one partner record version (ticket 83): {@code LIVE} (it serves calls) or {@code
 * REJECTED} (refused by the loader, or its dry-run failed; the previous version still serves). The
 * framework hands it to the service's {@link Sink} ({@code messaging/PartnerRouteStatusPublisher},
 * {@code mw.partner.route.status.v1} through the outbox). Problems name fields and rules, never a
 * secret value or a personal datum.
 *
 * @param kamelets the mw-* Kamelets a LIVE version uses (Kamelet usage); empty when REJECTED
 */
public record PartnerRouteStatus(String partnerId, long version, Status status, List<String> problems, List<String> kamelets) {

  public enum Status {
    LIVE,
    REJECTED
  }

  public PartnerRouteStatus {
    problems = problems == null ? List.of() : List.copyOf(problems);
    kamelets = kamelets == null ? List.of() : List.copyOf(kamelets);
  }

  public static PartnerRouteStatus live(PartnerRuntime runtime) {
    return new PartnerRouteStatus(runtime.id(), runtime.version(), Status.LIVE, List.of(), runtime.kamelets().stream().sorted().toList());
  }

  public static PartnerRouteStatus rejected(String partnerId, long version, List<String> problems) {
    return new PartnerRouteStatus(partnerId, version, Status.REJECTED, problems, List.of());
  }

  /** Where the statuses go; a failing sink is logged and never stops a record being applied. */
  @FunctionalInterface
  public interface Sink {
    void publish(PartnerRouteStatus status);
  }
}
