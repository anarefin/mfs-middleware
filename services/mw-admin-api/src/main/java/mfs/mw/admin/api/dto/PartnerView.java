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
    String approvedBy) {

  /** Every partner, by id. */
  public record Page(List<PartnerView> items) {
    public Page {
      items = List.copyOf(items);
    }
  }
}
