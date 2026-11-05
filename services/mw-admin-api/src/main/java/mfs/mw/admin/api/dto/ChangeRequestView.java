package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A change request as {@code admin.v1} shows it (ticket 37, spec 15.3). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangeRequestView(
    String id,
    String resourceType,
    String resourceId,
    String action,
    Map<String, Object> payload,
    Map<String, Object> diff,
    String makerId,
    String status,
    String checkerId,
    String reason,
    String decisionReason,
    Instant createdAt,
    Instant decidedAt,
    Instant expiresAt) {

  /** One page, newest first. */
  public record Page(List<ChangeRequestView> items, int limit, int offset) {

    public Page {
      items = List.copyOf(items);
    }
  }
}
