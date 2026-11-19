package mfs.mw.adapter.bank.partner.route;

import java.util.List;

/**
 * What became of one partner record version (ticket 83): {@code LIVE} (it serves calls) or {@code
 * REJECTED} (refused by the loader, or its dry-run failed; the previous version still serves). The
 * framework hands it to the service's {@link Sink} ({@code messaging/PartnerRouteStatusPublisher},
 * {@code mw.partner.route.status.v1} through the outbox). Problems name fields and rules, never a
 * secret value or a personal datum.
 *
 * <p>Ticket 97: a health report is a LIVE status of the live version with its {@code health}
 * ({@link PartnerRouteHealth.State}), sent by {@link PartnerHealthMonitor} on every change (a
 * seed's version is 0); a load report has none.
 *
 * @param kamelets the mw-* Kamelets a LIVE version uses (Kamelet usage); empty when REJECTED
 * @param health the partner's health of a health report, else null
 */
public record PartnerRouteStatus(
    String partnerId, long version, Status status, List<String> problems, List<String> kamelets, String health) {

  public enum Status {
    LIVE,
    REJECTED
  }

  public PartnerRouteStatus {
    problems = problems == null ? List.of() : List.copyOf(problems);
    kamelets = kamelets == null ? List.of() : List.copyOf(kamelets);
  }

  /** A load report (no health). */
  public PartnerRouteStatus(String partnerId, long version, Status status, List<String> problems, List<String> kamelets) {
    this(partnerId, version, status, problems, kamelets, null);
  }

  /** The health report of the live version {@code runtime}. */
  public static PartnerRouteStatus health(PartnerRuntime runtime, PartnerRouteHealth.State health) {
    return new PartnerRouteStatus(
        runtime.id(), runtime.version(), Status.LIVE, List.of(), runtime.kamelets().stream().sorted().toList(), health.name());
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
