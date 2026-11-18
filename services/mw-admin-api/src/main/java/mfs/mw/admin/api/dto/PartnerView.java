package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** A partner as {@code admin.v1} shows it (ticket 38): its config as last published, and who changed it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartnerView(
    String partnerId,
    String family,
    long version,
    PartnerConfigValue config,
    Instant updatedAt,
    String updatedBy,
    String approvedBy,
    Route route) {

  /**
   * The route version last approved (ticket 83) and what its adapter reported on {@code
   * mw.partner.route.status.v1}; null for a partner of the old shape.
   *
   * @param status PENDING_DRY_RUN until the adapter reports, then LIVE or REJECTED
   * @param liveVersion the newest version reported LIVE; null while none is
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Route(
      long version, String status, List<String> problems, List<String> kamelets, Instant statusAt, Long liveVersion) {
    public Route {
      problems = problems == null ? List.of() : List.copyOf(problems);
      kamelets = kamelets == null ? List.of() : List.copyOf(kamelets);
    }
  }

  /** {@code GET /admin/v1/partners/kamelet-usage}: each Kamelet and the live routes that use it. */
  public record KameletUsage(List<Kamelet> kamelets) {
    public KameletUsage {
      kamelets = List.copyOf(kamelets);
    }

    public record Kamelet(String name, List<RouteRef> routes) {
      public Kamelet {
        routes = List.copyOf(routes);
      }
    }

    public record RouteRef(String partnerId, long version) {}
  }

  /** Every partner, by id. */
  public record Page(List<PartnerView> items) {
    public Page {
      items = List.copyOf(items);
    }
  }
}
